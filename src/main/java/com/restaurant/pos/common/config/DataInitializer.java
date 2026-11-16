package com.restaurant.pos.common.config;

import com.restaurant.pos.auth.domain.Permission;
import com.restaurant.pos.auth.domain.RoleEntity;
import com.restaurant.pos.auth.domain.User;
import com.restaurant.pos.auth.domain.Menu;
import com.restaurant.pos.auth.repository.MenuRepository;
import com.restaurant.pos.auth.repository.PermissionRepository;
import com.restaurant.pos.auth.repository.RoleRepository;
import com.restaurant.pos.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements CommandLineRunner {

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final UserRepository userRepository;
    private final MenuRepository menuRepository;
    private final com.restaurant.pos.client.repository.OrganizationRepository organizationRepository;
    private final com.restaurant.pos.client.repository.ClientRepository clientRepository;
    private final com.restaurant.pos.paymenttype.repository.PaymentTypeRepository paymentTypeRepository;

    @Override
    @Transactional
    public void run(String... args) {
        log.info("Starting Data Initialization...");

        // 1. Seed Permissions
        List<String> permissionNames = Arrays.asList(
                "CREATE_ORDER", "VOID_BILL", "VIEW_REPORT",
                "MANAGE_USERS", "MANAGE_ORG", "MANAGE_TERMINAL");

        for (String pName : permissionNames) {
            if (permissionRepository.findByName(pName).isEmpty()) {
                permissionRepository.save(java.util.Objects.requireNonNull(Permission.builder()
                        .name(pName)
                        .description("Default permission: " + pName)
                        .build()));
            }
        }

        Set<Permission> allPermissions = new HashSet<>(permissionRepository.findAll());
        List<Menu> allMenus = menuRepository.findAll();

        // 2. Seed Default Roles (Global/Template roles)
        seedRole("SUPER_ADMIN", "Full System Access", allPermissions, allMenus);
        seedRole("ADMIN", "Business Administrator", allPermissions, allMenus);
        seedRole("MANAGER", "Branch Manager", allPermissions, allMenus);
        seedRole("STAFF", "Standard Staff Account", new HashSet<>(), allMenus);

        // 3. Repair Admin User Role if needed (Fix for "Access Denied")
        repairAdminRoles();

        // 4. Ensure Default Organization exists for all Clients
        seedDefaultOrganizations();

        // 5. Ensure Default Payment Types exist for all Organizations
        seedDefaultPaymentTypesForExistingOrgs();

        log.info("Data Initialization complete.");
    }

    private void seedRole(String name, String desc, Set<Permission> permissions, List<Menu> allMenus) {
        RoleEntity role = roleRepository.findByNameAndClientIdIsNull(name).orElseGet(() -> {
            // Cash drawer defaults match migration V1_202: admin/manager roles may open manually and view history.
            boolean drawerSupervisor = !"STAFF".equals(name);
            RoleEntity r = RoleEntity.builder()
                    .name(name)
                    .description(desc)
                    .isactive("Y")
                    .canOpenCashDrawer(drawerSupervisor)
                    .canViewCashDrawerHistory(drawerSupervisor)
                    .build();
            r.setCreatedBy("SYSTEM");
            return r;
        });
        role.setPermissions(permissions);

        Set<Menu> roleMenus = new java.util.HashSet<>();
        if ("SUPER_ADMIN".equals(name)) {
            roleMenus.addAll(allMenus);
        } else if ("ADMIN".equals(name) || "MANAGER".equals(name)) {
            allMenus.stream()
                    .filter(m -> Arrays.asList("Sales", "Product Management", "Dashboard", "Cash Drawer History").contains(m.getName()))
                    .forEach(roleMenus::add);
        } else if ("STAFF".equals(name)) {
            allMenus.stream()
                    .filter(m -> "Sales".equals(m.getName()))
                    .forEach(roleMenus::add);
        }
        role.setMenus(roleMenus);

        roleRepository.save(role);
        log.info("Seeded global role: {} with {} menus", name, roleMenus.size());
    }

    private void repairAdminRoles() {
        // Find users that might have lost their role link or have the wrong role name
        List<User> users = userRepository.findAll();
        RoleEntity adminRole = roleRepository.findByNameAndClientIdIsNull("ADMIN").orElse(null);

        if (adminRole == null)
            return;

        for (User user : users) {
            if (user.getRoleEntity() == null) {
                log.warn("User {} has no role. Assigning ADMIN role by default for emergency recovery.",
                        user.getEmail());
                user.setRoleEntity(adminRole);
                userRepository.save(user);
            }
        }
    }

    private void seedDefaultOrganizations() {
        List<com.restaurant.pos.client.domain.Client> clients = clientRepository.findAll();
        for (com.restaurant.pos.client.domain.Client client : clients) {
            UUID clientId = client.getId();
            var orgs = organizationRepository.findAllByClientId(clientId);
            if (orgs.isEmpty()) {
                log.info("Auto-creating default organization 'thalassery' for client {}", client.getEmail());
                com.restaurant.pos.client.domain.Organization defaultOrg = com.restaurant.pos.client.domain.Organization
                        .builder()
                        .name("thalassery")
                        .client(client)
                        .isactive("Y")
                        .build();
                defaultOrg.setCreatedBy("SYSTEM");
                defaultOrg.setClientId(clientId);
                organizationRepository.save(defaultOrg);
            }
        }
    }

    private void seedDefaultPaymentTypesForExistingOrgs() {
        List<com.restaurant.pos.client.domain.Organization> orgs = organizationRepository.findAll();
        for (com.restaurant.pos.client.domain.Organization org : orgs) {
            UUID clientId = org.getClientId();
            UUID orgId = org.getId();
            if (clientId == null)
                continue;

            record Seed(String display, String paymentType, String sales, String purchase, String expense, int sort,
                    boolean isDefault) {
            }

            List<Seed> defaults = List.of(
                    new Seed("Cash", "OTHERS", "Y", "Y", "Y", 1, true),
                    new Seed("Online", "OTHERS", "Y", "Y", "Y", 2, false),
                    new Seed("Mixed", "OTHERS", "Y", "Y", "Y", 3, false),
                    new Seed("Credit", "CREDIT", "Y", "N", "N", 4, false));

            for (Seed s : defaults) {
                boolean exists = paymentTypeRepository.existsByClientIdAndOrgIdAndDisplayName(clientId, orgId,
                        s.display());
                if (!exists) {
                    com.restaurant.pos.paymenttype.domain.PaymentType pt = com.restaurant.pos.paymenttype.domain.PaymentType
                            .builder()
                            .displayName(s.display())
                            .paymentType(s.paymentType())
                            .sales(s.sales())
                            .purchase(s.purchase())
                            .expense(s.expense())
                            .sortOrder(s.sort())
                            .isDefault(s.isDefault())
                            .isactive("Y")
                            .build();
                    pt.setClientId(clientId);
                    pt.setOrgId(orgId);
                    pt.setCreatedBy("SYSTEM");
                    paymentTypeRepository.save(pt);
                }
            }
        }
    }
}
