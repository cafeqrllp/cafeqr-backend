package com.restaurant.pos.backup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.restaurant.pos.auth.domain.User;
import com.restaurant.pos.auth.repository.UserRepository;
import com.restaurant.pos.auth.service.OtpService;
import com.restaurant.pos.common.exception.BusinessException;
import com.restaurant.pos.common.exception.ResourceNotFoundException;
import com.restaurant.pos.common.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class TenantBackupService {

    private static final String FORMAT_VERSION = "cafeqr-tenant-backup-v1";
    private static final String APP_NAME = "CafeQR 2.0 Test";
    private static final String TEST_WARNING = "UNENCRYPTED TEST BACKUP. Do not use this format in production.";
    private static final int QUERY_BATCH_SIZE = 500;
    private static final Pattern UUID_PATTERN = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private static final Set<String> EXCLUDED_TABLES = Set.of(
            "flyway_schema_history",
            "menus",
            "permissions",
            "refresh_tokens",
            "tenant_backups",
            "tenant_backup_settings",
            "audit_logs",
            "sync_operations",
            "print_jobs"
    );

    private static final Set<String> TOTAL_COLUMNS = Set.of(
            "amount",
            "total",
            "total_amount",
            "grand_total",
            "subtotal",
            "tax_amount",
            "discount_amount",
            "paid_amount",
            "balance_due",
            "debit",
            "credit",
            "quantity"
    );

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final OtpService otpService;
    private final TenantRestoreLockService restoreLockService;

    @Value("${app.backup.storage-dir:./data/backups}")
    private String backupStorageDir;

    @Value("${app.backup.restore-token-minutes:30}")
    private long restoreTokenMinutes;

    private Path storageRoot;

    @PostConstruct
    void initStorage() throws IOException {
        storageRoot = Path.of(backupStorageDir).toAbsolutePath().normalize();
        Files.createDirectories(storageRoot);
    }

    public BackupSettingsResponse getSettings() {
        UUID clientId = currentClientId();
        ensureDefaultSettings(clientId);
        return querySettings(clientId);
    }

    public BackupSettingsResponse updateSettings(BackupSettingsRequest request) {
        UUID clientId = currentClientId();
        boolean scheduleEnabled = Boolean.TRUE.equals(request.scheduleEnabled());
        String frequency = normalizeFrequency(request.scheduleFrequency());
        int retention = request.retentionCount() == null ? 10 : Math.max(1, Math.min(50, request.retentionCount()));

        jdbcTemplate.update("""
                INSERT INTO tenant_backup_settings (id, client_id, schedule_enabled, schedule_frequency, retention_count, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, NOW(), NOW())
                ON CONFLICT (client_id) DO UPDATE SET
                  schedule_enabled = EXCLUDED.schedule_enabled,
                  schedule_frequency = EXCLUDED.schedule_frequency,
                  retention_count = EXCLUDED.retention_count,
                  updated_at = NOW()
                """, UUID.randomUUID(), clientId, scheduleEnabled, frequency, retention);
        return querySettings(clientId);
    }

    public BackupCreateResponse createManualBackup() {
        User user = currentUser();
        ArchiveWriteResult result = createBackupArchive(currentClientId(), user, "MANUAL");
        return new BackupCreateResponse(
                result.backupId(),
                "COMPLETED",
                result.path().getFileName().toString(),
                result.sizeBytes(),
                result.archiveChecksum(),
                result.manifest()
        );
    }

    public List<BackupSummaryResponse> listBackups() {
        UUID clientId = currentClientId();
        return jdbcTemplate.query("""
                SELECT id, type, status, file_name, COALESCE(size_bytes, 0) AS size_bytes,
                       checksum_sha256, COALESCE(row_count, 0) AS row_count,
                       created_at, expires_at, error_message
                FROM tenant_backups
                WHERE client_id = ?
                  AND type IN ('MANUAL', 'PRE_RESTORE')
                ORDER BY created_at DESC
                LIMIT 100
                """, ps -> ps.setObject(1, clientId), (rs, rowNum) -> new BackupSummaryResponse(
                rs.getObject("id", UUID.class),
                rs.getString("type"),
                rs.getString("status"),
                rs.getString("file_name"),
                rs.getLong("size_bytes"),
                rs.getString("checksum_sha256"),
                rs.getLong("row_count"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("expires_at")),
                rs.getString("error_message")
        ));
    }

    public BackupDownload getDownload(UUID backupId) {
        UUID clientId = currentClientId();
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap("""
                    SELECT file_name, storage_path, COALESCE(size_bytes, 0) AS size_bytes
                    FROM tenant_backups
                    WHERE id = ? AND client_id = ? AND status = 'COMPLETED'
                    """, backupId, clientId);
            Path path = Path.of(Objects.toString(row.get("storage_path"), "")).toAbsolutePath().normalize();
            if (!path.startsWith(storageRoot) || !Files.exists(path)) {
                throw new ResourceNotFoundException("Backup file is not available on this server.");
            }
            return new BackupDownload(
                    Objects.toString(row.get("file_name"), path.getFileName().toString()),
                    path,
                    ((Number) row.get("size_bytes")).longValue()
            );
        } catch (EmptyResultDataAccessException ex) {
            throw new ResourceNotFoundException("Backup not found.");
        }
    }

    public BackupPreviewResponse previewRestore(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("Upload a CafeQR backup file first.");
        }

        UUID clientId = currentClientId();
        User user = currentUser();
        String token = UUID.randomUUID().toString().replace("-", "");
        Instant expiresAt = Instant.now().plus(Duration.ofMinutes(restoreTokenMinutes));
        String fileName = "restore-upload-" + shortId(clientId) + "-" + token + ".cqrbak";
        Path uploadPath = storageRoot.resolve(fileName).normalize();
        if (!uploadPath.startsWith(storageRoot)) {
            throw new BusinessException("Invalid backup file path.");
        }

        try {
            file.transferTo(uploadPath);
            ImportedBackup imported = readAndVerifyArchive(uploadPath);
            List<String> warnings = validateArchiveForCurrentClient(imported, clientId);
            String manifestJson = objectMapper.writeValueAsString(imported.manifest());
            String archiveChecksum = sha256Hex(uploadPath);

            jdbcTemplate.update("""
                    INSERT INTO tenant_backups (
                      id, client_id, requested_by_user_id, type, status, file_name, storage_path,
                      size_bytes, checksum_sha256, row_count, manifest_json, restore_token,
                      restore_token_expires_at, created_at, updated_at, expires_at
                    )
                    VALUES (?, ?, ?, 'RESTORE_UPLOAD', 'PREVIEWED', ?, ?, ?, ?, ?, ?::jsonb, ?, ?, NOW(), NOW(), ?)
                    """,
                    UUID.randomUUID(),
                    clientId,
                    user.getId(),
                    fileName,
                    uploadPath.toString(),
                    Files.size(uploadPath),
                    archiveChecksum,
                    imported.manifest().totalRows(),
                    manifestJson,
                    token,
                    java.sql.Timestamp.from(expiresAt),
                    java.sql.Timestamp.from(expiresAt));

            return new BackupPreviewResponse(token, expiresAt, imported.manifest(), warnings);
        } catch (IOException ex) {
            throw new BusinessException("Unable to read backup file: " + ex.getMessage());
        }
    }

    @Transactional
    public RestoreConfirmResponse confirmRestore(RestoreConfirmRequest request) {
        if (request == null || request.restoreToken() == null || request.restoreToken().isBlank()) {
            throw new BusinessException("Restore token is required. Preview the backup before confirming restore.");
        }
        if (!"RESTORE".equals(request.confirmationText())) {
            throw new BusinessException("Type RESTORE exactly to confirm this data restore.");
        }

        UUID clientId = currentClientId();
        User user = currentUser();
        if (!passwordEncoder.matches(Optional.ofNullable(request.currentPassword()).orElse(""), user.getPassword())) {
            throw new BusinessException("Current password is incorrect.");
        }
        if (!otpService.verifyOtp(user.getEmail(), request.otp())) {
            throw new BusinessException("Invalid or expired OTP.");
        }
        if (!restoreLockService.tryLock(clientId, Duration.ofHours(1))) {
            throw new BusinessException("A data restore is already in progress for this restaurant.");
        }

        try {
            RestoreUpload upload = findRestoreUpload(clientId, request.restoreToken());
            ImportedBackup imported = readAndVerifyArchive(upload.path());
            validateArchiveForCurrentClient(imported, clientId);

            ArchiveWriteResult preRestoreBackup = createBackupArchive(clientId, user, "PRE_RESTORE");
            TenantSnapshot currentSnapshot = collectTenantSnapshot(clientId);
            Map<String, TableInfo> metadata = loadTableInfo();

            deleteSnapshotRows(currentSnapshot);
            insertImportedRows(imported, metadata);
            ensureBackupMenuAccess(clientId);
            validateRestoredCounts(imported, collectTenantSnapshot(clientId));

            jdbcTemplate.update("""
                    UPDATE tenant_backups
                    SET status = 'RESTORED', updated_at = NOW(), error_message = NULL
                    WHERE id = ?
                    """, upload.id());

            return new RestoreConfirmResponse(
                    preRestoreBackup.backupId(),
                    imported.rowsByTable().size(),
                    imported.manifest().totalRows(),
                    imported.manifest()
            );
        } catch (RuntimeException ex) {
            log.error("Tenant restore failed for client {}", clientId, ex);
            throw ex;
        } catch (IOException ex) {
            throw new BusinessException("Unable to restore backup file: " + ex.getMessage());
        } finally {
            restoreLockService.unlock(clientId);
        }
    }

    /**
     * Called by the scheduler — creates a backup for a specific client without requiring
     * an active SecurityContext. Finds the first admin user for attribution.
     */
    void createScheduledBackup(UUID clientId) {
        try {
            UUID userId = jdbcTemplate.queryForObject("""
                    SELECT u.id FROM users u
                    JOIN user_roles ur ON ur.user_id = u.id
                    JOIN roles r ON r.id = ur.role_id
                    WHERE u.client_id = ? AND r.name IN ('SUPER_ADMIN', 'ADMIN')
                    ORDER BY r.name ASC
                    LIMIT 1
                    """, UUID.class, clientId);
            User user = userRepository.findById(userId).orElse(null);
            if (user == null) {
                log.warn("Scheduled backup skipped for client {} — no admin user found.", clientId);
                return;
            }
            createBackupArchive(clientId, user, "SCHEDULED");
            log.info("Scheduled backup completed for client {}.", clientId);
        } catch (Exception ex) {
            log.error("Scheduled backup failed for client {}: {}", clientId, ex.getMessage(), ex);
        }
    }

    /**
     * Called by the scheduler — enforces retention by deleting old backup files
     * exceeding the configured retention count.
     */
    void enforceRetention(UUID clientId, int retentionCount) {
        try {
            List<Map<String, Object>> excess = jdbcTemplate.queryForList("""
                    SELECT id, storage_path FROM tenant_backups
                    WHERE client_id = ?
                      AND type IN ('MANUAL', 'SCHEDULED')
                      AND status = 'COMPLETED'
                    ORDER BY created_at DESC
                    OFFSET ?
                    """, clientId, retentionCount);
            for (Map<String, Object> row : excess) {
                UUID backupId = (UUID) row.get("id");
                String storagePath = (String) row.get("storage_path");
                try {
                    if (storagePath != null) {
                        Path file = Path.of(storagePath).toAbsolutePath().normalize();
                        if (file.startsWith(storageRoot)) {
                            Files.deleteIfExists(file);
                        }
                    }
                    jdbcTemplate.update("DELETE FROM tenant_backups WHERE id = ?", backupId);
                    log.info("Retention cleanup: deleted backup {} for client {}.", backupId, clientId);
                } catch (Exception ex) {
                    log.warn("Retention cleanup: failed to delete backup {} — {}", backupId, ex.getMessage());
                }
            }
        } catch (Exception ex) {
            log.error("Retention enforcement failed for client {}: {}", clientId, ex.getMessage(), ex);
        }
    }

    private ArchiveWriteResult createBackupArchive(UUID clientId, User user, String type) {
        UUID backupId = UUID.randomUUID();
        String fileName = "cafeqr-backup-" + shortId(clientId) + "-" + Instant.now().toString().replace(":", "").replace(".", "") + "-" + shortId(backupId) + ".cqrbak";
        Path archivePath = storageRoot.resolve(fileName).normalize();
        if (!archivePath.startsWith(storageRoot)) {
            throw new BusinessException("Invalid backup storage path.");
        }

        jdbcTemplate.update("""
                INSERT INTO tenant_backups (id, client_id, requested_by_user_id, type, status, file_name, storage_path, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'RUNNING', ?, ?, NOW(), NOW())
                """, backupId, clientId, user.getId(), type, fileName, archivePath.toString());

        try {
            TenantSnapshot snapshot = collectTenantSnapshot(clientId);
            ArchiveWriteResult written = writeArchive(backupId, archivePath, snapshot, user);
            jdbcTemplate.update("""
                    UPDATE tenant_backups
                    SET status = 'COMPLETED',
                        size_bytes = ?,
                        checksum_sha256 = ?,
                        row_count = ?,
                        manifest_json = ?::jsonb,
                        updated_at = NOW(),
                        expires_at = NULL,
                        error_message = NULL
                    WHERE id = ?
                    """,
                    written.sizeBytes(),
                    written.archiveChecksum(),
                    written.manifest().totalRows(),
                    objectMapper.writeValueAsString(written.manifest()),
                    backupId);
            return written;
        } catch (Exception ex) {
            jdbcTemplate.update("""
                    UPDATE tenant_backups
                    SET status = 'FAILED', error_message = ?, updated_at = NOW()
                    WHERE id = ?
                    """, ex.getMessage(), backupId);
            if (ex instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new BusinessException("Backup failed: " + ex.getMessage());
        }
    }

    private ArchiveWriteResult writeArchive(UUID backupId, Path archivePath, TenantSnapshot snapshot, User user) throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        Map<String, String> checksums = new LinkedHashMap<>();
        List<BackupTableSummary> tableSummaries = new ArrayList<>();

        for (String table : snapshot.tableOrder()) {
            List<JsonNode> rows = new ArrayList<>(snapshot.rowsByTable().getOrDefault(table, new LinkedHashMap<>()).values());
            StringBuilder ndjson = new StringBuilder();
            for (JsonNode row : rows) {
                ndjson.append(objectMapper.writeValueAsString(row)).append('\n');
            }
            String entryName = dataEntryName(table);
            byte[] dataBytes = ndjson.toString().getBytes(StandardCharsets.UTF_8);
            files.put(entryName, dataBytes);
            String checksum = sha256Hex(dataBytes);
            checksums.put(entryName, checksum);
            tableSummaries.add(new BackupTableSummary(table, rows.size(), checksum));
        }

        BackupManifest manifest = new BackupManifest(
                FORMAT_VERSION,
                APP_NAME,
                Instant.now().toString(),
                currentClientId().toString(),
                clientName(currentClientId()),
                user.getEmail(),
                currentSchemaVersion(),
                snapshot.tableOrder(),
                tableSummaries,
                calculateTotals(snapshot),
                snapshot.rowCount(),
                TEST_WARNING
        );

        byte[] manifestBytes = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest);
        files.put("manifest.json", manifestBytes);
        checksums.put("manifest.json", sha256Hex(manifestBytes));

        StringBuilder checksumText = new StringBuilder();
        checksums.forEach((path, hash) -> checksumText.append(hash).append("  ").append(path).append('\n'));
        files.put("checksums.sha256", checksumText.toString().getBytes(StandardCharsets.UTF_8));

        Files.createDirectories(archivePath.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archivePath))) {
            for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                ZipEntry zipEntry = new ZipEntry(entry.getKey());
                zip.putNextEntry(zipEntry);
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }

        return new ArchiveWriteResult(
                backupId,
                archivePath,
                Files.size(archivePath),
                sha256Hex(archivePath),
                manifest
        );
    }

    private ImportedBackup readAndVerifyArchive(Path archivePath) throws IOException {
        if (!Files.exists(archivePath)) {
            throw new ResourceNotFoundException("Restore upload file is no longer available.");
        }
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archivePath))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                if (entry.getName().contains("..") || entry.getName().startsWith("/") || entry.getName().startsWith("\\")) {
                    throw new BusinessException("Backup archive contains an invalid file path.");
                }
                entries.put(entry.getName(), readAllBytes(zip));
            }
        }

        if (!entries.containsKey("manifest.json") || !entries.containsKey("checksums.sha256")) {
            throw new BusinessException("Invalid CafeQR backup: manifest or checksums are missing.");
        }

        verifyEntryChecksums(entries);
        BackupManifest manifest = objectMapper.readValue(entries.get("manifest.json"), BackupManifest.class);
        Map<String, List<JsonNode>> rowsByTable = new LinkedHashMap<>();

        for (String table : manifest.tableOrder()) {
            String entryName = dataEntryName(table);
            byte[] data = entries.get(entryName);
            if (data == null) {
                throw new BusinessException("Invalid CafeQR backup: missing data file for table " + table + ".");
            }
            List<JsonNode> rows = new ArrayList<>();
            String ndjson = new String(data, StandardCharsets.UTF_8);
            for (String line : ndjson.split("\\R")) {
                if (!line.isBlank()) {
                    rows.add(objectMapper.readTree(line));
                }
            }
            rowsByTable.put(table, rows);
        }

        return new ImportedBackup(manifest, rowsByTable);
    }

    private void verifyEntryChecksums(Map<String, byte[]> entries) {
        String text = new String(entries.get("checksums.sha256"), StandardCharsets.UTF_8);
        for (String line : text.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.trim().split("\\s+", 2);
            if (parts.length != 2) {
                throw new BusinessException("Invalid checksum entry in backup.");
            }
            String expected = parts[0].trim();
            String path = parts[1].trim();
            byte[] data = entries.get(path);
            if (data == null) {
                throw new BusinessException("Backup checksum references a missing file: " + path);
            }
            String actual = sha256Hex(data);
            if (!expected.equalsIgnoreCase(actual)) {
                throw new BusinessException("Backup checksum validation failed for " + path + ".");
            }
        }
    }

    private List<String> validateArchiveForCurrentClient(ImportedBackup imported, UUID clientId) {
        BackupManifest manifest = imported.manifest();
        if (!FORMAT_VERSION.equals(manifest.formatVersion())) {
            throw new BusinessException("Unsupported backup format: " + manifest.formatVersion());
        }
        if (!clientId.toString().equalsIgnoreCase(manifest.clientId())) {
            throw new BusinessException("This backup belongs to another restaurant/client.");
        }
        String currentSchema = currentSchemaVersion();
        if (currentSchema != null && manifest.schemaVersion() != null && !currentSchema.equals(manifest.schemaVersion())) {
            throw new BusinessException("Backup schema " + manifest.schemaVersion() + " does not match current schema " + currentSchema + ".");
        }

        Map<String, TableInfo> metadata = loadTableInfo();
        for (String table : manifest.tableOrder()) {
            if (!metadata.containsKey(table)) {
                throw new BusinessException("Backup references a table that does not exist in this app version: " + table);
            }
        }

        List<String> warnings = new ArrayList<>();
        warnings.add(TEST_WARNING);
        warnings.add("Restore will replace current data for this restaurant/client across all branches.");
        return warnings;
    }

    private void validateRestoredCounts(ImportedBackup imported, TenantSnapshot restoredSnapshot) {
        for (BackupTableSummary tableSummary : imported.manifest().tables()) {
            int restored = restoredSnapshot.rowsByTable()
                    .getOrDefault(tableSummary.table(), new LinkedHashMap<>())
                    .size();
            if (restored != tableSummary.rows()) {
                throw new BusinessException("Restore validation failed for " + tableSummary.table() + ": expected " + tableSummary.rows() + " rows but found " + restored + ".");
            }
        }
    }

    private TenantSnapshot collectTenantSnapshot(UUID clientId) {
        Map<String, TableInfo> tables = loadTableInfo();
        Map<String, LinkedHashMap<String, JsonNode>> rowsByTable = new LinkedHashMap<>();

        List<String> tableNames = new ArrayList<>(tables.keySet());
        Collections.sort(tableNames);
        for (String tableName : tableNames) {
            TableInfo table = tables.get(tableName);
            if (isExcluded(tableName)) {
                continue;
            }
            if ("clients".equals(tableName) && table.columns().contains("id")) {
                addRows(rowsByTable, table, queryRowsByColumn(table, "id", List.of(clientId)));
            } else if (table.columns().contains("client_id")) {
                addRows(rowsByTable, table, queryRowsByColumn(table, "client_id", List.of(clientId)));
            }
        }

        boolean changed;
        do {
            changed = false;
            for (String tableName : tableNames) {
                if (isExcluded(tableName)) {
                    continue;
                }
                TableInfo child = tables.get(tableName);
                for (ForeignKey foreignKey : child.foreignKeys()) {
                    LinkedHashMap<String, JsonNode> parentRows = rowsByTable.get(foreignKey.referencedTable());
                    if (parentRows == null || parentRows.isEmpty()) {
                        continue;
                    }
                    List<Object> parentValues = parentRows.values().stream()
                            .map(row -> row.get(foreignKey.referencedColumn()))
                            .filter(Objects::nonNull)
                            .filter(node -> !node.isNull())
                            .map(this::sqlValue)
                            .distinct()
                            .toList();
                    if (parentValues.isEmpty()) {
                        continue;
                    }
                    List<JsonNode> childRows = queryRowsByColumn(child, foreignKey.column(), parentValues);
                    if (addRows(rowsByTable, child, childRows)) {
                        changed = true;
                    }
                }
            }
        } while (changed);

        Set<String> includedTables = new LinkedHashSet<>(rowsByTable.keySet());
        List<String> tableOrder = sortTables(includedTables, tables);
        return new TenantSnapshot(rowsByTable, tableOrder, tables);
    }

    private boolean addRows(Map<String, LinkedHashMap<String, JsonNode>> rowsByTable, TableInfo table, List<JsonNode> rows) {
        if (rows.isEmpty()) {
            return false;
        }
        LinkedHashMap<String, JsonNode> tableRows = rowsByTable.computeIfAbsent(table.name(), ignored -> new LinkedHashMap<>());
        boolean changed = false;
        for (JsonNode row : rows) {
            String key = rowKey(table, row);
            if (!tableRows.containsKey(key)) {
                tableRows.put(key, row);
                changed = true;
            }
        }
        return changed;
    }

    private List<JsonNode> queryRowsByColumn(TableInfo table, String column, List<?> values) {
        if (values.isEmpty()) {
            return List.of();
        }
        List<JsonNode> rows = new ArrayList<>();
        for (int start = 0; start < values.size(); start += QUERY_BATCH_SIZE) {
            List<?> batch = values.subList(start, Math.min(values.size(), start + QUERY_BATCH_SIZE));
            String placeholders = String.join(", ", Collections.nCopies(batch.size(), "?"));
            String sql = "SELECT to_jsonb(t) AS row_json FROM public." + q(table.name()) + " t WHERE " + q(column) + " IN (" + placeholders + ")" + orderBy(table);
            rows.addAll(jdbcTemplate.query(sql, ps -> {
                for (int i = 0; i < batch.size(); i++) {
                    ps.setObject(i + 1, batch.get(i));
                }
            }, (rs, rowNum) -> readJson(rs.getString("row_json"))));
        }
        return rows;
    }

    private void deleteSnapshotRows(TenantSnapshot snapshot) {
        List<String> deleteOrder = new ArrayList<>(snapshot.tableOrder());
        Collections.reverse(deleteOrder);
        for (String tableName : deleteOrder) {
            if ("clients".equals(tableName)) {
                continue;
            }
            TableInfo table = snapshot.tables().get(tableName);
            if (table == null) {
                continue;
            }
            List<JsonNode> rows = new ArrayList<>(snapshot.rowsByTable().getOrDefault(tableName, new LinkedHashMap<>()).values());
            for (JsonNode row : rows) {
                deleteRow(table, row);
            }
        }
    }

    private void deleteRow(TableInfo table, JsonNode row) {
        if (!table.primaryKeyColumns().isEmpty()) {
            StringBuilder where = new StringBuilder();
            List<Object> args = new ArrayList<>();
            for (String pk : table.primaryKeyColumns()) {
                if (!row.has(pk)) {
                    return;
                }
                if (!where.isEmpty()) {
                    where.append(" AND ");
                }
                where.append(q(pk)).append(" = ?");
                args.add(sqlValue(row.get(pk)));
            }
            jdbcTemplate.update("DELETE FROM public." + q(table.name()) + " WHERE " + where, args.toArray());
            return;
        }
        try {
            jdbcTemplate.update("DELETE FROM public." + q(table.name()) + " t WHERE to_jsonb(t) = ?::jsonb", objectMapper.writeValueAsString(row));
        } catch (Exception ex) {
            throw new BusinessException("Unable to delete existing rows for table " + table.name() + ": " + ex.getMessage());
        }
    }

    private void insertImportedRows(ImportedBackup imported, Map<String, TableInfo> metadata) {
        List<String> insertOrder = sortTables(imported.rowsByTable().keySet(), metadata);
        if (insertOrder.remove("clients")) {
            insertOrder.add(0, "clients");
        }
        for (String tableName : insertOrder) {
            TableInfo table = metadata.get(tableName);
            if (table == null) {
                throw new BusinessException("Restore table is not available in this schema: " + tableName);
            }
            for (JsonNode row : imported.rowsByTable().getOrDefault(tableName, List.of())) {
                insertJsonRow(table, row);
            }
        }
    }

    private void insertJsonRow(TableInfo table, JsonNode row) {
        List<String> columns = table.columns().stream().filter(row::has).toList();
        if (columns.isEmpty()) {
            return;
        }
        String columnSql = joinQuoted(columns);
        String sql = "INSERT INTO public." + q(table.name()) + " (" + columnSql + ") " +
                "SELECT " + columnSql + " FROM json_populate_record(NULL::public." + q(table.name()) + ", ?::json)";
        if ("clients".equals(table.name()) && columns.contains("id")) {
            List<String> updateColumns = columns.stream().filter(column -> !"id".equals(column)).toList();
            if (!updateColumns.isEmpty()) {
                sql += " ON CONFLICT (id) DO UPDATE SET " + String.join(", ", updateColumns.stream()
                        .map(column -> q(column) + " = EXCLUDED." + q(column))
                        .toList());
            } else {
                sql += " ON CONFLICT (id) DO NOTHING";
            }
        }
        try {
            jdbcTemplate.update(sql, objectMapper.writeValueAsString(row));
        } catch (Exception ex) {
            throw new BusinessException("Unable to restore table " + table.name() + ": " + ex.getMessage());
        }
    }

    private Map<String, TableInfo> loadTableInfo() {
        List<String> tableNames = jdbcTemplate.queryForList("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_type = 'BASE TABLE'
                ORDER BY table_name
                """, String.class);

        Map<String, List<ForeignKey>> foreignKeysByTable = loadForeignKeys();
        Map<String, TableInfo> tables = new LinkedHashMap<>();
        for (String tableName : tableNames) {
            List<String> columns = jdbcTemplate.queryForList("""
                    SELECT column_name
                    FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = ?
                    ORDER BY ordinal_position
                    """, String.class, tableName);
            List<String> primaryKeys = jdbcTemplate.queryForList("""
                    SELECT kcu.column_name
                    FROM information_schema.table_constraints tc
                    JOIN information_schema.key_column_usage kcu
                      ON tc.constraint_name = kcu.constraint_name
                     AND tc.table_schema = kcu.table_schema
                    WHERE tc.constraint_type = 'PRIMARY KEY'
                      AND tc.table_schema = 'public'
                      AND tc.table_name = ?
                    ORDER BY kcu.ordinal_position
                    """, String.class, tableName);
            tables.put(tableName, new TableInfo(tableName, columns, primaryKeys, foreignKeysByTable.getOrDefault(tableName, List.of())));
        }
        return tables;
    }

    private Map<String, List<ForeignKey>> loadForeignKeys() {
        return jdbcTemplate.query("""
                SELECT
                  kcu.table_name,
                  kcu.column_name,
                  ccu.table_name AS referenced_table,
                  ccu.column_name AS referenced_column
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                  ON tc.constraint_name = kcu.constraint_name
                 AND tc.table_schema = kcu.table_schema
                JOIN information_schema.constraint_column_usage ccu
                  ON ccu.constraint_name = tc.constraint_name
                 AND ccu.constraint_schema = tc.table_schema
                WHERE tc.constraint_type = 'FOREIGN KEY'
                  AND tc.table_schema = 'public'
                ORDER BY kcu.table_name, kcu.ordinal_position
                """, rs -> {
            Map<String, List<ForeignKey>> map = new LinkedHashMap<>();
            while (rs.next()) {
                ForeignKey fk = new ForeignKey(
                        rs.getString("column_name"),
                        rs.getString("referenced_table"),
                        rs.getString("referenced_column")
                );
                map.computeIfAbsent(rs.getString("table_name"), ignored -> new ArrayList<>()).add(fk);
            }
            return map;
        });
    }

    private List<String> sortTables(Set<String> includedTables, Map<String, TableInfo> metadata) {
        Set<String> remaining = new LinkedHashSet<>(includedTables);
        List<String> ordered = new ArrayList<>();
        while (!remaining.isEmpty()) {
            boolean progressed = false;
            for (String tableName : new ArrayList<>(remaining)) {
                TableInfo table = metadata.get(tableName);
                Set<String> dependencies = new HashSet<>();
                if (table != null) {
                    for (ForeignKey foreignKey : table.foreignKeys()) {
                        if (remaining.contains(foreignKey.referencedTable())) {
                            dependencies.add(foreignKey.referencedTable());
                        }
                    }
                }
                if (dependencies.isEmpty()) {
                    ordered.add(tableName);
                    remaining.remove(tableName);
                    progressed = true;
                }
            }
            if (!progressed) {
                List<String> cycle = new ArrayList<>(remaining);
                Collections.sort(cycle);
                ordered.addAll(cycle);
                remaining.clear();
            }
        }
        return ordered;
    }

    private Map<String, Map<String, String>> calculateTotals(TenantSnapshot snapshot) {
        Map<String, Map<String, String>> totals = new LinkedHashMap<>();
        for (String tableName : snapshot.tableOrder()) {
            Map<String, BigDecimal> sums = new LinkedHashMap<>();
            List<JsonNode> rows = new ArrayList<>(snapshot.rowsByTable().getOrDefault(tableName, new LinkedHashMap<>()).values());
            for (JsonNode row : rows) {
                row.fieldNames().forEachRemaining(field -> {
                    if (!TOTAL_COLUMNS.contains(field)) {
                        return;
                    }
                    BigDecimal value = numericValue(row.get(field));
                    if (value != null) {
                        sums.merge(field, value, BigDecimal::add);
                    }
                });
            }
            if (!sums.isEmpty()) {
                Map<String, String> formatted = new LinkedHashMap<>();
                sums.forEach((column, total) -> formatted.put(column, total.stripTrailingZeros().toPlainString()));
                totals.put(tableName, formatted);
            }
        }
        return totals;
    }

    private BigDecimal numericValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            if (node.isNumber()) {
                return node.decimalValue();
            }
            if (node.isTextual() && !node.asText().isBlank()) {
                return new BigDecimal(node.asText());
            }
        } catch (NumberFormatException ignored) {
            return null;
        }
        return null;
    }

    private RestoreUpload findRestoreUpload(UUID clientId, String token) {
        try {
            return jdbcTemplate.queryForObject("""
                    SELECT id, storage_path
                    FROM tenant_backups
                    WHERE client_id = ?
                      AND restore_token = ?
                      AND type = 'RESTORE_UPLOAD'
                      AND status = 'PREVIEWED'
                      AND restore_token_expires_at > NOW()
                    """, (rs, rowNum) -> new RestoreUpload(
                    rs.getObject("id", UUID.class),
                    Path.of(rs.getString("storage_path")).toAbsolutePath().normalize()
            ), clientId, token);
        } catch (EmptyResultDataAccessException ex) {
            throw new BusinessException("Restore preview expired or was not found. Upload the backup again.");
        }
    }

    private void ensureBackupMenuAccess(UUID clientId) {
        jdbcTemplate.update("""
                INSERT INTO role_menus (role_id, menu_id)
                SELECT r.id, m.id
                FROM roles r
                CROSS JOIN menus m
                WHERE r.client_id = ?
                  AND r.name IN ('SUPER_ADMIN', 'ADMIN')
                  AND m.url = '/owner/data-backup'
                ON CONFLICT DO NOTHING
                """, clientId);
    }

    private void ensureDefaultSettings(UUID clientId) {
        jdbcTemplate.update("""
                INSERT INTO tenant_backup_settings (id, client_id, schedule_enabled, schedule_frequency, retention_count, created_at, updated_at)
                VALUES (?, ?, FALSE, 'MANUAL', 10, NOW(), NOW())
                ON CONFLICT (client_id) DO NOTHING
                """, UUID.randomUUID(), clientId);
    }

    private BackupSettingsResponse querySettings(UUID clientId) {
        return jdbcTemplate.queryForObject("""
                SELECT schedule_enabled, schedule_frequency, retention_count, updated_at
                FROM tenant_backup_settings
                WHERE client_id = ?
                """, (rs, rowNum) -> new BackupSettingsResponse(
                rs.getBoolean("schedule_enabled"),
                rs.getString("schedule_frequency"),
                rs.getInt("retention_count"),
                toInstant(rs.getTimestamp("updated_at"))
        ), clientId);
    }

    private String normalizeFrequency(String value) {
        String normalized = Optional.ofNullable(value).orElse("MANUAL").trim().toUpperCase(Locale.ROOT);
        if (Set.of("MANUAL", "DAILY", "WEEKLY").contains(normalized)) {
            return normalized;
        }
        return "MANUAL";
    }

    private User currentUser() {
        String email = Optional.ofNullable(SecurityContextHolder.getContext().getAuthentication())
                .map(authentication -> authentication.getName())
                .orElseThrow(() -> new BusinessException("Authenticated user is required."));
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException("Authenticated user was not found."));
    }

    private UUID currentClientId() {
        UUID tenant = TenantContext.getCurrentTenant();
        if (tenant != null) {
            return tenant;
        }
        UUID userClient = currentUser().getClientId();
        if (userClient == null) {
            throw new BusinessException("Restaurant/client context is required for backup.");
        }
        return userClient;
    }

    private String clientName(UUID clientId) {
        try {
            return jdbcTemplate.queryForObject("SELECT COALESCE(name, legal_name, email, id::text) FROM clients WHERE id = ?", String.class, clientId);
        } catch (Exception ignored) {
            return clientId.toString();
        }
    }

    private String currentSchemaVersion() {
        try {
            return jdbcTemplate.queryForObject("""
                    SELECT version
                    FROM flyway_schema_history
                    WHERE success = TRUE
                    ORDER BY installed_rank DESC
                    LIMIT 1
                    """, String.class);
        } catch (Exception ex) {
            return null;
        }
    }

    private boolean isExcluded(String tableName) {
        return EXCLUDED_TABLES.contains(tableName);
    }

    private String rowKey(TableInfo table, JsonNode row) {
        List<String> keyColumns = table.primaryKeyColumns().isEmpty() ? table.columns() : table.primaryKeyColumns();
        List<String> parts = new ArrayList<>();
        for (String column : keyColumns) {
            JsonNode value = row.get(column);
            parts.add(column + "=" + (value == null || value.isNull() ? "<null>" : value.toString()));
        }
        return String.join("|", parts);
    }

    private Object sqlValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            String text = node.asText();
            if (UUID_PATTERN.matcher(text).matches()) {
                return UUID.fromString(text);
            }
            return text;
        }
        if (node.isIntegralNumber()) {
            return node.longValue();
        }
        if (node.isFloatingPointNumber() || node.isBigDecimal()) {
            return node.decimalValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        return node.toString();
    }

    private JsonNode readJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (IOException ex) {
            throw new BusinessException("Unable to read database row as JSON: " + ex.getMessage());
        }
    }

    private String orderBy(TableInfo table) {
        if (table.primaryKeyColumns().isEmpty()) {
            return "";
        }
        return " ORDER BY " + joinQuoted(table.primaryKeyColumns());
    }

    private String joinQuoted(List<String> columns) {
        return String.join(", ", columns.stream().map(this::q).toList());
    }

    private String q(String identifier) {
        if (identifier == null || !identifier.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid SQL identifier: " + identifier);
        }
        return "\"" + identifier + "\"";
    }

    private String dataEntryName(String table) {
        return "data/" + table + ".ndjson";
    }

    private String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    private Instant toInstant(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private byte[] readAllBytes(ZipInputStream zip) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = zip.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private String sha256Hex(Path path) throws IOException {
        return sha256Hex(Files.readAllBytes(path));
    }

    private String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is not available.", ex);
        }
    }

    private record TableInfo(String name, List<String> columns, List<String> primaryKeyColumns, List<ForeignKey> foreignKeys) {
    }

    private record ForeignKey(String column, String referencedTable, String referencedColumn) {
    }

    private record TenantSnapshot(
            Map<String, LinkedHashMap<String, JsonNode>> rowsByTable,
            List<String> tableOrder,
            Map<String, TableInfo> tables
    ) {
        long rowCount() {
            return rowsByTable.values().stream().mapToLong(Map::size).sum();
        }
    }

    private record ArchiveWriteResult(UUID backupId, Path path, long sizeBytes, String archiveChecksum, BackupManifest manifest) {
    }

    private record ImportedBackup(BackupManifest manifest, Map<String, List<JsonNode>> rowsByTable) {
    }

    private record RestoreUpload(UUID id, Path path) {
    }
}
