package com.example.retention.common;

import org.springframework.http.HttpStatus;

/** 业务异常：携带 HTTP 状态码，由全局处理器渲染为一致的错误体。 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public static ApiException notFound(String what) {
        return new ApiException(HttpStatus.NOT_FOUND, what);
    }

    public static ApiException conflict(String what) {
        return new ApiException(HttpStatus.CONFLICT, what);
    }

    public static ApiException badRequest(String what) {
        return new ApiException(HttpStatus.BAD_REQUEST, what);
    }

    public static ApiException forbidden(String what) {
        return new ApiException(HttpStatus.FORBIDDEN, what);
    }
}
