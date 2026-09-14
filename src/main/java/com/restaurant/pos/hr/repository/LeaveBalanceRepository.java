package com.restaurant.pos.hr.repository;

import com.restaurant.pos.hr.entity.LeaveBalance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LeaveBalanceRepository extends JpaRepository<LeaveBalance, UUID> {
    List<LeaveBalance> findByEmployeeIdAndYear(UUID employeeId, int year);
}
