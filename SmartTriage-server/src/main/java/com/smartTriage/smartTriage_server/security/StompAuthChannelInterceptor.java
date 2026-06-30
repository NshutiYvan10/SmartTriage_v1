package com.smartTriage.smartTriage_server.security;

import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.common.enums.Role;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Authenticates STOMP CONNECT frames with the same JWT the REST layer uses, and authorises
 * every SUBSCRIBE against the destination — closing the cross-tenant WebSocket hole where
 * any connected client could subscribe to another hospital's {@code /topic/alerts/{id}} or
 * another user's {@code /topic/alerts/user/{id}} and receive its PHI (the broker has no
 * per-destination security of its own).
 *
 * <p>CONNECT: read the {@code Authorization: Bearer <jwt>} native header, validate it, and
 * bind the resolved principal to the WebSocket session. A missing/invalid token REJECTS the
 * connection (no token → no realtime).
 *
 * <p>SUBSCRIBE: a forbidden subscription is DROPPED (returns null — the subscription is just
 * never registered) rather than throwing, because a thrown error closes the whole STOMP
 * session and would tear down the client's other legitimate subscriptions. Authorisation
 * reuses {@link ClinicalAuthz} so it matches the REST rules exactly:
 * <ul>
 *   <li>{@code /topic/alerts/user/{userId}} — must be the caller's own id.</li>
 *   <li>hospital-scoped families ({@code alerts,devices,beds,lab,medications,ems,visits,
 *       sepsis,fasttrack,hypoglycemia,isolation,pathway}/{hospitalId}…) — canAccessHospital.</li>
 *   <li>visit-scoped ({@code vitals,trend,triage}/{visitId} and {@code visit/{visitId}/notes})
 *       — canAccessVisit.</li>
 *   <li>SUPER_ADMIN bypasses (cross-hospital monitoring), matching ClinicalAuthz.</li>
 *   <li>any other {@code /topic/**} destination is DENIED (fail-safe — a new sensitive topic
 *       must be added here explicitly); non-{@code /topic} destinations are allowed.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;
    private final ClinicalAuthz clinicalAuthz;

    private static final String TOPIC_PREFIX = "/topic/";
    private static final Set<String> HOSPITAL_FAMILIES = Set.of(
            "alerts", "devices", "beds", "lab", "medications", "ems", "visits",
            "sepsis", "fasttrack", "hypoglycemia", "isolation", "pathway", "governance", "rfid");
    private static final Set<String> VISIT_FAMILIES = Set.of("vitals", "trend", "triage");

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message; // heartbeats / non-STOMP frames
        }

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            Authentication auth = authenticate(accessor);
            if (auth == null) {
                log.warn("[ws] Rejected STOMP CONNECT — missing/invalid bearer token");
                throw new MessagingException("Unauthorized WebSocket connection");
            }
            accessor.setUser(auth);
            return message;
        }

        if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            Authentication auth = (accessor.getUser() instanceof Authentication a) ? a : null;
            String destination = accessor.getDestination();
            if (!isSubscriptionAllowed(auth, destination)) {
                log.warn("[ws] Denied SUBSCRIBE to {} for user {}", destination,
                        auth != null ? auth.getName() : "anonymous");
                return null; // drop this subscription without tearing down the session
            }
        }
        return message;
    }

    /** Resolve + validate the bearer token from the CONNECT frame into an Authentication. */
    private Authentication authenticate(StompHeaderAccessor accessor) {
        try {
            List<String> headers = accessor.getNativeHeader("Authorization");
            if (headers == null || headers.isEmpty()) return null;
            String header = headers.get(0);
            if (header == null || !header.startsWith("Bearer ")) return null;
            String token = header.substring(7).trim();
            if (token.isEmpty()) return null;
            // Reject a REFRESH token presented as a CONNECT credential — only short-lived
            // ACCESS tokens may open a realtime stream (refresh tokens are long-lived).
            if (jwtService.isRefreshToken(token)) return null;
            String email = jwtService.extractUsername(token);
            if (email == null) return null;
            UserDetails userDetails = userDetailsService.loadUserByUsername(email);
            // Mirror the REST gate (JwtAuthenticationFilter): valid signature/expiry AND the
            // account is still enabled. User.isEnabled() folds in the user soft-delete flag
            // AND the owning hospital's active flag, so a deactivated user — or a user whose
            // hospital was deactivated — cannot open a realtime PHI stream on a still-unexpired
            // token, matching the "deactivation revokes in-flight JWTs on next (re)connect"
            // contract the REST filter enforces.
            if (!jwtService.isTokenValid(token, userDetails) || !userDetails.isEnabled()) return null;
            return new UsernamePasswordAuthenticationToken(
                    userDetails, null, userDetails.getAuthorities());
        } catch (Exception e) {
            log.warn("[ws] CONNECT token validation failed: {}", e.getMessage());
            return null;
        }
    }

    private boolean isSubscriptionAllowed(Authentication auth, String destination) {
        if (auth == null) return false;
        if (destination == null) return false;
        if (!destination.startsWith(TOPIC_PREFIX)) return true; // only the broker topics carry PHI

        // SUPER_ADMIN can monitor across hospitals (matches ClinicalAuthz).
        if (auth.getPrincipal() instanceof User u && u.getRole() == Role.SUPER_ADMIN) {
            return true;
        }

        String[] seg = destination.substring(TOPIC_PREFIX.length()).split("/");
        if (seg.length == 0 || seg[0].isBlank()) return false;
        String family = seg[0];

        // User-targeted: /topic/alerts/user/{userId} — STRICTLY the caller's own id.
        if ("alerts".equals(family) && seg.length >= 3 && "user".equals(seg[1])) {
            UUID self = (auth.getPrincipal() instanceof User u) ? u.getId() : null;
            UUID target = parseUuid(seg[2]);
            return self != null && self.equals(target);
        }

        // Alert topics are role/zone-SCOPED (mirrors AlertScopeResolver, the REST half):
        //   /topic/alerts/{hospitalId}        → hospital-wide firehose, OVERSIGHT roles only
        //                                       (Charge Nurse / shift lead / Super-Admin /
        //                                       Read-Only). A zone-bound nurse's SUBSCRIBE here
        //                                       is silently dropped — that is how the General
        //                                       nurse stops seeing Acute / inbound alerts live.
        //   /topic/alerts/{hospitalId}/{ZONE} → caller must currently cover that zone (or be
        //                                       oversight).
        // No missed alerts: publishHospitalAlert fans every zoned alert out to its zone topic,
        // so zone-bound staff (subscribed to their zone) still receive everything for their zone.
        if ("alerts".equals(family)) {
            UUID hospitalId = seg.length >= 2 ? parseUuid(seg[1]) : null;
            if (hospitalId == null) {
                return false;
            }
            if (seg.length == 2) {
                return clinicalAuthz.canSeeAllZonesAtHospital(auth, hospitalId);
            }
            EdZone zone = parseZone(seg[2]);
            return zone != null && clinicalAuthz.canReceiveZoneAlerts(auth, hospitalId, zone);
        }

        // Visit-scoped: /topic/{vitals|trend|triage}/{visitId} and /topic/visit/{visitId}/notes.
        if (VISIT_FAMILIES.contains(family)) {
            UUID visitId = seg.length >= 2 ? parseUuid(seg[1]) : null;
            return visitId != null && clinicalAuthz.canAccessVisit(auth, visitId);
        }
        if ("visit".equals(family)) {
            UUID visitId = seg.length >= 2 ? parseUuid(seg[1]) : null;
            return visitId != null && clinicalAuthz.canAccessVisit(auth, visitId);
        }

        // Hospital-scoped: {family}/{hospitalId}[/...].
        if (HOSPITAL_FAMILIES.contains(family)) {
            UUID hospitalId = seg.length >= 2 ? parseUuid(seg[1]) : null;
            return hospitalId != null && clinicalAuthz.canAccessHospital(auth, hospitalId);
        }

        // Unknown /topic/** destination — deny by default (a new sensitive topic must be
        // added to the families above). Fail-safe for a multi-tenant medical system.
        return false;
    }

    private static UUID parseUuid(String s) {
        try {
            return s == null ? null : UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static EdZone parseZone(String s) {
        try {
            return s == null ? null : EdZone.valueOf(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
