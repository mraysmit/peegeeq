package dev.mars.peegeeq.rest.handlers;

/**
 * Signals a 4xx HTTP response condition through the Future failure channel.
 * Used inside {@code .compose()} steps to route expected client errors to
 * {@code onFailure()} without leaking exceptions through {@code onSuccess()}.
 */
class ResponseException extends RuntimeException {

    final int statusCode;

    ResponseException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }
}
