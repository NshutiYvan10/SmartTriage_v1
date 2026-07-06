package com.smartTriage.smartTriage_server.module.audit.interceptor;

import com.smartTriage.smartTriage_server.module.audit.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

/**
 * Records every STATE-CHANGING request (POST/PUT/PATCH/DELETE) to the persisted
 * audit log after it completes — who (from the security context), what (method +
 * path + derived action), and the outcome (HTTP status). Reads (GET) are not
 * audited (volume + no state change). Runs in {@code afterCompletion}, where the
 * SecurityContext is still populated; the write is fail-safe in its own transaction.
 */
@Component
@RequiredArgsConstructor
public class AuditInterceptor implements HandlerInterceptor {

    private final AuditService auditService;
    private final com.smartTriage.smartTriage_server.module.audit.context.AuditContext auditContext;

    private static final Set<String> MUTATING = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final String UUID_REGEX =
            "/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // Defensive: servlet threads are pooled — never let a previous request's
        // visit/actor attribution leak into this one.
        auditContext.clear();
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        try {
            String method = request.getMethod();
            if (method == null || !MUTATING.contains(method)) {
                return;
            }
            String path = request.getRequestURI();
            if (path == null || !path.startsWith("/api/")) {
                return; // skip websocket / static / non-API
            }
            // record() reads this request's AuditContext (visit resolved by authz,
            // actor noted on login/refresh) — still populated here: afterCompletion
            // runs on the request thread, before the clear below.
            auditService.record(method, path, deriveAction(method, path), response.getStatus());
        } finally {
            auditContext.clear();
        }
    }

    /** Readable, groupable action label: verb + resource path with UUIDs masked. */
    private static String deriveAction(String method, String path) {
        String p = path.replaceAll(UUID_REGEX, "/{id}").replaceFirst("^/api/v\\d+", "");
        String label = method + " " + p;
        return label.length() > 120 ? label.substring(0, 120) : label;
    }
}
