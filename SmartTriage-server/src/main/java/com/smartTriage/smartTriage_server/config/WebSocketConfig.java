package com.smartTriage.smartTriage_server.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * WebSocket configuration for real-time vital streaming to frontend dashboards.
 *
 * Uses STOMP (Simple Text Oriented Messaging Protocol) over WebSocket for
 * structured pub/sub messaging.
 *
 * Topics:
 * /topic/vitals/{visitId} — real-time vital stream for a specific patient
 * /topic/alerts/{hospitalId} — alert broadcast for a hospital
 * /topic/devices/{hospitalId} — device status changes for a hospital
 * /topic/triage/{visitId} — triage changes for a specific patient
 *
 * Client connection:
 * ws://host:port/ws/smarttriage → STOMP endpoint
 * SockJS fallback available at same endpoint
 *
 * In production, a dedicated message broker (RabbitMQ, Redis) should replace
 * the in-memory simple broker for horizontal scaling.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Enable a simple in-memory broker for topic-based pub/sub
        registry.enableSimpleBroker("/topic");

        // Prefix for messages FROM clients (unused for now — devices use REST)
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // WebSocket endpoint — clients connect here
        // withSockJS() enables SockJS fallback (required for the frontend SockJS
        // client)
        registry.addEndpoint("/ws/smarttriage")
                .setAllowedOriginPatterns("*") // Configure properly in production
                .withSockJS();
    }
}
