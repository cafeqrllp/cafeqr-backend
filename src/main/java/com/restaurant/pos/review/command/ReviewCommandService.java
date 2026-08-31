package com.restaurant.pos.review.command;

import com.restaurant.pos.common.exception.BusinessException;
import com.restaurant.pos.review.domain.Review;
import com.restaurant.pos.review.repository.ReviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewCommandService {

    private final ReviewRepository reviewRepository;

    @Transactional
    public Review submitReview(UUID clientId, UUID orgId, String customerName, String customerEmail, Integer rating, String comment) {
        if (clientId == null) {
            throw new BusinessException("Client ID is required for review submission");
        }
        if (customerName == null || customerName.isBlank()) {
            throw new BusinessException("Customer name is required");
        }
        if (rating == null || rating < 1 || rating > 5) {
            throw new BusinessException("Rating must be between 1 and 5");
        }

        Review review = Review.builder()
                .clientId(clientId)
                .orgId(orgId)
                .customerName(customerName.trim())
                .customerEmail(customerEmail != null ? customerEmail.trim() : null)
                .rating(rating)
                .comment(comment != null ? comment.trim() : "")
                .isApproved("Y")
                .build();

        Review saved = reviewRepository.save(review);
        log.info("[Review] Saved review ID {} for client {} (rating {})", saved.getId(), clientId, rating);
        return saved;
    }
}
