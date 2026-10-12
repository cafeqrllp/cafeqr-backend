package com.restaurant.pos.auth.service;

import com.restaurant.pos.auth.domain.User;
import com.restaurant.pos.auth.domain.RoleEntity;
import com.restaurant.pos.auth.domain.Permission;
import com.restaurant.pos.auth.dto.AuthRequest;
import com.restaurant.pos.auth.dto.AuthResponse;
import com.restaurant.pos.auth.dto.RegisterRequest;
import com.restaurant.pos.auth.repository.UserRepository;
import com.restaurant.pos.auth.repository.RoleRepository;
import com.restaurant.pos.auth.repository.PermissionRepository;
import com.restaurant.pos.auth.repository.MenuRepository;
import com.restaurant.pos.auth.domain.Menu;
import com.restaurant.pos.client.domain.Client;
import com.restaurant.pos.client.repository.ClientRepository;
import com.restaurant.pos.client.repository.OrganizationRepository;
import com.restaurant.pos.client.repository.TerminalRepository;
import com.restaurant.pos.common.exception.BusinessException;
import com.restaurant.pos.common.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository repository;
    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final ClientRepository clientRepository;
    private final OrganizationRepository organizationRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final AuthenticationManager authenticationManager;
    private final OtpService otpService;
    private final MenuRepository menuRepository;
    private final TerminalRepository terminalRepository;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Transactional
    public AuthResponse register(RegisterRequest request, String ipAddress, String userAgent) {
        log.info("Starting registration for email: {}", request.getEmail());
        if (repository.existsByEmail(request.getEmail())) {
            throw new BusinessException("Email already in use");
        }

        if (!otpService.verifyOtp(request.getEmail(), request.getOtp())) {
            throw new BusinessException("Invalid or expired OTP");
        }

        // 1. Create Client (Tenant)
        Client client = Client.builder()
                .name(null) // Client/Restaurant name to be set from Client Management
                .email(request.getEmail())
                .country(request.getCountry())
                .posType(request.getPosType())
                .subscriptionStatus("UNPAID")
                .subscriptionExpiryDate(LocalDateTime.now())
                .isactive("Y")
                .build();
        client.setCreatedBy("SYSTEM");
        client = clientRepository.save(client);
        UUID clientId = client.getId();
        log.info("Created client with ID: {}", clientId);
        TenantContext.setCurrentTenant(clientId);

        // 2. Provision Default Organization
        com.restaurant.pos.client.domain.Organization defaultOrg = com.restaurant.pos.client.domain.Organization.builder()
                .name("Main Outlet")
                .client(client)
                .clientId(clientId)
                .branchCode("HQ")
                .timezone("Asia/Kolkata") // Standard default timezone
                .subscriptionStatus("UNPAID")
                .subscriptionExpiryDate(LocalDateTime.now())
                .isactive("Y")
                .build();
        defaultOrg.setCreatedBy("SYSTEM");
        defaultOrg = organizationRepository.save(defaultOrg);
        log.info("Created default organization with ID: {}", defaultOrg.getId());
        
        // 3. Seed Roles & Permissions for the Tenant
        seedTenantRoles(clientId, "SYSTEM");

        // 4. Create Super Admin User
        RoleEntity superAdminRole = roleRepository.findByNameAndClientId("SUPER_ADMIN", clientId)
                .orElseThrow(() -> new BusinessException("Role provisioning failed"));

        User user = User.builder()
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .roleEntity(superAdminRole)
                .isactive("Y")
                .isEnabled(true)
                .build();
        user.setCreatedBy("SYSTEM");
        user.setClientId(clientId);
        user.setOrgId(defaultOrg.getId()); // Set default organization branch link
        user = repository.save(user);
        log.info("Created super admin user with ID: {}", user.getId());

        return buildAuthResponse(user, client, ipAddress, userAgent);
    }

    public void seedTenantRoles(UUID clientId, String createdBy) {
        log.info("Seeding default roles for tenant: {}", clientId);
        Set<Permission> allPermissions = new HashSet<>(permissionRepository.findAll());
        List<Menu> allMenus = menuRepository.findAll();
        
        List<String> roleNames = Arrays.asList("SUPER_ADMIN", "ADMIN", "MANAGER", "STAFF");
        
        for (String name : roleNames) {
            if (roleRepository.findByNameAndClientId(name, clientId).isEmpty()) {
                Set<Menu> roleMenus = new HashSet<>();
                if ("SUPER_ADMIN".equals(name)) {
                    roleMenus.addAll(allMenus);
                } else if ("ADMIN".equals(name) || "MANAGER".equals(name)) {
                    allMenus.stream()
                            .filter(m -> Arrays.asList("Sales", "Product Management", "Dashboard").contains(m.getName()))
                            .forEach(roleMenus::add);
                } else if ("STAFF".equals(name)) {
                    allMenus.stream()
                            .filter(m -> "Sales".equals(m.getName()))
                            .forEach(roleMenus::add);
                }

                RoleEntity role = RoleEntity.builder()
                        .name(name)
                        .description("Default " + name + " role")
                        .isactive("Y")
                        .permissions(name.equals("STAFF") ? new HashSet<>() : allPermissions)
                        .menus(roleMenus)
                        .build();
                role.setCreatedBy(createdBy);
                role.setClientId(clientId);
                roleRepository.save(role);
                log.info("Provisioned role {} for tenant {} with {} menus", name, clientId, roleMenus.size());
            }
        }
    }

    @Transactional
    public AuthResponse authenticate(AuthRequest request, String ipAddress, String userAgent) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword())
        );
        
        User user = repository.findByEmail(request.getEmail())
                .orElseThrow(() -> new BusinessException("User not found"));
        
        // 1. Check User Active Status
        if (!"Y".equalsIgnoreCase(user.getIsactive())) {
            throw new BusinessException("Your account is inactive. Please contact support.");
        }

        // 2. Check Organization Status if assigned
        if (user.getOrgId() != null) {
            organizationRepository.findById(java.util.Objects.requireNonNull(user.getOrgId())).ifPresent(org -> {
                if (!org.isActive()) {
                    throw new BusinessException("Your assigned branch/organization is currently inactive. Please contact your administrator.");
                }
            });
        }

        // 3. Check Terminal Status if assigned
        if (user.getTerminalId() != null) {
            terminalRepository.findById(java.util.Objects.requireNonNull(user.getTerminalId())).ifPresent(term -> {
                if (!term.isActive()) {
                    throw new BusinessException("Your assigned terminal is currently inactive. Please contact your administrator.");
                }
            });
        }
        
        Client client = clientRepository.findById(java.util.Objects.requireNonNull(user.getClientId()))
                .orElseThrow(() -> new BusinessException("Client not found"));

        return buildAuthResponse(user, client, ipAddress, userAgent);
    }

    private AuthResponse buildAuthResponse(User user, Client client, String ipAddress, String userAgent) {
        // Self-heal: auto-assign user to the client's default branch if not already set
        UUID resolvedOrgId = user.getOrgId();
        if (resolvedOrgId == null) {
            var orgs = organizationRepository.findAllByClientId(user.getClientId());
            if (!orgs.isEmpty()) {
                resolvedOrgId = orgs.get(0).getId();
                user.setOrgId(resolvedOrgId);
                user = repository.save(user);
                log.info("Auto-assigned user {} to default branch {}", user.getId(), resolvedOrgId);
            }
        }

        Map<String, Object> extraClaims = new HashMap<>();
        extraClaims.put("clientId", user.getClientId());
        extraClaims.put("orgId", resolvedOrgId);
        extraClaims.put("terminalId", user.getTerminalId());
        extraClaims.put("userId", user.getId());
        extraClaims.put("role", user.getRoleEntity().getName());

        String jwtToken = jwtService.generateToken(extraClaims, user);
        String refreshToken = refreshTokenService.createRefreshToken(user, ipAddress, userAgent).getToken();

        String subStatus = client.getSubscriptionStatus();
        LocalDateTime subExpiry = client.getSubscriptionExpiryDate();
        if (resolvedOrgId != null) {
            var orgOpt = organizationRepository.findById(resolvedOrgId);
            if (orgOpt.isPresent()) {
                subStatus = orgOpt.get().getSubscriptionStatus();
                subExpiry = orgOpt.get().getSubscriptionExpiryDate();
            }
        }

        return AuthResponse.builder()
                .accessToken(jwtToken)
                .refreshToken(refreshToken)
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .role(user.getRoleEntity().getName())
                .clientId(user.getClientId())
                .clientName(client.getName())
                .orgId(user.getOrgId())
                .orgName(user.getOrganization() != null ? user.getOrganization().getName() : null)
                .terminalId(user.getTerminalId())
                .terminalName(user.getTerminal() != null ? user.getTerminal().getName() : null)
                .userId(user.getId())
                .currency(client.getCurrency())
                .country(client.getCountry())
                .subscriptionStatus(subStatus)
                .subscriptionExpiryDate(subExpiry)
                .canCancelOrder(user.getRoleEntity() != null ? user.getRoleEntity().getCanCancelOrder() : true)
                .canDeleteOrderItem(user.getRoleEntity() != null ? user.getRoleEntity().getCanDeleteOrderItem() : true)
                .canDecrementOrderItem(user.getRoleEntity() != null ? user.getRoleEntity().getCanDecrementOrderItem() : true)
                .timezone(user.getOrganization() != null && user.getOrganization().getTimezone() != null ? user.getOrganization().getTimezone() : client.getTimezone())
                .termsAcceptedVersion(user.getTermsAcceptedVersion())
                .termsAcceptedAt(user.getTermsAcceptedAt())
                .build();
    }

    @Transactional
    public java.util.Map<String, Object> acceptTerms(java.util.UUID userId, String termsVersion, String ipAddress, String userAgent) {
        if (userId == null) {
            throw new BusinessException("User ID is required");
        }
        User user = repository.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found"));

        String version = (termsVersion != null && !termsVersion.isBlank()) ? termsVersion.trim() : "v1.0";
        LocalDateTime now = LocalDateTime.now();

        user.setTermsAcceptedVersion(version);
        user.setTermsAcceptedAt(now);
        user.setTermsAcceptedIp(ipAddress);
        repository.save(user);

        // Record audit trail
        try {
            jdbcTemplate.update(
                    "INSERT INTO terms_acceptance_audit (user_id, client_id, terms_version, ip_address, user_agent, accepted_at) VALUES (?, ?, ?, ?, ?, ?)",
                    user.getId(),
                    user.getClientId(),
                    version,
                    ipAddress,
                    userAgent,
                    now
            );
        } catch (Exception e) {
            log.warn("Could not insert into terms_acceptance_audit: {}", e.getMessage());
        }

        log.info("User {} ({}) accepted terms version {}", user.getEmail(), user.getId(), version);

        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("status", "SUCCESS");
        result.put("termsAcceptedVersion", version);
        result.put("termsAcceptedAt", now);
        return result;
    }

    @Transactional
    public AuthResponse refreshToken(String token, String ipAddress, String userAgent) {
        var refreshedToken = refreshTokenService.verifyAndRotate(token, ipAddress, userAgent);
        User user = refreshedToken.getUser();
        Client client = clientRepository.findById(java.util.Objects.requireNonNull(user.getClientId()))
                .orElseThrow(() -> new BusinessException("Client not found"));

        return buildAuthResponse(user, client, ipAddress, userAgent);
    }

    @Transactional
    public void logout(String refreshToken) {
        refreshTokenService.revokeToken(refreshToken);
    }

    @Transactional
    public void changePassword(String email, String currentPassword, String newPassword) {
        var user = repository.findByEmail(email)
                .orElseThrow(() -> new BusinessException("User not found"));

        if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
            throw new BusinessException("Current password does not match");
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        repository.save(user);
    }

    @Transactional
    public void resetPassword(String email, String otp, String newPassword) {
        if (!otpService.verifyOtp(email, otp)) {
            throw new BusinessException("Invalid or expired OTP");
        }

        var user = repository.findByEmail(email)
                .orElseThrow(() -> new BusinessException("User not found"));

        user.setPassword(passwordEncoder.encode(newPassword));
        repository.save(user);
    }

    public boolean userExists(String email) {
        return repository.existsByEmail(email);
    }
}
