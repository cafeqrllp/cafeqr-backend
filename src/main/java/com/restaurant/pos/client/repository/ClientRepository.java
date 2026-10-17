package com.restaurant.pos.client.repository;

import com.restaurant.pos.client.domain.Client;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ClientRepository extends JpaRepository<Client, UUID> {
    Optional<Client> findByEmail(String email);
    Optional<Client> findBySlugIgnoreCase(String slug);
    boolean existsBySlugIgnoreCase(String slug);
    boolean existsBySlugIgnoreCaseAndIdNot(String slug, UUID id);
    java.util.Optional<Client> findFirstByPhone(String phone);
    java.util.Optional<Client> findFirstByWhatsappNumber(String whatsappNumber);
    @org.springframework.data.jpa.repository.Query("SELECT c FROM Client c WHERE c.phone = :p OR c.whatsappNumber = :p")
    java.util.Optional<Client> findFirstByAnyPhone(@org.springframework.data.repository.query.Param("p") String phone);
}
