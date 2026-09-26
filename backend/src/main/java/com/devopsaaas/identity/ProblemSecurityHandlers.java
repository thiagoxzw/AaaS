package com.devopsaaas.identity;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * 401 and 403 as problem+json. The bodies are fixed strings: nothing from the request or the exception is
 * echoed back.
 */
@Component
class ProblemSecurityHandlers {

    private static final String UNAUTHORIZED = """
            {"type":"about:blank","title":"Unauthorized","status":401,\
            "detail":"Authentication is required, or the token is invalid or expired."}""";
    private static final String FORBIDDEN = """
            {"type":"about:blank","title":"Forbidden","status":403,\
            "detail":"You do not have permission to perform this action."}""";

    private final Counter denied;

    ProblemSecurityHandlers(MeterRegistry registry) {
        this.denied = Counter.builder("devops.authorization.denied")
                .description("Requests rejected because the user lacks the required permission")
                .register(registry);
    }

    AuthenticationEntryPoint entryPoint() {
        return (request, response, exception) -> {
            response.setHeader("WWW-Authenticate", "Bearer");
            write(response, HttpServletResponse.SC_UNAUTHORIZED, UNAUTHORIZED);
        };
    }

    AccessDeniedHandler accessDeniedHandler() {
        return (request, response, exception) -> {
            denied.increment();
            write(response, HttpServletResponse.SC_FORBIDDEN, FORBIDDEN);
        };
    }

    @SuppressFBWarnings(value = "XSS_SERVLET",
            justification = "Writes one of two constant problem+json bodies; nothing from the request is echoed")
    private static void write(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(body);
    }
}
