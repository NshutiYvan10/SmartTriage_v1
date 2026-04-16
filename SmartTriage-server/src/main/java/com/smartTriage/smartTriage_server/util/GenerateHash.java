package com.smartTriage.smartTriage_server.util;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * One-time utility to generate a BCrypt hash. Run manually, then delete.
 */
public class GenerateHash {
    public static void main(String[] args) {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
        String hash = encoder.encode("SmartTriage@2026");
        System.out.println("=== BCrypt hash for SmartTriage@2026 ===");
        System.out.println(hash);
        System.out.println("=== Verify: " + encoder.matches("SmartTriage@2026", hash) + " ===");
    }
}
