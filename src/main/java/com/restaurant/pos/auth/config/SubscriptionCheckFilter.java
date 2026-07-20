package com.restaurant.pos.auth.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.restaurant.pos.auth.domain.User;
import com.restaurant.pos.client.domain.Client;
import com.restaurant.pos.client.domain.Organization;
import com.restaurant.pos.client.repository.ClientRepository;
import com.restaurant.pos.client.repository.OrganizationRepository;
import com.restaurant.pos.common.dto.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class SubscriptionCheckFilter extends OncePerRequestFilter {

    private final ClientRepository clientRepository;
    private final OrganizationRepository organizationRepository;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        String path = request.getServletPath();

        // Exempt public, auth, debug, founder, and subscription endpoints from the lockout check
        if (path.contains("/api/v1/auth/") || 
            path.contains("/api/v1/debug/") || 
            path.contains("/api/v1/public/") || 
            path.contains("/api/v1/founder/") || 
            path.contains("/api/v1/subscription/")) {
            filterChain.doFilter(request, response);
            return;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated() && authentication.getPrincipal() instanceof User user) {
            UUID clientId = user.getClientId();
            UUID orgId = user.getOrgId();
            if (orgId != null) {
                Organization organization = organizationRepository.findById(orgId).orElse(null);
                if (organization == null || !organization.isSubscriptionActive()) {
                    sendErrorResponse(request, response, "Subscription for this branch has expired. Access is restricted. Please renew your subscription to continue.", HttpServletResponse.SC_FORBIDDEN);
                    return;
                }
            } else if (clientId != null) {
                Client client = clientRepository.findById(clientId).orElse(null);
                boolean clientActive = client != null && client.isSubscriptionActive();
                boolean anyBranchActive = organizationRepository.findAllByClientId(clientId).stream()
                        .anyMatch(Organization::isSubscriptionActive);
                
                if (!clientActive && !anyBranchActive) {
                    sendErrorResponse(request, response, "Subscription has expired. Access is restricted. Please renew your subscription to continue.", HttpServletResponse.SC_FORBIDDEN);
                    return;
                }
            }
        }

        filterChain.doFilter(request, response);
    }

    private void sendErrorResponse(HttpServletRequest request, HttpServletResponse response, String message, int status) throws IOException {
        String origin = request.getHeader("Origin");
        if (origin != null && !origin.isBlank()) {
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.setHeader("Access-Control-Allow-Credentials", "true");
            response.setHeader("Vary", "Origin");
        }
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        
        ApiResponse<Object> apiResponse = ApiResponse.error(message);
        response.getWriter().write(objectMapper.writeValueAsString(apiResponse));
    }
}
