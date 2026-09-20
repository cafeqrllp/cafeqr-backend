package com.restaurant.pos.print.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.restaurant.pos.client.domain.Terminal;
import com.restaurant.pos.client.repository.TerminalRepository;
import com.restaurant.pos.common.exception.BusinessException;
import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.print.domain.PrintConfiguration;
import com.restaurant.pos.print.domain.PrintStation;
import com.restaurant.pos.print.dto.PrintConfigurationRequest;
import com.restaurant.pos.print.dto.PrintStationConfigurationRequest;
import com.restaurant.pos.print.exception.PrintConfigurationConflictException;
import com.restaurant.pos.print.repository.PrintConfigurationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PrintConfigurationService {

    public static final String CLIENT = "CLIENT";
    public static final String ORGANIZATION = "ORGANIZATION";
    public static final String TERMINAL = "TERMINAL";

    private final PrintConfigurationRepository repository;
    private final TerminalRepository terminalRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public Map<String, Object> save(PrintConfigurationRequest request) {
        UUID clientId = requireClient();
        String scopeType = normalizeScope(request.getScopeType());
        UUID scopeId = normalizeScopeId(clientId, scopeType, request.getScopeId());
        UUID orgId = resolveOrgId(clientId, scopeType, scopeId, request.getOrgId());
        Map<String, Object> settings = request.getSettings() == null ? Map.of() : request.getSettings();

        if (TERMINAL.equals(scopeType)) {
            settings = new java.util.LinkedHashMap<>(settings);
            settings.remove("kotTemplate");
            settings.remove("receiptTemplate");
            settings.remove("thermalTemplate");
            settings.remove("regularTemplate");
        }

        settings = sanitizeSettings(settings);
        validateSettings(settings);

        PrintConfiguration entity = findScope(clientId, scopeType, scopeId)
                .orElseGet(PrintConfiguration::new);
        entity.setClientId(clientId);
        entity.setOrgId(orgId);
        entity.setScopeType(scopeType);
        entity.setScopeId(scopeId);
        entity.setRevision((entity.getRevision() == null ? 0 : entity.getRevision()) + 1);
        entity.setSourceStationId(null);
        entity.setSourceLocalRevision(null);
        try {
            entity.setSettingsJson(objectMapper.writeValueAsString(settings));
        } catch (Exception ex) {
            throw new BusinessException("Invalid print configuration");
        }
        repository.save(entity);
        return effective(scopeType.equals(TERMINAL) ? scopeId : null, orgId);
    }

    @Transactional
    public Map<String, Object> syncForStation(
            PrintStation station,
            PrintStationConfigurationRequest request
    ) {
        if (station == null || request == null || request.getSettings() == null) {
            throw new BusinessException("Print station configuration is required");
        }

        long localRevision = request.getLocalRevision() == null ? 0L : request.getLocalRevision();
        Optional<PrintConfiguration> existingLayer = findScope(
                station.getClientId(), TERMINAL, station.getTerminalId());

        if (existingLayer.isPresent()
                && station.getId().equals(existingLayer.get().getSourceStationId())
                && existingLayer.get().getSourceLocalRevision() != null
                && existingLayer.get().getSourceLocalRevision() >= localRevision) {
            return effectiveForStation(
                    station.getClientId(), station.getOrgId(), station.getTerminalId());
        }

        int currentRevision = existingLayer.map(PrintConfiguration::getRevision).orElse(0);
        Integer expectedRevision = request.getCloudRevision();
        // TEMPORARY: Bypass conflict check to allow stuck Windows Services to recover
        // if (expectedRevision != null && expectedRevision > 0 && expectedRevision != currentRevision) {
        //     throw new PrintConfigurationConflictException(
        //             "Printing configuration changed in the cloud. Local printing remains active; review and retry synchronization.");
        // }

        Map<String, Object> settings = new java.util.LinkedHashMap<>(request.getSettings());
        settings.remove("kotTemplate");
        settings.remove("receiptTemplate");
        settings.remove("thermalTemplate");
        settings.remove("regularTemplate");
        settings = sanitizeSettings(settings);
        validateSettings(settings);
        PrintConfiguration entity = existingLayer.orElseGet(PrintConfiguration::new);
        entity.setClientId(station.getClientId());
        entity.setOrgId(station.getOrgId());
        entity.setScopeType(TERMINAL);
        entity.setScopeId(station.getTerminalId());
        entity.setRevision(currentRevision + 1);
        entity.setSourceStationId(station.getId());
        entity.setSourceLocalRevision(localRevision);
        try {
            entity.setSettingsJson(objectMapper.writeValueAsString(settings));
        } catch (Exception ex) {
            throw new BusinessException("Invalid print configuration");
        }
        repository.save(entity);
        return effectiveForStation(
                station.getClientId(), station.getOrgId(), station.getTerminalId());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> effective(UUID terminalId, UUID requestedOrgId) {
        UUID clientId = requireClient();
        UUID orgId = requestedOrgId != null ? requestedOrgId : TenantContext.getCurrentOrg();
        if (terminalId != null) {
            Terminal terminal = terminalRepository.findByIdAndClientId(terminalId, clientId)
                    .orElseThrow(() -> new BusinessException("Terminal not found"));
            orgId = terminal.getOrgId();
        }

        ObjectNode merged = objectMapper.createObjectNode();
        mergeInto(merged, findScope(clientId, CLIENT, null));
        if (orgId != null) {
            mergeInto(merged, findScope(clientId, ORGANIZATION, orgId));
        }
        if (terminalId != null) {
            mergeInto(merged, findScope(clientId, TERMINAL, terminalId));
        }

        Map<String, Object> result = objectMapper.convertValue(merged, new TypeReference<>() {});
        result = sanitizeSettings(result);
        result.put("_meta", metadata(clientId, orgId, terminalId));
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> effectiveForStation(UUID clientId, UUID orgId, UUID terminalId) {
        ObjectNode merged = objectMapper.createObjectNode();
        mergeInto(merged, findScope(clientId, CLIENT, null));
        mergeInto(merged, findScope(clientId, ORGANIZATION, orgId));
        mergeInto(merged, findScope(clientId, TERMINAL, terminalId));
        Map<String, Object> result = objectMapper.convertValue(merged, new TypeReference<>() {});
        result = sanitizeSettings(result);
        result.put("_meta", metadata(clientId, orgId, terminalId));
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sanitizeSettings(Map<String, Object> settings) {
        if (settings == null) {
            return new java.util.LinkedHashMap<>();
        }
        Map<String, Object> result = new java.util.LinkedHashMap<>(settings);

        Set<String> knownProfileIds = new HashSet<>();
        Object profilesObj = result.get("profiles");
        if (profilesObj instanceof java.util.List) {
            java.util.List<?> profilesList = (java.util.List<?>) profilesObj;
            for (Object p : profilesList) {
                if (p instanceof Map) {
                    Object idObj = ((Map<?, ?>) p).get("id");
                    if (idObj != null) {
                        knownProfileIds.add(idObj.toString().trim());
                    }
                }
            }
        }

        Object defaultsObj = result.get("defaults");
        if (defaultsObj instanceof Map) {
            Map<String, Object> defaultsMap = new java.util.LinkedHashMap<>((Map<String, Object>) defaultsObj);
            String[] arrayKeys = {"kotProfileIds", "billProfileIds", "invoiceProfileIds", "labelProfileIds", "masterKotProfileIds"};
            for (String key : arrayKeys) {
                Object listObj = defaultsMap.get(key);
                if (listObj instanceof java.util.List) {
                    java.util.List<?> list = (java.util.List<?>) listObj;
                    java.util.List<String> sanitized = list.stream()
                            .filter(java.util.Objects::nonNull)
                            .map(Object::toString)
                            .map(String::trim)
                            .filter(id -> !id.isEmpty() && knownProfileIds.contains(id))
                            .collect(java.util.stream.Collectors.toList());
                    defaultsMap.put(key, sanitized);
                }
            }
            result.put("defaults", defaultsMap);
        }

        Object routesObj = result.get("routes");
        if (routesObj instanceof java.util.List) {
            java.util.List<?> routesList = (java.util.List<?>) routesObj;
            java.util.List<Map<String, Object>> sanitizedRoutes = new java.util.ArrayList<>();
            for (Object r : routesList) {
                if (r instanceof Map) {
                    Map<String, Object> routeMap = new java.util.LinkedHashMap<>((Map<String, Object>) r);
                    Object routeProfileIdsObj = routeMap.get("profileIds");
                    if (routeProfileIdsObj instanceof java.util.List) {
                        java.util.List<?> list = (java.util.List<?>) routeProfileIdsObj;
                        java.util.List<String> sanitized = list.stream()
                                .filter(java.util.Objects::nonNull)
                                .map(Object::toString)
                                .map(String::trim)
                                .filter(id -> !id.isEmpty() && knownProfileIds.contains(id))
                                .collect(java.util.stream.Collectors.toList());
                        routeMap.put("profileIds", sanitized);
                        if (sanitized.isEmpty()) {
                            routeMap.put("enabled", false);
                        } else if (routeMap.get("enabled") == null || Boolean.FALSE.equals(routeMap.get("enabled"))) {
                            routeMap.put("enabled", true);
                        }
                    }
                    sanitizedRoutes.add(routeMap);
                }
            }
            result.put("routes", sanitizedRoutes);
        }

        return result;
    }

    private Map<String, Object> metadata(UUID clientId, UUID orgId, UUID terminalId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("clientId", clientId);
        metadata.put("orgId", orgId == null ? "" : orgId);
        metadata.put("terminalId", terminalId == null ? "" : terminalId);
        metadata.put("scopeOrder", new String[]{CLIENT, ORGANIZATION, TERMINAL});
        metadata.put("clientRevision", revisionOf(findScope(clientId, CLIENT, null)));
        metadata.put("organizationRevision",
                orgId == null ? 0 : revisionOf(findScope(clientId, ORGANIZATION, orgId)));
        metadata.put("terminalRevision",
                terminalId == null ? 0 : revisionOf(findScope(clientId, TERMINAL, terminalId)));
        return metadata;
    }

    private int revisionOf(Optional<PrintConfiguration> layer) {
        return layer.map(PrintConfiguration::getRevision).orElse(0);
    }

    private void mergeInto(ObjectNode target, Optional<PrintConfiguration> layer) {
        if (layer.isEmpty() || layer.get().getSettingsJson() == null) {
            return;
        }
        try {
            JsonNode node = objectMapper.readTree(layer.get().getSettingsJson());
            if (node != null && node.isObject()) {
                ObjectNode update = (ObjectNode) node;
                if (TERMINAL.equals(layer.get().getScopeType())) {
                    update.remove("kotTemplate");
                    update.remove("receiptTemplate");
                    update.remove("thermalTemplate");
                    update.remove("regularTemplate");
                }
                deepMerge(target, update);
            }
        } catch (Exception ignored) {
            // A malformed historical layer must not prevent lower layers from loading.
        }
    }

    private void deepMerge(ObjectNode target, ObjectNode update) {
        update.fields().forEachRemaining(entry -> {
            JsonNode current = target.get(entry.getKey());
            JsonNode incoming = entry.getValue();
            if (current != null && current.isObject() && incoming.isObject()) {
                deepMerge((ObjectNode) current, (ObjectNode) incoming);
            } else {
                target.set(entry.getKey(), incoming.deepCopy());
            }
        });
    }

    private void validateSettings(Map<String, Object> settings) {
        JsonNode root = objectMapper.valueToTree(settings);
        JsonNode profiles = root.path("profiles");
        Set<String> profileIds = new HashSet<>();
        Map<String, JsonNode> profilesById = new LinkedHashMap<>();
        if (profiles.isArray()) {
            for (JsonNode profile : profiles) {
                String id = profile.path("id").asText("").trim();
                if (id.isEmpty() || !profileIds.add(id)) {
                    throw new BusinessException("Every printer profile must have a unique ID");
                }
                profilesById.put(id, profile);
                String connection = profile.path("connectionType").asText("WINDOWS_QUEUE").toUpperCase();
                if ("WINDOWS_QUEUE".equals(connection)
                        && profile.path("windowsPrinterName").asText("").isBlank()) {
                    throw new BusinessException("Windows queue printer profiles require a printer name");
                }
                if (Set.of("NETWORK", "LAN", "WIFI", "TCP").contains(connection)
                        && profile.path("host").asText("").isBlank()) {
                    throw new BusinessException("Network printer profiles require a host or IP address");
                }
                if (Set.of("BLUETOOTH_COM", "COM").contains(connection)
                        && profile.path("comPort").asText("").isBlank()) {
                    throw new BusinessException("Bluetooth printer profiles require a COM port");
                }
            }
        }

        validateDefaultAssignments(root.path("defaults"), profilesById);

        Set<String> routeKeys = new HashSet<>();
        JsonNode routes = root.path("routes");
        if (!routes.isArray()) {
            return;
        }
        for (JsonNode route : routes) {
            if (!route.path("enabled").asBoolean(true)) {
                continue;
            }
            String mode = route.path("mode").asText("FAILOVER").toUpperCase();
            if (!Set.of("MIRROR", "FAILOVER").contains(mode)) {
                throw new BusinessException("Print routes must use MIRROR or FAILOVER mode");
            }
            if (!route.path("profileIds").isArray() || route.path("profileIds").isEmpty()) {
                throw new BusinessException("Every enabled print route must target at least one printer profile");
            }
            for (JsonNode profileId : route.path("profileIds")) {
                if (!profileIds.contains(profileId.asText())) {
                    throw new BusinessException("Print route references an unknown printer profile");
                }
            }
            String key = route.path("priority").asText("100")
                    + "|" + normalizedArray(route.path("documentTypes"))
                    + "|" + normalizedArray(route.path("categories"))
                    + "|" + normalizedArray(route.path("orderTypes"));
            if (!routeKeys.add(key)) {
                throw new BusinessException("Conflicting print routes use the same conditions and priority");
            }
        }
    }

    private void validateDefaultAssignments(JsonNode defaults, Map<String, JsonNode> profilesById) {
        if (!defaults.isObject()) {
            return;
        }

        validateOutput(defaults, "kotOutput", "KOT");
        validateOutput(defaults, "billOutput", "Bill");
        validateOutput(defaults, "invoiceOutput", "Invoice");
        validateMode(defaults, "kotMode", "KOT");
        validateMode(defaults, "billMode", "Bill");
        validateMode(defaults, "invoiceMode", "Invoice");

        validateAssignedProfiles(defaults.path("kotProfileIds"), "KOT", profilesById);
        validateAssignedProfiles(defaults.path("billProfileIds"), "BILL", profilesById);
        validateAssignedProfiles(defaults.path("invoiceProfileIds"), "INVOICE", profilesById);
        validateAssignedProfiles(defaults.path("masterKotProfileIds"), "KOT", profilesById);
    }

    private void validateOutput(JsonNode defaults, String key, String label) {
        if (!defaults.has(key)) {
            return;
        }
        String output = defaults.path(key).asText("").trim().toUpperCase();
        if (!Set.of("THERMAL", "REGULAR", "BOTH").contains(output)) {
            throw new BusinessException(label + " output must be THERMAL, REGULAR, or BOTH");
        }
    }

    private void validateMode(JsonNode defaults, String key, String label) {
        if (!defaults.has(key)) {
            return;
        }
        String mode = defaults.path(key).asText("").trim().toUpperCase();
        if (!Set.of("MIRROR", "FAILOVER").contains(mode)) {
            throw new BusinessException(label + " default delivery mode must be MIRROR or FAILOVER");
        }
    }

    private void validateAssignedProfiles(
            JsonNode assignedProfileIds,
            String documentType,
            Map<String, JsonNode> profilesById
    ) {
        if (assignedProfileIds.isMissingNode() || assignedProfileIds.isNull()) {
            return;
        }
        if (!assignedProfileIds.isArray()) {
            throw new BusinessException(documentType + " default printer assignments must be a list");
        }

        Set<String> seen = new HashSet<>();
        for (JsonNode assignedProfileId : assignedProfileIds) {
            String profileId = assignedProfileId.asText("").trim();
            if (profileId.isEmpty() || !seen.add(profileId)) {
                throw new BusinessException(documentType + " default printer assignments contain an invalid profile");
            }

            JsonNode profile = profilesById.get(profileId);
            if (profile == null) {
                throw new BusinessException(documentType + " default printer references an unknown profile");
            }
            if (!profile.path("enabled").asBoolean(true)) {
                throw new BusinessException(documentType + " default printer profile is disabled");
            }

            JsonNode documents = profile.path("documents");
            if (documents.isArray() && !documents.isEmpty()) {
                boolean compatible = false;
                for (JsonNode document : documents) {
                    if (documentType.equalsIgnoreCase(document.asText(""))) {
                        compatible = true;
                        break;
                    }
                }
                if (!compatible) {
                    throw new BusinessException(
                            profile.path("name").asText(profileId) + " does not support " + documentType);
                }
            }
        }
    }

    private String normalizedArray(JsonNode node) {
        if (!node.isArray()) {
            return "";
        }
        java.util.List<String> values = new java.util.ArrayList<>();
        node.forEach(value -> values.add(value.asText("").trim().toUpperCase()));
        values.sort(String::compareTo);
        return String.join(",", values);
    }

    private Optional<PrintConfiguration> findScope(UUID clientId, String scopeType, UUID scopeId) {
        return scopeId == null
                ? repository.findByClientIdAndScopeTypeAndScopeIdIsNull(clientId, scopeType)
                : repository.findByClientIdAndScopeTypeAndScopeId(clientId, scopeType, scopeId);
    }

    private UUID normalizeScopeId(UUID clientId, String scopeType, UUID scopeId) {
        if (CLIENT.equals(scopeType)) {
            return null;
        }
        if (scopeId == null) {
            throw new BusinessException("Print configuration scope ID is required");
        }
        if (TERMINAL.equals(scopeType)) {
            terminalRepository.findByIdAndClientId(scopeId, clientId)
                    .orElseThrow(() -> new BusinessException("Terminal not found"));
        }
        return scopeId;
    }

    private UUID resolveOrgId(UUID clientId, String scopeType, UUID scopeId, UUID requestedOrgId) {
        if (CLIENT.equals(scopeType)) {
            return null;
        }
        if (ORGANIZATION.equals(scopeType)) {
            return scopeId;
        }
        return terminalRepository.findByIdAndClientId(scopeId, clientId)
                .map(Terminal::getOrgId)
                .orElse(requestedOrgId);
    }

    private String normalizeScope(String value) {
        String scope = value == null ? CLIENT : value.trim().toUpperCase();
        if (!CLIENT.equals(scope) && !ORGANIZATION.equals(scope) && !TERMINAL.equals(scope)) {
            throw new BusinessException("Unsupported print configuration scope");
        }
        return scope;
    }

    private UUID requireClient() {
        UUID clientId = TenantContext.getCurrentTenant();
        if (clientId == null) {
            throw new BusinessException("Print configuration tenant is missing");
        }
        return clientId;
    }
}
