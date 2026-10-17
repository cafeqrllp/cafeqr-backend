package com.restaurant.pos.auth.config;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CookieAuthOriginFilterTest {

    private final CookieAuthOriginFilter filter = new CookieAuthOriginFilter(
            List.of("https://*.cafeqr.in", "https://cafe-qr-frontend.vercel.app", "http://localhost:*"));

    /** @return true when the request reached the application, false when the filter blocked it with 403. */
    private boolean passes(MockHttpServletRequest req) throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, res, chain);
        boolean reached = chain.getRequest() != null;
        assertThat(reached).isEqualTo(res.getStatus() != 403);
        return reached;
    }

    private MockHttpServletRequest post(String origin) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/orders");
        req.setCookies(new Cookie("access_token", "jwt"));
        if (origin != null) {
            req.addHeader("Origin", origin);
        }
        return req;
    }

    @Test
    void blocksCookieAuthPostFromAttackerSite() throws Exception {
        assertThat(passes(post("https://evil.example"))).isFalse();
        assertThat(passes(post("https://attacker.vercel.app"))).isFalse(); // wildcard hosts are not trusted
        assertThat(passes(post("null"))).isFalse();
    }

    @Test
    void blocksForgedRefererWhenOriginMissing() throws Exception {
        MockHttpServletRequest req = post(null);
        req.addHeader("Referer", "https://evil.example/page");
        assertThat(passes(req)).isFalse();
    }

    @Test
    void allowsTrustedFrontendOrigins() throws Exception {
        assertThat(passes(post("https://app.cafeqr.in"))).isTrue();
        assertThat(passes(post("https://cafe-qr-frontend.vercel.app"))).isTrue();
        assertThat(passes(post("http://localhost:3000"))).isTrue();
    }

    @Test
    void bearerRequestsAndSafeMethodsAreNotChecked() throws Exception {
        MockHttpServletRequest bearer = post("https://evil.example");
        bearer.addHeader("Authorization", "Bearer abc");
        assertThat(passes(bearer)).isTrue();

        MockHttpServletRequest get = new MockHttpServletRequest("GET", "/api/v1/orders");
        get.setCookies(new Cookie("access_token", "jwt"));
        get.addHeader("Origin", "https://evil.example");
        assertThat(passes(get)).isTrue();
    }

    @Test
    void requestsWithoutAuthCookiesOrBrowserHeadersPass() throws Exception {
        MockHttpServletRequest noCookie = new MockHttpServletRequest("POST", "/api/v1/orders");
        noCookie.addHeader("Origin", "https://evil.example");
        assertThat(passes(noCookie)).isTrue();

        assertThat(passes(post(null))).isTrue(); // curl / native app: no Origin or Referer
    }
}
