package com.smartTriage.smartTriage_server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Standalone PasswordEncoder configuration.
 * Extracted to its own class to prevent circular dependency with SecurityConfig.
 * BCrypt strength 12 — healthcare-grade password hashing.
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
