package com.smartTriage.smartTriage_server.module.audit.context;

import com.smartTriage.smartTriage_server.module.user.entity.User;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Per-request audit enrichment context (ThreadLocal-backed; MVC requests are
 * handled synchronously on one thread, and {@code AuditInterceptor} reads it in
 * {@code afterCompletion} on that same thread).
 *
 * <p>Two producers feed it, both one-liners at points that ALREADY know the answer:
 * <ul>
 *   <li>{@code ClinicalAuthz} — authorization must resolve WHICH VISIT every
 *       clinical endpoint touches (all resource-keyed {@code canAccessX} checks
 *       funnel into {@code canAccessVisit}); it notes that visit id here. This is
 *       what turns the flat HTTP audit log into a patient-centric trail without
 *       touching any module service.</li>
 *   <li>{@code AuthService} — notes the authenticated user on login/refresh, where
 *       the SecurityContext principal is not yet populated (login rows used to be
 *       recorded as "anonymous").</li>
 * </ul>
 *
 * <p>First-noted visit wins: the first visit resolved during authorization IS the
 * request's target; later incidental resolutions must not overwrite it. The
 * interceptor {@link #clear() clears} the context for every /api request — both
 * before handling (residue from thread reuse) and after recording.
 */
@Component
public class AuditContext {

    private final ThreadLocal<UUID> visitId = new ThreadLocal<>();
    private final ThreadLocal<User> actor = new ThreadLocal<>();
    private final ThreadLocal<String> sourceIp = new ThreadLocal<>();
    private final ThreadLocal<String> userAgent = new ThreadLocal<>();

    /** Attribute this request to a visit (first caller wins; nulls ignored). */
    public void noteVisit(UUID id) {
        if (id != null && visitId.get() == null) {
            visitId.set(id);
        }
    }

    /**
     * Origin of this request (V108 forensics) — noted once by AuditInterceptor.preHandle,
     * so both the interceptor's own record() call AND the custom producers that call
     * AuditService.record mid-request (RFID / cross-hospital reads) stamp the same origin.
     */
    public void noteRequest(String ip, String agent) {
        if (ip != null && !ip.isBlank()) sourceIp.set(ip);
        if (agent != null && !agent.isBlank()) userAgent.set(agent);
    }

    /** Attribute this request to an actor resolved outside the SecurityContext (login/refresh). */
    public void noteActor(User user) {
        if (user != null) {
            actor.set(user);
        }
    }

    public UUID getVisitId() {
        return visitId.get();
    }

    public User getActor() {
        return actor.get();
    }

    public String getSourceIp() {
        return sourceIp.get();
    }

    public String getUserAgent() {
        return userAgent.get();
    }

    /** Remove all request-scoped state (interceptor calls this on every /api request). */
    public void clear() {
        visitId.remove();
        actor.remove();
        sourceIp.remove();
        userAgent.remove();
    }
}
