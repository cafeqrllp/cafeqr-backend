package com.restaurant.pos.review.query;

import com.restaurant.pos.review.domain.Review;
import com.restaurant.pos.review.repository.ReviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewQueryService {

    private final ReviewRepository reviewRepository;

    @Transactional(readOnly = true)
    public List<Review> getApprovedReviews(UUID clientId, UUID orgId) {
        if (clientId == null) {
            return Collections.emptyList();
        }
        return reviewRepository.findApprovedByClientIdAndOrgId(clientId, orgId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getReviewSummary(UUID clientId, UUID orgId) {
        List<Review> list = getApprovedReviews(clientId, orgId);
        double avg = list.stream().mapToInt(Review::getRating).average().orElse(4.8);
        Map<String, Object> summary = new HashMap<>();
        summary.put("averageRating", Math.round(avg * 10.0) / 10.0);
        summary.put("totalReviews", list.size());
        summary.put("reviews", list);
        return summary;
    }
}
