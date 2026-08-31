package com.restaurant.pos.review.repository;

import com.restaurant.pos.review.domain.Review;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ReviewRepository extends JpaRepository<Review, UUID> {

    @Query("SELECT r FROM Review r WHERE r.clientId = :clientId AND (r.orgId = :orgId OR :orgId IS NULL) AND r.isApproved = 'Y' ORDER BY r.createdAt DESC")
    List<Review> findApprovedByClientIdAndOrgId(UUID clientId, UUID orgId);

    List<Review> findByClientIdOrderByCreatedAtDesc(UUID clientId);
}
