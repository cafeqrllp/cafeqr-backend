package com.restaurant.pos.loyalty.mapper;

import com.restaurant.pos.loyalty.domain.*;
import com.restaurant.pos.loyalty.dto.*;
import com.restaurant.pos.order.domain.Order;
import com.restaurant.pos.order.repository.OrderRepository;
import com.restaurant.pos.purchasing.domain.Customer;
import com.restaurant.pos.purchasing.repository.CustomerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LoyaltyDtoMapper {

    private final CustomerRepository customerRepository;
    private final OrderRepository orderRepository;

    public LoyaltyProgramDto toProgramDto(LoyaltyProgram p) {
        if (p == null) return null;

        LoyaltyProgramDto.EarnRuleDto earnDto = p.getEarnRules() == null || p.getEarnRules().isEmpty() ? null
                : p.getEarnRules().stream().findFirst().map(r -> LoyaltyProgramDto.EarnRuleDto.builder()
                        .id(r.getId()).spendAmount(r.getSpendAmount()).earnPoints(r.getEarnPoints()).build()).orElse(null);

        LoyaltyProgramDto.RedemptionRuleDto redeemDto = p.getRedemptionRules() == null || p.getRedemptionRules().isEmpty() ? null
                : p.getRedemptionRules().stream().findFirst().map(r -> LoyaltyProgramDto.RedemptionRuleDto.builder()
                        .id(r.getId()).pointsRequired(r.getPointsRequired()).discountAmount(r.getDiscountAmount())
                        .minPoints(r.getMinPoints()).maxPointsPerOrder(r.getMaxPointsPerOrder())
                        .allowPartial(r.isAllowPartial()).build()).orElse(null);

        return LoyaltyProgramDto.builder()
                .id(p.getId())
                .name(p.getName())
                .description(p.getDescription())
                .isActive(p.isActive())
                .isDefault(p.isDefault())
                .priority(p.getPriority())
                .earnRule(earnDto)
                .redemptionRule(redeemDto)
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .build();
    }

    public CustomerLoyaltyDto toCustomerLoyaltyDto(CustomerLoyalty acc, LoyaltyProgram program) {
        if (acc == null) return null;
        Customer customer = customerRepository.findById(acc.getCustomerId()).orElse(null);

        return CustomerLoyaltyDto.builder()
                .id(acc.getId())
                .customerId(acc.getCustomerId())
                .customerName(customer != null ? customer.getName() : null)
                .customerPhone(customer != null ? customer.getPhone() : null)
                .programId(acc.getProgramId())
                .programName(program != null ? program.getName() : null)
                .currentPoints(acc.getCurrentPoints())
                .lifetimeEarned(acc.getLifetimeEarned())
                .lifetimeRedeemed(acc.getLifetimeRedeemed())
                .createdAt(acc.getCreatedAt())
                .updatedAt(acc.getUpdatedAt())
                .build();
    }

    public LoyaltyTransactionDto toTransactionDto(LoyaltyTransaction t) {
        if (t == null) return null;

        String orderNo = null;
        if (t.getOrderId() != null) {
            Order order = orderRepository.findById(t.getOrderId()).orElse(null);
            if (order != null) {
                orderNo = order.getOrderNo() != null && !order.getOrderNo().isBlank()
                        ? order.getOrderNo()
                        : (order.getInvoiceNo() != null && !order.getInvoiceNo().isBlank()
                                ? order.getInvoiceNo()
                                : "#" + order.getId().toString().substring(0, 8));
            }
        }

        return LoyaltyTransactionDto.builder()
                .id(t.getId())
                .customerId(t.getCustomerId())
                .orderId(t.getOrderId())
                .orderNumber(orderNo)
                .transactionType(t.getTransactionType())
                .points(t.getPoints())
                .balanceAfter(t.getBalanceAfter())
                .referenceTransactionId(t.getReferenceTransactionId())
                .remarks(t.getRemarks())
                .createdAt(t.getCreatedAt())
                .build();
    }
}
