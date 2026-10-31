package com.kanbanstudio.web;

import org.springframework.http.HttpStatus;

/** An error returned to the client as {"detail": message} with the given status. */
class ApiException extends RuntimeException {

    private final HttpStatus status;

    ApiException(HttpStatus status, String detail) {
        super(detail);
        this.status = status;
    }

    ApiException(HttpStatus status, String detail, Throwable cause) {
        super(detail, cause);
        this.status = status;
    }

    HttpStatus status() {
        return status;
    }
}
