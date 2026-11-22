package com.restaurant.pos.whatsapp.controller;

import com.restaurant.pos.client.domain.Organization;
import com.restaurant.pos.client.repository.OrganizationRepository;
import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.whatsapp.service.WhatsAppService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WhatsAppControllerSessionTest {

    private final UUID clientA = UUID.randomUUID();
    private final UUID branchA1 = UUID.randomUUID();
    private final UUID branchA2 = UUID.randomUUID();
    private final UUID branchOfOtherClient = UUID.randomUUID();

    private WhatsAppService whatsAppService;
    private WhatsAppController controller;

    @BeforeEach
    void setUp() {
        whatsAppService = mock(WhatsAppService.class);
        OrganizationRepository orgs = mock(OrganizationRepository.class);
        when(orgs.findByIdAndClientId(any(), any())).thenReturn(Optional.empty());
        when(orgs.findByIdAndClientId(branchA1, clientA)).thenReturn(Optional.of(Organization.builder().id(branchA1).build()));
        when(orgs.findByIdAndClientId(branchA2, clientA)).thenReturn(Optional.of(Organization.builder().id(branchA2).build()));
        controller = new WhatsAppController(whatsAppService, orgs);

        TenantContext.setCurrentTenant(clientA);
        TenantContext.setCurrentOrg(branchA1);
        loginAs("ROLE_ADMIN");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    private static void loginAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("u", "p", List.of(new SimpleGrantedAuthority(role))));
    }

    @Test
    void noSessionGiven_usesCurrentBranch() {
        assertEquals("org_" + branchA1, controller.resolveSessionId(null));
        assertEquals("org_" + branchA1, controller.resolveSessionId("  "));
    }

    @Test
    void anotherBranchOfSameCompany_isAllowed() {
        assertEquals("org_" + branchA2, controller.resolveSessionId("org_" + branchA2));
        assertEquals("client_" + clientA, controller.resolveSessionId("client_" + clientA));
    }

    @Test
    void otherCompanysBranch_isRejected() {
        assertThrows(AccessDeniedException.class, () -> controller.resolveSessionId("org_" + branchOfOtherClient));
        assertThrows(AccessDeniedException.class, () -> controller.resolveSessionId("client_" + UUID.randomUUID()));
    }

    @Test
    void sharedAndOfficialSessions_areRejectedForNonSuperAdmin() {
        assertThrows(AccessDeniedException.class, () -> controller.resolveSessionId("cafeqr_official"));
        assertThrows(AccessDeniedException.class, () -> controller.resolveSessionId("cafe_qr_orders"));
        assertThrows(AccessDeniedException.class, () -> controller.resolveSessionId("default"));
        assertThrows(AccessDeniedException.class, () -> controller.resolveSessionId("org_not-a-uuid"));
    }

    @Test
    void superAdmin_mayTargetAnySession() {
        loginAs("ROLE_SUPER_ADMIN");
        assertEquals("org_" + branchOfOtherClient, controller.resolveSessionId("org_" + branchOfOtherClient));
        assertEquals("cafeqr_official", controller.resolveSessionId("cafeqr_official"));
    }

    @Test
    void disconnectOfOtherCompany_neverReachesGateway() {
        assertThrows(AccessDeniedException.class,
                () -> controller.disconnect(Map.of("sessionId", "org_" + branchOfOtherClient)));
        verify(whatsAppService, never()).disconnectGateway(any());
    }

    @Test
    void noTenantAndNotSuperAdmin_isRejected() {
        TenantContext.clear();
        assertThrows(AccessDeniedException.class, () -> controller.resolveSessionId(null));
    }
}
