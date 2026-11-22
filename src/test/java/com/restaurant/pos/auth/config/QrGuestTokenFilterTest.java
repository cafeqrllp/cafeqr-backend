package com.restaurant.pos.auth.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.restaurant.pos.auth.service.JwtService;
import com.restaurant.pos.qrmenu.guest.QrGuestTokenService;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** QR table-guest tokens (scope=qr:guest) are for cafe-qr-ai only and must never sign anyone in here. */
class QrGuestTokenFilterTest {

    private final UserDetails owner = User.withUsername("owner@cafe.test").password("x").roles("ADMIN").build();
    private final AtomicInteger userLookups = new AtomicInteger();
    private JwtService jwtService;
    private JwtAuthenticationFilter filter;
    private QrGuestTokenService guestTokens;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "privateKeyStr", "");
        ReflectionTestUtils.setField(jwtService, "publicKeyStr", "");
        ReflectionTestUtils.setField(jwtService, "jwtExpiration", 60_000L);
        ReflectionTestUtils.setField(jwtService, "refreshExpiration", 60_000L);
        jwtService.init();
        UserDetailsService users = username -> {
            userLookups.incrementAndGet();
            return owner;
        };
        filter = new JwtAuthenticationFilter(jwtService, users, new ObjectMapper());
        guestTokens = new QrGuestTokenService(jwtService);
        ReflectionTestUtils.setField(guestTokens, "expirationMs", 60_000L);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private String guestToken() {
        return guestTokens.issue(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "T5").token();
    }

    private MockHttpServletResponse run(String method, String path, String token, MockFilterChain chain) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(method, path);
        req.setServletPath(path);
        req.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, chain);
        return res;
    }

    @Test
    void guestTokenCarriesTableClaimsAndNoUser() {
        UUID clientId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        UUID tableId = UUID.randomUUID();
        QrGuestTokenService.GuestToken guest = guestTokens.issue(clientId, orgId, tableId, "T5");

        Claims claims = jwtService.extractClaim(guest.token(), c -> c);
        assertThat(claims.get(JwtService.SCOPE_CLAIM)).isEqualTo(JwtService.QR_GUEST_SCOPE);
        assertThat(claims.get("clientId")).isEqualTo(clientId.toString());
        assertThat(claims.get("orgId")).isEqualTo(orgId.toString());
        assertThat(claims.get("tableId")).isEqualTo(tableId.toString());
        assertThat(claims.getSubject()).isEqualTo(JwtService.QR_GUEST_SUBJECT_PREFIX + guest.sessionId());
        assertThat(claims.get("userId")).isNull();
        assertThat(claims.get("role")).isNull();
    }

    @Test
    void eachTableSessionGetsItsOwnSessionId() {
        UUID clientId = UUID.randomUUID();
        UUID tableId = UUID.randomUUID();
        assertThat(guestTokens.issue(clientId, null, tableId, "T1").sessionId())
                .isNotEqualTo(guestTokens.issue(clientId, null, tableId, "T1").sessionId());
    }

    @Test
    void guestTokenIsRejectedOnEveryProtectedEndpoint() throws Exception {
        for (String method : new String[]{"GET", "POST", "PUT", "DELETE"}) {
            for (String path : new String[]{"/api/v1/customers", "/api/v1/orders", "/api/v1/reports/sales-summary",
                    "/api/v1/products", "/api/v1/ai/token"}) {
                MockFilterChain chain = new MockFilterChain();
                MockHttpServletResponse res = run(method, path, guestToken(), chain);
                assertThat(chain.getRequest()).as(method + " " + path + " must not reach the app").isNull();
                assertThat(res.getStatus()).isEqualTo(403);
            }
        }
        assertThat(userLookups.get()).as("a guest token must never load a user").isZero();
    }

    @Test
    void guestTokenOnPublicPathContinuesAnonymously() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        run("GET", "/api/v1/public/menu/abc/def", guestToken(), chain);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(userLookups.get()).isZero();
    }

    @Test
    void unknownScopeFailsClosed() throws Exception {
        Map<String, Object> claims = new HashMap<>();
        claims.put(JwtService.SCOPE_CLAIM, "something:else");
        claims.put("clientId", UUID.randomUUID().toString());
        String token = jwtService.generateToken(claims, owner, 60_000L);

        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse res = run("GET", "/api/v1/customers", token, chain);
        assertThat(chain.getRequest()).isNull();
        assertThat(res.getStatus()).isEqualTo(403);
    }

    @Test
    void scopedTokenWithoutScopeIsRefused() {
        assertThatThrownBy(() -> jwtService.generateScopedToken("qr-guest:x", new HashMap<>(), 60_000L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
