package com.nequi.paymentmock.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.MethodNotAllowedException;
import org.springframework.web.server.NotAcceptableStatusException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;
import org.springframework.web.server.UnsupportedMediaTypeStatusException;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Error catalogue of PM-IV-005 for everything that is not answered by an operation itself:
 * <ul>
 *   <li>request validation, unreadable or oversized bodies and unsupported media types: 400 {@code VALIDATION_ERROR};</li>
 *   <li>framework 404/405/406: the same status with {@code NOT_FOUND}, {@code METHOD_NOT_ALLOWED} or
 *       {@code NOT_ACCEPTABLE};</li>
 *   <li>anything unexpected: 500 {@code INTERNAL_ERROR} without stack trace or internal detail in the body.</li>
 * </ul>
 * Runs before the Spring Boot default handler (order -1).
 */
@Component
@Order(-2)
public class ApiErrorHandler implements WebExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiErrorHandler.class);

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable error) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(error);
        }
        return switch (error) {
            case RequestValidationException invalid ->
                    write(exchange, HttpStatus.BAD_REQUEST, ErrorBody.VALIDATION_ERROR, invalid.getMessage());
            case ServerWebInputException ignored ->
                    write(exchange, HttpStatus.BAD_REQUEST, ErrorBody.VALIDATION_ERROR, "Malformed request");
            case UnsupportedMediaTypeStatusException ignored ->
                    write(exchange, HttpStatus.BAD_REQUEST, ErrorBody.VALIDATION_ERROR, "Unsupported content type");
            case DataBufferLimitException ignored ->
                    write(exchange, HttpStatus.BAD_REQUEST, ErrorBody.VALIDATION_ERROR, "Request body too large");
            case MethodNotAllowedException ignored ->
                    write(exchange, HttpStatus.METHOD_NOT_ALLOWED, ErrorBody.METHOD_NOT_ALLOWED, "Method not allowed");
            case NotAcceptableStatusException ignored ->
                    write(exchange, HttpStatus.NOT_ACCEPTABLE, ErrorBody.NOT_ACCEPTABLE, "Not acceptable");
            case ResponseStatusException status -> fromStatus(exchange, status.getStatusCode(), error);
            default -> internal(exchange, error);
        };
    }

    private Mono<Void> fromStatus(ServerWebExchange exchange, HttpStatusCode status, Throwable error) {
        if (status.value() == HttpStatus.NOT_FOUND.value()) {
            return write(exchange, HttpStatus.NOT_FOUND, ErrorBody.NOT_FOUND, "Resource not found");
        }
        if (status.is4xxClientError()) {
            return write(exchange, HttpStatus.BAD_REQUEST, ErrorBody.VALIDATION_ERROR, "Invalid request");
        }
        return internal(exchange, error);
    }

    private Mono<Void> internal(ServerWebExchange exchange, Throwable error) {
        String method = exchange.getRequest().getMethod().name();
        // Only the exception type is logged: messages may carry input data (ADR-032). Logging is offloaded
        // because console appenders perform blocking I/O.
        return Mono.fromRunnable(() -> LOG.error("Unexpected internal error on {} request: {}", method,
                        error.getClass().getName()))
                .subscribeOn(Schedulers.boundedElastic())
                .then(write(exchange, HttpStatus.INTERNAL_SERVER_ERROR, ErrorBody.INTERNAL_ERROR, "Internal error"));
    }

    private static Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String code, String message) {
        return ErrorWriter.write(exchange, status, new ErrorBody(code, message));
    }
}
