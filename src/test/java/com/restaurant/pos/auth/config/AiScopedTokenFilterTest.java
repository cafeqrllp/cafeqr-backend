package com.restaurant.pos.auth.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.restaurant.pos.auth.service.JwtService;
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

import static org.assertj.core.api.Assertions.assertThat;

/** AI assistant tokens (scope=ai:read) must only ever be able to read. */
class AiScopedTokenFilterTest {

    private final UserDetails owner = User.withUsername("owner@cafe.test").password("x").roles("ADMIN").build();
    private JwtService jwtService;
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "privateKeyStr", "");
        ReflectionTestUtils.setField(jwtService, "publicKeyStr", "");
        ReflectionTestUtils.setField(jwtService, "jwtExpiration", 60_000L);
        ReflectionTestUtils.setField(jwtService, "refreshExpiration", 60_000L);
        jwtService.init();
        UserDetailsService users = username -> owner;
        filter = new JwtAuthenticationFilter(jwtService, users, new ObjectMapper());
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private String token(boolean aiScoped) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("clientId", UUID.randomUUID().toString());
        if (aiScoped) {
            claims.put(JwtService.SCOPE_CLAIM, JwtService.AI_READ_SCOPE);
        }
        return jwtService.generateToken(claims, owner, 60_000L);
    }

    private MockHttpServletResponse run(String method, String token, MockFilterChain chain) throws Exception {
        return run(method, "/api/v1/reports/sales-summary", token, chain);
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
    void aiTokenCanRead() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse res = run("GET", token(true), chain);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    void aiTokenCannotWrite() throws Exception {
        for (String method : new String[]{"POST", "PUT", "PATCH", "DELETE"}) {
            MockFilterChain chain = new MockFilterChain();
            MockHttpServletResponse res = run(method, token(true), chain);
            assertThat(chain.getRequest()).as(method + " must not reach the app").isNull();
            assertThat(res.getStatus()).isEqualTo(403);
            assertThat(res.getContentAsString()).contains("read-only");
        }
    }

    @Test
    void aiTokenCannotReadOutsideTheAssistantEndpoints() throws Exception {
        for (String path : new String[]{"/api/v1/backups/1/download", "/api/v1/customers", "/api/v1/users",
                "/api/v1/accounting/journals", "/api/v1/orders", "/api/v1/reports/sales-summary/../../users"}) {
            MockFilterChain chain = new MockFilterChain();
            MockHttpServletResponse res = run("GET", path, token(true), chain);
            assertThat(chain.getRequest()).as(path + " must not reach the app").isNull();
            assertThat(res.getStatus()).isEqualTo(403);
        }
    }

    @Test
    void aiTokenCanReadEveryAssistantEndpoint() throws Exception {
        for (String path : new String[]{"/api/v1/clients/me", "/api/v1/organizations", "/api/v1/reports/item-wise",
                "/api/v1/reports/hourly", "/api/v1/orders/history", "/api/v1/inventory/stock-overview",
                "/api/v1/products", "/api/v1/tables/active", "/api/v1/reports/profit-loss/"}) {
            MockFilterChain chain = new MockFilterChain();
            run("GET", path, token(true), chain);
            assertThat(chain.getRequest()).as(path).isNotNull();
        }
    }

    @Test
    void normalLoginTokenCanStillReadEverything() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        run("GET", "/api/v1/customers", token(false), chain);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void normalLoginTokenCanStillWrite() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        run("POST", token(false), chain);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void publishesPublicKeyThatVerifiesTokens() {
        assertThat(jwtService.getPublicKeyBase64()).isNotBlank();
        assertThat(jwtService.extractScope(token(true))).isEqualTo(JwtService.AI_READ_SCOPE);
        assertThat(jwtService.extractScope(token(false))).isNull();
    }
}
