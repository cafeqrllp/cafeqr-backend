package com.restaurant.pos.hr.service;

import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.hr.dto.SalaryComponentDto;
import com.restaurant.pos.hr.entity.SalaryComponent;
import com.restaurant.pos.hr.repository.SalaryComponentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SalaryComponentServiceTest {

    private SalaryComponentRepository salaryComponentRepository;
    private SalaryComponentService service;

    private UUID clientId;
    private UUID orgId;

    @BeforeEach
    void setUp() {
        salaryComponentRepository = mock(SalaryComponentRepository.class);
        service = new SalaryComponentService(salaryComponentRepository);

        clientId = UUID.randomUUID();
        orgId = UUID.randomUUID();

        TenantContext.setCurrentTenant(clientId);
        TenantContext.setCurrentOrg(orgId);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void createComponent_UniqueName_Success() {
        when(salaryComponentRepository.findByNameIgnoreCaseAndClientIdAndOrgIdOrGlobal("Bike Allowance", clientId, orgId))
                .thenReturn(Collections.emptyList());
        when(salaryComponentRepository.save(any(SalaryComponent.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        SalaryComponentDto dto = SalaryComponentDto.builder()
                .name("Bike Allowance")
                .type("EARNING")
                .amountType("FIXED")
                .defaultAmount(new BigDecimal("50.00"))
                .isActive(true)
                .build();

        SalaryComponentDto result = service.createComponent(dto);

        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo("Bike Allowance");
    }

    @Test
    void createComponent_DuplicateName_ThrowsException() {
        SalaryComponent existing = new SalaryComponent();
        existing.setName("Bike Allowance");

        when(salaryComponentRepository.findByNameIgnoreCaseAndClientIdAndOrgIdOrGlobal("Bike Allowance", clientId, orgId))
                .thenReturn(List.of(existing));

        SalaryComponentDto dto = SalaryComponentDto.builder()
                .name("Bike Allowance")
                .type("EARNING")
                .amountType("FIXED")
                .defaultAmount(new BigDecimal("50.00"))
                .isActive(true)
                .build();

        assertThatThrownBy(() -> service.createComponent(dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Rule Name already exists.");
    }

    @Test
    void updateComponent_DuplicateNameOnOtherEntity_ThrowsException() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        SalaryComponent compToEdit = new SalaryComponent();
        compToEdit.setId(id1);
        compToEdit.setName("Original Name");

        SalaryComponent anotherComp = new SalaryComponent();
        anotherComp.setId(id2);
        anotherComp.setName("Bike Allowance");

        when(salaryComponentRepository.findByIdAndClientIdAndOrgIdOrGlobal(id1, clientId, orgId))
                .thenReturn(Optional.of(compToEdit));
        when(salaryComponentRepository.findByNameIgnoreCaseAndClientIdAndOrgIdOrGlobal("Bike Allowance", clientId, orgId))
                .thenReturn(List.of(anotherComp));

        SalaryComponentDto dto = SalaryComponentDto.builder()
                .name("Bike Allowance")
                .type("EARNING")
                .amountType("FIXED")
                .defaultAmount(new BigDecimal("50.00"))
                .isActive(true)
                .build();

        assertThatThrownBy(() -> service.updateComponent(id1, dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Rule Name already exists.");
    }
}
