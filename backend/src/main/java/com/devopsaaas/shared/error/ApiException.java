package com.devopsaaas.shared.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** An expected error that maps to a problem+json response with a safe, client-facing detail. */
public class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final HttpStatus status;
    private final LinkedHashMap<String, String> headers;

    public ApiException(HttpStatus status, String detail) {
        this(status, detail, Map.of());
    }

    public ApiException(HttpStatus status, String detail, Map<String, String> headers) {
        super(detail);
        this.status = status;
        this.headers = new LinkedHashMap<>(headers);
    }

    public HttpStatus status() {
        return status;
    }

    public Map<String, String> headers() {
        return Collections.unmodifiableMap(headers);
    }

    public static ApiException notFound(String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, detail);
    }

    public static ApiException conflict(String detail) {
        return new ApiException(HttpStatus.CONFLICT, detail);
    }

    public static ApiException unprocessable(String detail) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, detail);
    }
}
