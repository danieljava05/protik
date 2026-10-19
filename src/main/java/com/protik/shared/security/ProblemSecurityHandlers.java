package com.protik.shared.security;

import com.protik.shared.error.ProblemType;
import com.protik.shared.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 401 and 403 raised by the security filter chain never reach MVC's exception
 * handler, so they are rendered here as RFC 9457 problem documents, like every
 * other error. The detail is deliberately generic: it must not say why a token
 * was rejected.
 */
@Component
class ProblemSecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final JsonMapper json;

    ProblemSecurityHandlers(JsonMapper json) {
        this.json = json;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        response.setHeader("WWW-Authenticate", "Bearer");
        write(response, ProblemType.UNAUTHENTICATED, "A valid access token is required.");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException {
        write(response, ProblemType.FORBIDDEN, "You are not allowed to do that.");
    }

    private void write(HttpServletResponse response, ProblemType type, String detail) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(type.status(), detail);
        problem.setType(type.type());
        problem.setTitle(type.title());
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) {
            problem.setProperty("correlationId", correlationId);
        }
        response.setStatus(type.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");
        json.writeValue(response.getOutputStream(), problem);
    }
}
