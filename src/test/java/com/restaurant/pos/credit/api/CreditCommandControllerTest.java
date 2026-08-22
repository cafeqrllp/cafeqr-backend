package com.restaurant.pos.credit.api;

import com.restaurant.pos.common.dto.ApiResponse;
import com.restaurant.pos.common.idempotency.IdempotencyGuard;
import com.restaurant.pos.credit.command.CreditCommandService;
import com.restaurant.pos.credit.command.RecordPaymentCommand;
import com.restaurant.pos.credit.dto.CreditBPartnerDto;
import com.restaurant.pos.credit.dto.CreditPaymentRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CreditCommandControllerTest {

    private CreditCommandService commandService;
    private IdempotencyGuard idempotencyGuard;
    private com.restaurant.pos.common.service.BranchContextService branchContext;
    private CreditCommandController controller;

    @BeforeEach
    void setUp() {
        commandService = mock(CreditCommandService.class);
        idempotencyGuard = mock(IdempotencyGuard.class);
        branchContext = mock(com.restaurant.pos.common.service.BranchContextService.class);
        controller = new CreditCommandController(commandService, idempotencyGuard, branchContext);
    }

    @Test
    void suspendCustomerDelegatesToService() {
        UUID customerId = UUID.randomUUID();
        CreditBPartnerDto dto = CreditBPartnerDto.builder().id(customerId).status("SUSPENDED").build();
        when(commandService.suspendCustomer(customerId)).thenReturn(dto);

        ResponseEntity<ApiResponse<CreditBPartnerDto>> response = controller.suspendCustomer(customerId);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getData().getStatus()).isEqualTo("SUSPENDED");
        verify(commandService).suspendCustomer(customerId);
    }

    @Test
    void reactivateCustomerDelegatesToService() {
        UUID customerId = UUID.randomUUID();
        CreditBPartnerDto dto = CreditBPartnerDto.builder().id(customerId).status("ACTIVE").build();
        when(commandService.reactivateCustomer(customerId)).thenReturn(dto);

        ResponseEntity<ApiResponse<CreditBPartnerDto>> response = controller.reactivateCustomer(customerId);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getData().getStatus()).isEqualTo("ACTIVE");
        verify(commandService).reactivateCustomer(customerId);
    }

    @Test
    void recordPaymentPassesThroughIdempotencyGuard() {
        UUID customerId = UUID.randomUUID();
        String key = "test-idempotency-key-123";
        CreditPaymentRequest request = new CreditPaymentRequest();
        request.setAmount(new BigDecimal("100.00"));
        request.setPaymentMethod("CASH");

        CreditBPartnerDto expected = CreditBPartnerDto.builder().id(customerId).balance(BigDecimal.ZERO).build();

        when(idempotencyGuard.execute(eq("credit-payment"), eq(customerId), eq(key), eq(CreditBPartnerDto.class), any()))
                .thenReturn(expected);

        ResponseEntity<ApiResponse<CreditBPartnerDto>> response = controller.recordPayment(customerId, key, request);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getData()).isSameAs(expected);
        verify(idempotencyGuard).execute(eq("credit-payment"), eq(customerId), eq(key), eq(CreditBPartnerDto.class), any());
    }
}
