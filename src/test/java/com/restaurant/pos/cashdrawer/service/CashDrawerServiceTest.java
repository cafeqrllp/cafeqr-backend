package com.restaurant.pos.cashdrawer.service;

import com.restaurant.pos.auth.domain.RoleEntity;
import com.restaurant.pos.auth.domain.User;
import com.restaurant.pos.auth.repository.UserRepository;
import com.restaurant.pos.cashdrawer.domain.CashDrawerEvent;
import com.restaurant.pos.cashdrawer.domain.CashDrawerEventStatus;
import com.restaurant.pos.cashdrawer.domain.CashDrawerTenders;
import com.restaurant.pos.cashdrawer.domain.CashDrawerTrigger;
import com.restaurant.pos.cashdrawer.dto.CashDrawerEventDto;
import com.restaurant.pos.cashdrawer.dto.RecordCashDrawerEventRequest;
import com.restaurant.pos.cashdrawer.repository.CashDrawerEventRepository;
import com.restaurant.pos.client.repository.TerminalRepository;
import com.restaurant.pos.common.context.ContextProvider;
import com.restaurant.pos.common.dto.ConfigurationDto;
import com.restaurant.pos.common.exception.BusinessException;
import com.restaurant.pos.common.service.SystemConfigurationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CashDrawerServiceTest {

    private final UUID clientId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    private CashDrawerEventRepository repository;
    private UserRepository userRepository;
    private SystemConfigurationService configurationService;
    private ContextProvider context;
    private CashDrawerService service;
    private RoleEntity role;

    @BeforeEach
    void setUp() {
        repository = mock(CashDrawerEventRepository.class);
        userRepository = mock(UserRepository.class);
        configurationService = mock(SystemConfigurationService.class);
        context = mock(ContextProvider.class);
        service = new CashDrawerService(repository, userRepository, mock(TerminalRepository.class), configurationService, context);

        role = RoleEntity.builder().name("CASHIER").build();
        User user = User.builder().id(userId).firstName("Asha").lastName("K").email("asha@example.test").roleEntity(role).build();

        when(context.getCurrentTenant()).thenReturn(clientId);
        when(context.getCurrentOrg()).thenReturn(orgId);
        when(context.getCurrentUserId()).thenReturn(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(repository.save(any(CashDrawerEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(configurationService.getConfigurationForClientAndBranch(clientId, orgId))
                .thenReturn(ConfigurationDto.builder().cashDrawerManualReasonRequired(true).build());
    }

    @Test
    void cashierWithDefaultsCanAutoOpenOnSettle() {
        CashDrawerEventDto dto = service.record(request(CashDrawerTrigger.SALE_SETTLE, null));

        ArgumentCaptor<CashDrawerEvent> saved = ArgumentCaptor.forClass(CashDrawerEvent.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getClientId()).isEqualTo(clientId);
        assertThat(saved.getValue().getOrgId()).isEqualTo(orgId);
        assertThat(saved.getValue().getUserName()).isEqualTo("Asha K");
        assertThat(saved.getValue().getRoleName()).isEqualTo("CASHIER");
        assertThat(saved.getValue().getPaymentMethod()).isEqualTo("CASH");
        assertThat(dto.getTriggerType()).isEqualTo(CashDrawerTrigger.SALE_SETTLE);
    }

    @Test
    void autoOpenWithRoleFlagOffIsRecordedAndFlagged() {
        role.setCanAutoOpenCashDrawer(false);
        CashDrawerEventDto dto = service.record(request(CashDrawerTrigger.ORDER_SETTLE, null));
        verify(repository).save(any());
        assertThat(dto.getPolicyViolation()).isEqualTo(CashDrawerService.VIOLATION_NOT_PERMITTED);
    }

    @Test
    void manualOpenWithoutPermissionIsRecordedAndFlagged() {
        CashDrawerEventDto denied = service.record(request(CashDrawerTrigger.MANUAL, "Change"));
        assertThat(denied.getPolicyViolation()).isEqualTo(CashDrawerService.VIOLATION_NOT_PERMITTED);

        role.setCanOpenCashDrawer(true);
        CashDrawerEventDto allowed = service.record(request(CashDrawerTrigger.MANUAL, "Change"));
        assertThat(allowed.getPolicyViolation()).isNull();
        verify(repository, times(2)).save(any());
    }

    @Test
    void manualOpenWithoutRequiredReasonIsRecordedAndFlagged() {
        role.setCanOpenCashDrawer(true);
        CashDrawerEventDto dto = service.record(request(CashDrawerTrigger.MANUAL, "  "));
        verify(repository).save(any());
        assertThat(dto.getPolicyViolation()).isEqualTo(CashDrawerService.VIOLATION_REASON_MISSING);
    }

    @Test
    void adminBypassesRoleFlags() {
        when(context.hasRole("ADMIN")).thenReturn(true);
        CashDrawerEventDto dto = service.record(request(CashDrawerTrigger.TEST, null));
        verify(repository).save(any());
        assertThat(dto.getPolicyViolation()).isNull();
    }

    @Test
    void replayWithSameClientEventIdIsIdempotent() {
        CashDrawerEvent existing = CashDrawerEvent.builder().id(UUID.randomUUID())
                .triggerType(CashDrawerTrigger.SALE_SETTLE).status(CashDrawerEventStatus.SENT).build();
        when(repository.findByClientIdAndClientEventId(clientId, "evt-1")).thenReturn(Optional.of(existing));

        RecordCashDrawerEventRequest req = request(CashDrawerTrigger.SALE_SETTLE, null);
        req.setClientEventId("evt-1");
        CashDrawerEventDto dto = service.record(req);

        assertThat(dto.getId()).isEqualTo(existing.getId());
        verify(repository, never()).save(any());
    }

    @Test
    void historyRequiresViewPermission() {
        String today = LocalDate.now().toString();
        assertThatThrownBy(() -> service.search(today, today, null, null, null, null, null, 0, 50))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void dateAndInstantBoundsAreConvertedToUtc() {
        java.time.ZoneId ist = java.time.ZoneId.of("Asia/Kolkata");
        assertThat(CashDrawerService.parseBound("2026-11-11", ist, false)).isEqualTo(java.time.LocalDateTime.parse("2026-11-10T18:30:00"));
        assertThat(CashDrawerService.parseBound("2026-11-11", ist, true)).isEqualTo(java.time.LocalDateTime.parse("2026-11-11T18:30:00"));
        assertThat(CashDrawerService.parseBound("2026-11-11T09:15:30.500Z", ist, true)).isEqualTo(java.time.LocalDateTime.parse("2026-11-11T09:16:00"));
        assertThat(CashDrawerService.parseBound(" ", ist, false)).isNull();
        assertThatThrownBy(() -> CashDrawerService.parseBound("yesterday", ist, false)).isInstanceOf(BusinessException.class);
    }

    @Test
    void tenderListIsNormalised() {
        assertThat(CashDrawerTenders.normalize(" cash, card ,cash,bitcoin")).isEqualTo("CASH,CARD");
        assertThat(CashDrawerTenders.normalize("")).isEqualTo("CASH");
        assertThat(CashDrawerTenders.normalize("foo")).isEqualTo("CASH");
    }

    private RecordCashDrawerEventRequest request(CashDrawerTrigger trigger, String reason) {
        return RecordCashDrawerEventRequest.builder()
                .triggerType(trigger)
                .status(CashDrawerEventStatus.SENT)
                .reason(reason)
                .paymentMethod("cash")
                .amount(new BigDecimal("150.00"))
                .build();
    }
}
