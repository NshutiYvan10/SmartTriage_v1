package com.smartTriage.smartTriage_server.config;

import com.smartTriage.smartTriage_server.security.JwtAuthenticationEntryPoint;
import com.smartTriage.smartTriage_server.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Security configuration for SmartTriage.
 *
 * Healthcare-grade security requirements:
 *  - Stateless JWT sessions (no server-side session)
 *  - BCrypt password hashing (defined in PasswordEncoderConfig)
 *  - Role-based method security
 *  - CSRF disabled (API-only, JWT-based)
 *  - Proper CORS for frontend integration
 *
 * Note: JwtAuthenticationFilter and UserDetailsService are injected via
 * method parameters (not constructor) to break the circular dependency
 * chain: SecurityConfig → JwtAuthFilter → UserService → PasswordEncoder → SecurityConfig
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtAuthFilter,
            JwtAuthenticationEntryPoint jwtEntryPoint,
            AuthenticationProvider authenticationProvider
    ) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .exceptionHandling(ex -> ex.authenticationEntryPoint(jwtEntryPoint))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Public endpoints (auth + account activation)
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        // IoT device-write endpoints — authenticated via API key header,
                        // not JWT. ONLY /ingest and /heartbeat (in IoTStreamController)
                        // are device-driven; everything else under /api/v1/iot/** is a
                        // clinical-read endpoint (vital streams, monitoring sessions,
                        // device inventory) and MUST flow through the JWT chain so
                        // ClinicalAuthz can gate it. The earlier broad
                        // /api/v1/iot/stream/** rule unintentionally exposed
                        // /api/v1/iot/stream/latest/{visitId} (and siblings on
                        // IoTDeviceController) to anonymous callers.
                        .requestMatchers("/api/v1/iot/stream/ingest",
                                          "/api/v1/iot/stream/heartbeat").permitAll()

                        // WebSocket endpoint
                        .requestMatchers("/ws/**").permitAll()

                        // All other endpoints require authentication
                        .anyRequest().authenticated()
                )
                .authenticationProvider(authenticationProvider)
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public AuthenticationProvider authenticationProvider(
            UserDetailsService userDetailsService,
            PasswordEncoder passwordEncoder
    ) {
        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider(userDetailsService);
        authProvider.setPasswordEncoder(passwordEncoder);
        return authProvider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
