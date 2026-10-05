package com.nequi.paymentmock.web;

import java.nio.charset.StandardCharsets;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/** Writes an {@link ErrorBody} directly to the response, outside the handler pipeline (filters, error handler). */
final class ErrorWriter {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ErrorWriter() {
    }

    /** Caches the serializer on the calling (startup) thread, see {@link JsonWarmUp}. */
    static void warmUp() {
        JSON.writeValueAsString(new ErrorBody(ErrorBody.INTERNAL_ERROR, "warm-up"));
    }

    static Mono<Void> write(ServerWebExchange exchange, HttpStatus status, ErrorBody body) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] bytes = JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        response.getHeaders().setContentLength(bytes.length);
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }
}
