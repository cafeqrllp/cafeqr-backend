package com.restaurant.pos.auth.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Guards against re-introducing wildcard hosts anyone can register (they would be trusted with credentials). */
class CorsOriginsTest {

    /** Builds SecurityConfig with its @Value default for allowed origins, as when no environment override is set. */
    private CorsConfiguration corsConfigWithDefaults() throws Exception {
        Constructor<?> ctor = SecurityConfig.class.getDeclaredConstructors()[0];
        Object[] args = new Object[ctor.getParameterCount()];
        Class<?>[] types = ctor.getParameterTypes();
        for (int i = 0; i < args.length; i++) {
            args[i] = mock(types[i]);
        }
        SecurityConfig config = (SecurityConfig) ctor.newInstance(args);

        Field field = SecurityConfig.class.getDeclaredField("allowedOrigins");
        String expr = field.getAnnotation(Value.class).value(); // ${app.cors.allowed-origins:<defaults>}
        String defaults = expr.substring(expr.indexOf(':') + 1, expr.length() - 1);
        ReflectionTestUtils.setField(config, "allowedOrigins", defaults.split(","));

        CorsConfigurationSource source = config.corsConfigurationSource();
        return source.getCorsConfiguration(new MockHttpServletRequest("GET", "/api/v1/orders"));
    }

    @Test
    void unknownVercelAndPagesHostsAreRejected() throws Exception {
        CorsConfiguration cors = corsConfigWithDefaults();
        assertThat(cors.checkOrigin("https://attacker.vercel.app")).isNull();
        assertThat(cors.checkOrigin("https://attacker.pages.dev")).isNull();
        assertThat(cors.checkOrigin("https://evil.example")).isNull();
    }

    @Test
    void knownDeploymentsStillWork() throws Exception {
        CorsConfiguration cors = corsConfigWithDefaults();
        for (String origin : new String[] {
                "https://cafe-test-qr-frontend.vercel.app",
                "https://cafe-qr-frontend.vercel.app",
                "https://cafeqr-frontend.pages.dev",
                "https://cafe-qr-delivery-website.vercel.app",
                "https://cafe-qr-delivery-app.vercel.app",
                "https://cafeqr.in",
                "https://app.cafeqr.in",
                "https://pos.cafeqr.in"}) {
            assertThat(cors.checkOrigin(origin)).as(origin).isEqualTo(origin);
        }
    }
}
