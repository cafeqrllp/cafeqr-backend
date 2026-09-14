package com.restaurant.pos.hr.repository;

import com.restaurant.pos.hr.entity.Employee;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmployeeRepository extends JpaRepository<Employee, UUID> {
    
    @Query("SELECT e FROM Employee e WHERE e.clientId = :clientId AND (:orgId IS NULL OR e.orgId = :orgId OR e.orgId IS NULL)")
    List<Employee> findByClientIdAndOrgId(@Param("clientId") UUID clientId, @Param("orgId") UUID orgId);

    @Query("SELECT e FROM Employee e WHERE e.id = :id AND e.clientId = :clientId AND (:orgId IS NULL OR e.orgId = :orgId OR e.orgId IS NULL)")
    Optional<Employee> findByIdAndClientIdAndOrgId(@Param("id") UUID id, @Param("clientId") UUID clientId, @Param("orgId") UUID orgId);

    @Query("SELECT e FROM Employee e WHERE e.userId = :userId AND e.clientId = :clientId AND (:orgId IS NULL OR e.orgId = :orgId OR e.orgId IS NULL)")
    Optional<Employee> findByUserIdAndClientIdAndOrgId(@Param("userId") UUID userId, @Param("clientId") UUID clientId, @Param("orgId") UUID orgId);

    @Query("SELECT COUNT(e) > 0 FROM Employee e WHERE e.clientId = :clientId AND LOWER(e.email) = LOWER(:email) AND (:id IS NULL OR e.id != :id)")
    boolean existsByEmailAndClientId(@Param("email") String email, @Param("clientId") UUID clientId, @Param("id") UUID id);

    @Query("SELECT COUNT(e) > 0 FROM Employee e WHERE e.clientId = :clientId AND (:orgId IS NULL OR e.orgId = :orgId) AND e.pinCode = :pinCode AND (:id IS NULL OR e.id != :id)")
    boolean existsByPinCodeAndClientIdAndOrgId(@Param("pinCode") String pinCode, @Param("clientId") UUID clientId, @Param("orgId") UUID orgId, @Param("id") UUID id);
}
