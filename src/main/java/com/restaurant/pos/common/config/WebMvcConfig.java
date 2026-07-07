package com.restaurant.pos.common.config;

import com.restaurant.pos.backup.TenantRestoreLockInterceptor;
import com.restaurant.pos.common.tenant.TenantInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final TenantInterceptor tenantInterceptor;
    private final TenantRestoreLockInterceptor tenantRestoreLockInterceptor;
    private final com.restaurant.pos.subscription.interceptor.RequireModuleInterceptor requireModuleInterceptor;

    @Value("${app.cors.allowed-origins:" +
            "http://localhost:3000," +
            "http://localhost:3001," +
            "http://192.168.29.152:3000," +
            "http://192.168.29.152:3001," +
            "https://cafe-test-qr-frontend.vercel.app," +
            "https://cafe-qr-frontend.vercel.app," +
            "https://test-cafe-qr-delivery-app.vercel.app," +
            "https://cafe-qr-delivery-app.vercel.app," +
            "https://test-cafe-qr-delivery-website.vercel.app," +
            "https://cafe-qr-delivery-website.vercel.app," +
            "https://cafeqr-delivery-website.vercel.app," +
            "https://cafeqr-frontend.pages.dev," +
            "https://*.pages.dev" +
            "}")
    private String[] allowedOrigins;

    @Override
    public void addInterceptors(@org.springframework.lang.NonNull InterceptorRegistry registry) {
        registry.addInterceptor(java.util.Objects.requireNonNull(tenantInterceptor));
        registry.addInterceptor(java.util.Objects.requireNonNull(tenantRestoreLockInterceptor));
        registry.addInterceptor(java.util.Objects.requireNonNull(requireModuleInterceptor));
    }

    @Override
    public void addCorsMappings(
            @org.springframework.lang.NonNull org.springframework.web.servlet.config.annotation.CorsRegistry registry) {
        String[] configuredOrigins = java.util.Arrays.stream(allowedOrigins)
                .filter(origin -> origin != null && !origin.isBlank())
                .map(String::trim)
                .toArray(String[]::new);
        registry.addMapping("/**")
                .allowedOriginPatterns(configuredOrigins)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}
