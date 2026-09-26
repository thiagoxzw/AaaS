package com.devopsaaas.shared.observability;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Correlates every log line of a request through a {@code requestId} MDC entry and echoes it back in
 * the {@code X-Request-Id} response header.
 *
 * <p>A client-supplied id is reused only when it matches a strict format; anything else is replaced by
 * a generated UUID, so the header cannot be used to inject content into the logs.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    private static final Pattern VALID_REQUEST_ID = Pattern.compile("[A-Za-z0-9-]{8,64}");

    @Override
    @SuppressFBWarnings(
            value = "SERVLET_HEADER",
            justification = "The header is untrusted by design and only used after matching VALID_REQUEST_ID; "
                    + "see RequestIdFilterTest.replacesRequestId_whenHeaderCouldInjectIntoLogs")
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = resolveRequestId(request.getHeader(HEADER));
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    static String resolveRequestId(String candidate) {
        if (candidate != null && VALID_REQUEST_ID.matcher(candidate).matches()) {
            return candidate;
        }
        return UUID.randomUUID().toString();
    }
}
