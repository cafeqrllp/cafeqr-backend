package com.restaurant.pos.purchasing.service;

import com.restaurant.pos.common.exception.BusinessException;
import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.purchasing.domain.Customer;
import com.restaurant.pos.purchasing.domain.Vendor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.restaurant.pos.purchasing.repository.CurrencyRepository;
import com.restaurant.pos.purchasing.repository.CustomerRepository;
import com.restaurant.pos.purchasing.repository.PricelistRepository;
import com.restaurant.pos.purchasing.repository.VendorRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PurchasingServiceTest {

    private CustomerRepository customerRepository;
    private VendorRepository vendorRepository;
    private PurchasingService purchasingService;
    private UUID clientId;

    @BeforeEach
    void setUp() {
        customerRepository = mock(CustomerRepository.class);
        vendorRepository = mock(VendorRepository.class);
        when(vendorRepository.save(any(Vendor.class))).thenAnswer(inv -> inv.getArgument(0));
        purchasingService = new PurchasingService(
                customerRepository,
                vendorRepository,
                mock(CurrencyRepository.class),
                mock(PricelistRepository.class),
                new com.restaurant.pos.common.service.BranchContextService()
        );
        clientId = UUID.randomUUID();
        TenantContext.setCurrentTenant(clientId);
        TenantContext.setCurrentOrg(UUID.randomUUID());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "owner@example.com",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))
        ));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void saveCustomerRejectsDuplicatePhoneInClientProfile() {
        Customer customer = Customer.builder()
                .name("Rahul")
                .phone("123 456")
                .build();

        when(customerRepository.existsByClientIdAndPhone(clientId, "123456")).thenReturn(true);

        assertThatThrownBy(() -> purchasingService.saveCustomer(customer))
                .isInstanceOf(BusinessException.class)
                .hasMessage("A customer with this phone number already exists");
    }

    private Vendor existingVendor(String isactive) {
        Vendor v = Vendor.builder().name("Acme").isactive(isactive).build();
        v.setId(UUID.randomUUID());
        when(vendorRepository.findByIdAndClientId(v.getId(), clientId)).thenReturn(Optional.of(v));
        when(vendorRepository.findByIdAndClientIdAndOrgIdOrGlobal(any(), any(), any())).thenReturn(Optional.of(v));
        return v;
    }

    @Test
    void updateVendorWithoutStatusKeepsVendorInactive() {
        Vendor v = existingVendor("N");
        Vendor updates = Vendor.builder().name("Acme Ltd").isactive(null).build();

        Vendor saved = purchasingService.updateVendor(v.getId(), updates);

        assertThat(saved.getIsactive()).isEqualTo("N");
        assertThat(saved.getName()).isEqualTo("Acme Ltd");
    }

    @Test
    void updateVendorDeactivatesWithBooleanStyleFlag() {
        Vendor v = existingVendor("Y");
        Vendor updates = Vendor.builder().name("Acme").isactive("false").build();

        assertThat(purchasingService.updateVendor(v.getId(), updates).getIsactive()).isEqualTo("N");
    }

    @Test
    void vendorJsonAcceptsLowercaseIsactiveKey() throws Exception {
        Vendor parsed = new ObjectMapper().readValue("{\"name\":\"Acme\",\"isactive\":\"N\"}", Vendor.class);
        assertThat(parsed.getIsactive()).isEqualTo("N");
    }

    @Test
    void invalidVendorStatusIsRejected() {
        assertThatThrownBy(() -> PurchasingService.normalizeActiveFlag("maybe"))
                .isInstanceOf(BusinessException.class);
    }
}
