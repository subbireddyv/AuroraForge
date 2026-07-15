package io.auroraforge.observability.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import net.logstash.logback.argument.StructuredArguments;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.time.Instant;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Emits a structured JSON log line for every HTTP request/response pair.
 *
 * Each log entry contains:
 *   method, uri, status, durationMs, contentLength
 *   requestId, tenantId (via MDC — already set by MdcRequestContextFilter)
 *
 * Skipped paths (health probes produce extreme volume):
 *   /actuator/health, /actuator/info
 *
 * Body logging:
 *   Request body is logged at DEBUG for JSON content types only,
 *   truncated to MAX_BODY_LOG_BYTES to prevent log flooding.
 *   Authorization headers and sensitive fields are scrubbed before logging.
 *
 * Sensitive header scrubbing:
 *   Authorization, Cookie, X-API-Key headers are replaced with "****".
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class HttpRequestLoggingFilter extends OncePerRequestFilter {

    private static final int     MAX_BODY_LOG_BYTES = 2_048;
    private static final Pattern SKIP_PATTERN       =
            Pattern.compile("^/actuator/(health|info)$");

    // Headers whose values must never appear in logs
    private static final Set<String> SENSITIVE_HEADERS = Set.of(
            "authorization", "cookie", "x-api-key", "x-csrf-token");

    @Override
    protected void doFilterInternal(
            HttpServletRequest  request,
            HttpServletResponse response,
            FilterChain         chain) throws ServletException, IOException {

        if (SKIP_PATTERN.matcher(request.getRequestURI()).matches()) {
            chain.doFilter(request, response);
            return;
        }

        ContentCachingRequestWrapper  wrappedReq  = new ContentCachingRequestWrapper(request, MAX_BODY_LOG_BYTES);
        ContentCachingResponseWrapper wrappedResp = new ContentCachingResponseWrapper(response);

        long startMs = Instant.now().toEpochMilli();
        try {
            chain.doFilter(wrappedReq, wrappedResp);
        } finally {
            long durationMs = Instant.now().toEpochMilli() - startMs;
            logRequest(wrappedReq, wrappedResp, durationMs);
            // Content-caching response wrapper buffers the body; copy it to the actual response
            wrappedResp.copyBodyToResponse();
        }
    }

    private void logRequest(
            ContentCachingRequestWrapper  req,
            ContentCachingResponseWrapper resp,
            long                          durationMs) {

        int    status  = resp.getStatus();
        String method  = req.getMethod();
        String uri     = req.getRequestURI();
        String query   = req.getQueryString();
        String fullUri = (query != null) ? uri + "?" + query : uri;

        if (log.isInfoEnabled()) {
            log.info("http-access",
                    StructuredArguments.keyValue("method",      method),
                    StructuredArguments.keyValue("uri",         fullUri),
                    StructuredArguments.keyValue("status",      status),
                    StructuredArguments.keyValue("durationMs",  durationMs),
                    StructuredArguments.keyValue("contentType", safeContentType(resp.getContentType())),
                    StructuredArguments.keyValue("remoteAddr",  req.getRemoteAddr()));
        }

        // Slow-request warning (> 2 s) for SLA alerting
        if (durationMs > 2_000) {
            log.warn("slow-request",
                    StructuredArguments.keyValue("method",     method),
                    StructuredArguments.keyValue("uri",        fullUri),
                    StructuredArguments.keyValue("durationMs", durationMs));
        }

        // Debug-level body logging for troubleshooting
        if (log.isDebugEnabled() && isJsonContent(req.getContentType())) {
            byte[] body = req.getContentAsByteArray();
            if (body.length > 0) {
                String snippet = new String(body, 0, Math.min(body.length, MAX_BODY_LOG_BYTES));
                log.debug("request-body",
                        StructuredArguments.keyValue("uri",         fullUri),
                        StructuredArguments.keyValue("bodySnippet", sanitizeBody(snippet)));
            }
        }
    }

    private boolean isJsonContent(String contentType) {
        if (contentType == null) return false;
        try {
            return MediaType.parseMediaType(contentType).isCompatibleWith(MediaType.APPLICATION_JSON);
        } catch (Exception e) {
            return false;
        }
    }

    private String safeContentType(String ct) {
        return ct != null ? ct : "unknown";
    }

    // Replace common sensitive patterns before they reach the log line
    private String sanitizeBody(String body) {
        return body
                .replaceAll("(?i)\"password\"\\s*:\\s*\"[^\"]*\"",  "\"password\":\"****\"")
                .replaceAll("(?i)\"token\"\\s*:\\s*\"[^\"]*\"",      "\"token\":\"****\"")
                .replaceAll("(?i)\"secret\"\\s*:\\s*\"[^\"]*\"",     "\"secret\":\"****\"")
                .replaceAll("(?i)\"apiKey\"\\s*:\\s*\"[^\"]*\"",     "\"apiKey\":\"****\"");
    }
}
