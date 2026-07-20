package com.restaurant.pos.auth.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthFilter;
    private final SubscriptionCheckFilter subscriptionCheckFilter;
    private final AuthenticationProvider authenticationProvider;
    private final CustomAuthenticationEntryPoint authenticationEntryPoint;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .exceptionHandling(ex -> ex.authenticationEntryPoint(authenticationEntryPoint))
                .authorizeHttpRequests(req ->
                        req.requestMatchers(HttpMethod.OPTIONS, "/**")
                                .permitAll()
                                .requestMatchers(
                                        "/api/v1/auth/**",
                                        "/api/v1/debug/**",
                                        "/api/v1/public/**",
                                        "/api/v1/founder/**",
                                        "/api/delivery/**",
                                        "/delivery/**",
                                        "/v2/api-docs",
                                        "/v3/api-docs",
                                        "/v3/api-docs/**",
                                        "/swagger-resources",
                                        "/swagger-resources/**",
                                        "/configuration/ui",
                                        "/configuration/security",
                                        "/swagger-ui/**",
                                        "/webjars/**",
                                        "/swagger-ui.html",
                                        "/actuator/health",
                                        "/actuator/health/**"
                                )
                                .permitAll()
                                .anyRequest()
                                .authenticated()
                )
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authenticationProvider(authenticationProvider)
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(subscriptionCheckFilter, JwtAuthenticationFilter.class);

        return http.build();
    }

    // ── CORS allowed origins ───────────────────────────────────────────────────
    // Add new Vercel deployments here (comma-separated, no trailing spaces).
    // Also set app.cors.allowed-origins in Render environment variables to
    // override this default without a redeploy.
    @Value("${app.cors.allowed-origins:" +
            "http://localhost:3000," +
            "http://localhost:3001," +
            "https://cafe-test-qr-frontend.vercel.app," +
            "https://cafe-qr-frontend.vercel.app," +
            "https://test-cafe-qr-delivery-app.vercel.app," +
            "https://cafe-qr-delivery-app.vercel.app," +
            "https://test-cafe-qr-delivery-website.vercel.app," +
            "https://cafe-qr-delivery-website.vercel.app," +
            "https://cafeqr-delivery-website.vercel.app," +
            "https://cafeqr-frontend.pages.dev," +
            "https://*.pages.dev," +
            "https://pos.cafeqr.in" +
            "}")
    private String[] allowedOrigins;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        for (String origin : allowedOrigins) {
            if (origin != null && !origin.isBlank()) {
                configuration.addAllowedOriginPattern(origin.trim());
            }
        }
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(Arrays.asList("*"));
        configuration.setExposedHeaders(Arrays.asList("*"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
