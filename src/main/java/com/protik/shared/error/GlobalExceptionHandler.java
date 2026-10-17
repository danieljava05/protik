package com.protik.shared.error;

import com.protik.shared.web.CorrelationIdFilter;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Every error leaves the API as RFC 9457 {@code application/problem+json}, with
 * the request's correlation ID. Framework exceptions (404, 405, malformed JSON,
 * unsupported media type...) are handled by the base class; this adds our own
 * types and guarantees that unexpected exceptions never leak internals.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Object> handleApiException(ApiException ex, WebRequest request) {
        ProblemType type = ex.problemType();
        ProblemDetail body = ProblemDetail.forStatusAndDetail(type.status(), ex.getMessage());
        body.setType(type.type());
        body.setTitle(type.title());
        return handleExceptionInternal(ex, body, new HttpHeaders(), type.status(), request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        // Full detail goes to the log (with the correlation ID); the client gets none.
        log.error("Unhandled exception", ex);
        ProblemType type = ProblemType.INTERNAL_ERROR;
        ProblemDetail body = ProblemDetail.forStatusAndDetail(type.status(),
                "An unexpected error occurred. Quote the correlation ID when reporting it.");
        body.setType(type.type());
        body.setTitle(type.title());
        return handleExceptionInternal(ex, body, new HttpHeaders(), type.status(), request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemType type = ProblemType.VALIDATION_FAILED;
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, "One or more fields are invalid.");
        body.setType(type.type());
        body.setTitle(type.title());
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> Map.of("field", e.getField(), "message", String.valueOf(e.getDefaultMessage())))
                .toList();
        body.setProperty("errors", errors);
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
            if (correlationId != null) {
                problem.setProperty("correlationId", correlationId);
            }
        }
        return response;
    }
}
