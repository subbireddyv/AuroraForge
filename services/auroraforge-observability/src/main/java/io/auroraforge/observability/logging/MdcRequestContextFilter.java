package io.auroraforge.observability.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Populates the SLF4J MDC with request-scoped identifiers so every log line
 * emitted during a request carries consistent correlation fields.
 *
 * MDC keys set:
 *   requestId  — X-Request-ID header, or a fresh UUID when absent
 *   tenantId   — extracted from /api/v1/tenants/{tenantId}/... path segment
 *   userId     — sub claim from X-Forwarded-User header (populated by auth gateway)
 *   traceId    — OpenTelemetry trace ID (set by micrometer-tracing automatically;
 *                this filter sets a fallback so it is never blank)
 *   spanId     — current OTel span ID (fallback)
 *
 * The MDC is cleared in the finally block so pooled threads never leak values
 * across requests.
 *
 * Ordering: runs at HIGHEST_PRECEDENCE so MDC is available to all subsequent filters.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MdcRequestContextFilter extends OncePerRequestFilter {

    // Header set by the API gateway / ingress; absent in direct-to-service calls
    private static final String REQUEST_ID_HEADER   = "X-Request-ID";
    private static final String FORWARDED_USER_HDR  = "X-Forwarded-User";

    // MDC key names — must match logback-base.xml includeMdcKeyName entries
    public static final String MDC_REQUEST_ID = "requestId";
    public static final String MDC_TENANT_ID  = "tenantId";
    public static final String MDC_USER_ID    = "userId";
    public static final String MDC_TRACE_ID   = "traceId";
    public static final String MDC_SPAN_ID    = "spanId";

    @Override
    protected void doFilterInternal(
            HttpServletRequest  request,
            HttpServletResponse response,
            FilterChain         chain) throws ServletException, IOException {

        try {
            String requestId = resolveRequestId(request);
            MDC.put(MDC_REQUEST_ID, requestId);
            MDC.put(MDC_TENANT_ID,  extractTenantId(request.getRequestURI()));
            MDC.put(MDC_USER_ID,    nullToEmpty(request.getHeader(FORWARDED_USER_HDR)));

            // OTel bridge writes traceId/spanId automatically when a span is active.
            // Set fallback values so JSON fields are never absent (Elasticsearch mapping stability).
            MDC.putIfAbsent(MDC_TRACE_ID, "0000000000000000");
            MDC.putIfAbsent(MDC_SPAN_ID,  "0000000000000000");

            // Echo the resolved request-id back so callers can correlate with logs
            response.setHeader(REQUEST_ID_HEADER, requestId);

            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_REQUEST_ID);
            MDC.remove(MDC_TENANT_ID);
            MDC.remove(MDC_USER_ID);
            MDC.remove(MDC_TRACE_ID);
            MDC.remove(MDC_SPAN_ID);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String resolveRequestId(HttpServletRequest request) {
        String header = request.getHeader(REQUEST_ID_HEADER);
        return (header != null && !header.isBlank()) ? header : UUID.randomUUID().toString();
    }

    /**
     * Extracts the tenantId segment from paths like
     * /api/v1/tenants/{tenantId}/...
     * Returns "–" when the path does not follow that convention.
     */
    private String extractTenantId(String uri) {
        if (uri == null) return "–";
        String[] segments = uri.split("/");
        for (int i = 0; i < segments.length - 1; i++) {
            if ("tenants".equals(segments[i]) && !segments[i + 1].isBlank()) {
                return segments[i + 1];
            }
        }
        return "–";
    }

    private String nullToEmpty(String value) {
        return (value != null) ? value : "–";
    }
}
