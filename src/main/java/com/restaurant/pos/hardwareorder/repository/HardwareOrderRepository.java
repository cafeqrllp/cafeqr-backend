package com.restaurant.pos.hardwareorder.repository;

import com.restaurant.pos.hardwareorder.domain.HardwareOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface HardwareOrderRepository extends JpaRepository<HardwareOrder, UUID> {
    Optional<HardwareOrder> findByRazorpayOrderId(String razorpayOrderId);
    Optional<HardwareOrder> findByRazorpayPaymentId(String razorpayPaymentId);
}
