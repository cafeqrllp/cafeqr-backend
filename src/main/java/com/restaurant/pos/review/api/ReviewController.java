package com.restaurant.pos.review.api;

import com.restaurant.pos.client.domain.Client;
import com.restaurant.pos.client.repository.ClientRepository;
import com.restaurant.pos.client.repository.OrganizationRepository;
import com.restaurant.pos.common.dto.ApiResponse;
import com.restaurant.pos.common.exception.ResourceNotFoundException;
import com.restaurant.pos.review.command.ReviewCommandService;
import com.restaurant.pos.review.domain.Review;
import com.restaurant.pos.review.dto.ReviewRequestDto;
import com.restaurant.pos.review.query.ReviewQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping({"/api/v1/reviews", "/api/delivery"})
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewQueryService reviewQueryService;
    private final ReviewCommandService reviewCommandService;
    private final ClientRepository clientRepository;
    private final OrganizationRepository organizationRepository;

    private Client resolveClient(String clientIdOrHandle) {
        if (clientIdOrHandle == null || clientIdOrHandle.isBlank()) {
            throw new ResourceNotFoundException("Client ID or handle is required");
        }
        String trimmed = clientIdOrHandle.trim();
        try {
            UUID id = UUID.fromString(trimmed);
            var clientOpt = clientRepository.findById(id);
            if (clientOpt.isPresent()) return clientOpt.get();

            var orgOpt = organizationRepository.findById(id);
            if (orgOpt.isPresent()) {
                return clientRepository.findById(orgOpt.get().getClientId())
                        .orElseThrow(() -> new ResourceNotFoundException("Client not found for org: " + id));
            }
        } catch (IllegalArgumentException ignored) { }

        var clientOpt = clientRepository.findBySlugIgnoreCase(trimmed);
        if (clientOpt.isPresent()) return clientOpt.get();

        var orgs = organizationRepository.findAllBySlugIgnoreCase(trimmed);
        if (orgs.size() == 1) {
            return clientRepository.findById(orgs.get(0).getClientId())
                    .orElseThrow(() -> new ResourceNotFoundException("Client not found for org slug: " + trimmed));
        }

        var allClients = clientRepository.findAll();
        if (allClients.size() == 1) return allClients.get(0);

        throw new ResourceNotFoundException("Client not found for handle: " + trimmed);
    }

    private UUID parseOrgId(String orgId) {
        if (orgId == null || orgId.isBlank() || "null".equalsIgnoreCase(orgId)) return null;
        try {
            return UUID.fromString(orgId.trim());
        } catch (IllegalArgumentException e) {
            var orgs = organizationRepository.findAllBySlugIgnoreCase(orgId.trim());
            if (!orgs.isEmpty()) return orgs.get(0).getId();
            return null;
        }
    }

    @GetMapping({"/restaurant/{clientId}", "/restaurant/{clientId}/reviews"})
    public ResponseEntity<ApiResponse<Map<String, Object>>> getReviews(
            @PathVariable String clientId,
            @RequestParam(required = false) String orgId) {

        Client client = resolveClient(clientId);
        UUID orgUuid = parseOrgId(orgId);

        Map<String, Object> summary = reviewQueryService.getReviewSummary(client.getId(), orgUuid);
        return ResponseEntity.ok(ApiResponse.success(summary));
    }

    @PostMapping({"/restaurant/{clientId}", "/restaurant/{clientId}/reviews"})
    public ResponseEntity<ApiResponse<Review>> submitReview(
            @PathVariable String clientId,
            @RequestParam(required = false) String orgId,
            @RequestBody ReviewRequestDto request) {

        Client client = resolveClient(clientId);
        UUID orgUuid = parseOrgId(orgId != null ? orgId : (request.getOrgId() != null ? request.getOrgId().toString() : null));

        Review saved = reviewCommandService.submitReview(
                client.getId(),
                orgUuid,
                request.getCustomerName(),
                request.getCustomerEmail(),
                request.getRating(),
                request.getComment()
        );
        return ResponseEntity.ok(ApiResponse.success(saved));
    }
}
