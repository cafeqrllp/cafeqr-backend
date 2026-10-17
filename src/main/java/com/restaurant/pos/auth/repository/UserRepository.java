package com.restaurant.pos.auth.repository;

import com.restaurant.pos.auth.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
    Optional<User> findFirstByPhone(String phone);
    boolean existsByPhone(String phone);
    List<User> findAllByClientId(UUID clientId);
}
