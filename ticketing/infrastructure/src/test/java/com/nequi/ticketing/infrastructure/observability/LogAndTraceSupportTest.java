package com.nequi.ticketing.infrastructure.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.nequi.ticketing.infrastructure.adapter.sqs.TraceContext;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext.Builder;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.TracingObservationHandler.TracingContext;
import io.micrometer.tracing.test.simple.SimpleTracer;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

/** CMP-018 support: log dispatcher without blocking, structured log records and W3C trace context. */
class LogAndTraceSupportTest {

    @Test
    @DisplayName("CMP-018 NFR-003 log calls are enqueued without blocking from reactive threads and written by the writer thread")
    void dispatcherWritesOffTheReactiveThreads() throws Exception {
        LogDispatcher dispatcher = LogDispatcher.start(100);
        List<String> threads = new CopyOnWriteArrayList<>();
        CountDownLatch written = new CountDownLatch(50);

        StepVerifier.create(Mono.fromRunnable(() -> {
                    for (int index = 0; index < 50; index++) {
                        assertThat(dispatcher.submit(() -> {
                            threads.add(Thread.currentThread().getName());
                            written.countDown();
                        })).isTrue();
                    }
                }).subscribeOn(Schedulers.parallel()))
                .verifyComplete();

        assertThat(written.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(threads).containsOnly("ticketing-log-writer");
        dispatcher.submit(() -> {
            throw new IllegalStateException("a failing log call never stops the writer");
        });
        CountDownLatch after = new CountDownLatch(1);
        dispatcher.submit(after::countDown);
        assertThat(after.await(5, TimeUnit.SECONDS)).isTrue();
        dispatcher.close(Duration.ofSeconds(5));
        assertThat(dispatcher.submit(() -> { })).isFalse();
        assertThat(dispatcher.dropped()).isEqualTo(1);
    }

    @Test
    @DisplayName("CMP-018 IV-023 a full log queue drops and counts the record instead of blocking or growing")
    void fullQueueDrops() throws Exception {
        LogDispatcher dispatcher = LogDispatcher.start(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(1);
        dispatcher.submit(() -> {
            running.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(dispatcher.submit(() -> { })).isTrue();
        assertThat(dispatcher.pending()).isEqualTo(1);
        assertThat(dispatcher.submit(() -> { })).isFalse();
        assertThat(dispatcher.dropped()).isEqualTo(1);
        release.countDown();
        await().atMost(Duration.ofSeconds(5)).until(() -> dispatcher.pending() == 0);
        dispatcher.close();
        assertThatThrownBy(() -> LogDispatcher.start(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dispatcher.submit(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("CMP-018 a closed dispatcher still writes the records accepted before the close")
    void closeDrainsPending() {
        LogDispatcher dispatcher = LogDispatcher.start(10);
        List<Integer> done = new CopyOnWriteArrayList<>();
        for (int index = 0; index < 5; index++) {
            int value = index;
            dispatcher.submit(() -> done.add(value));
        }
        dispatcher.close(Duration.ofSeconds(5));
        assertThat(done).hasSize(5);
        Thread.currentThread().interrupt();
        dispatcher.close(Duration.ofMillis(1));
        assertThat(Thread.interrupted()).isTrue();
    }

    @Test
    @DisplayName("CMP-018 IV-007 structured records carry the event name and fields; disabled levels are not enqueued")
    void structuredRecords() throws Exception {
        LogDispatcher dispatcher = LogDispatcher.start(10);
        StructuredLog log = new StructuredLog(dispatcher);
        assertThat(log.info("test.info", "info").with("orderId", "o-1").with("absent", null)
                .with("state", Thread.State.NEW).write()).isTrue();
        assertThat(log.warn("test.warn", "warn").cause(new IllegalStateException("x")).write()).isTrue();
        assertThat(log.error("test.error", "error").write()).isTrue();
        Logger quiet = LoggerFactory.getLogger("com.nequi.ticketing.observability.quiet");
        ((ch.qos.logback.classic.Logger) quiet).setLevel(ch.qos.logback.classic.Level.ERROR);
        StructuredLog filtered = new StructuredLog(dispatcher, quiet);
        assertThat(filtered.debug("test.debug", "debug").write()).isFalse();
        assertThat(filtered.error("test.error", "error").with("eventId", "e-1").write()).isTrue();
        dispatcher.close(Duration.ofSeconds(5));
        assertThat(dispatcher.pending()).isZero();
    }

    @Test
    @DisplayName("ADR-037 W3C traceparent values are parsed, validated and formatted")
    void traceParents() {
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        String spanId = "00f067aa0ba902b7";
        TraceParents.TraceParent parsed = TraceParents.parse(" 00-" + traceId + "-" + spanId + "-01 ").orElseThrow();
        assertThat(parsed).isEqualTo(new TraceParents.TraceParent(traceId, spanId, true));
        assertThat(parsed.format()).isEqualTo("00-" + traceId + "-" + spanId + "-01");
        assertThat(TraceParents.parse("00-" + traceId + "-" + spanId + "-00").orElseThrow().sampled()).isFalse();
        assertThat(TraceParents.parse(null)).isEmpty();
        assertThat(TraceParents.parse("garbage")).isEmpty();
        assertThat(TraceParents.parse("ff-" + traceId + "-" + spanId + "-01")).isEmpty();
        assertThat(TraceParents.parse("00-" + "0".repeat(32) + "-" + spanId + "-01")).isEmpty();
        assertThat(TraceParents.parse("00-" + traceId + "-" + "0".repeat(16) + "-01")).isEmpty();
        assertThat(TraceParents.valid(traceId, spanId)).isTrue();
        assertThat(TraceParents.valid(null, spanId)).isFalse();
        assertThat(TraceParents.valid(traceId, null)).isFalse();
        assertThat(TraceParents.valid("abc", spanId)).isFalse();
        assertThat(TraceParents.valid(traceId, "0".repeat(16))).isFalse();
        assertThat(TraceParents.valid("0".repeat(32), spanId)).isFalse();
    }

    @Test
    @DisplayName("ADR-037 a span continues the trace of the message and its own traceparent reaches the body")
    void spansContinueTheMessageTrace() {
        SimpleTracer tracer = new SimpleTracer();
        Spans spans = new Spans(tracer);
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        String parent = "00-" + traceId + "-00f067aa0ba902b7-01";

        Mono<String> body = Mono.deferContextual(context -> Mono.just(TraceContext.from(context).orElse("none")));
        StepVerifier.create(spans.child("ticketing.test", Map.of("orderId", "o-1"), body)
                        .contextWrite(context -> TraceContext.with(context, parent)))
                .assertNext(seen -> assertThat(seen).isNotEqualTo(parent))
                .verifyComplete();
        assertThat(tracer.onlySpan().getName()).isEqualTo("ticketing.test");
        assertThat(tracer.onlySpan().getTags()).containsEntry("orderId", "o-1");
        assertThat(tracer.onlySpan().getEndTimestamp()).isNotNull();

        StepVerifier.create(spans.child("ticketing.failing", Map.of(), Mono.error(new IllegalStateException("x"))))
                .expectError(IllegalStateException.class)
                .verify();
        assertThat(tracer.lastSpan().getError()).isInstanceOf(IllegalStateException.class);

        StepVerifier.create(new Spans(Tracer.NOOP).child("noop", Map.of(), body)
                        .contextWrite(context -> TraceContext.with(context, parent)))
                .expectNext(parent)
                .verifyComplete();
        assertThat(Spans.traceParentOf(null)).isEmpty();
        assertThat(Spans.traceParentOf(Span.NOOP)).isEmpty();
    }

    @Test
    @DisplayName("ADR-037 a span with W3C identifiers exposes its traceparent and the HTTP server span is resolved from the exchange")
    void traceParentOfSpansAndHttpExchanges() {
        Span span = new FixedSpan("4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7", true);
        assertThat(Spans.traceParentOf(span)).contains("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        assertThat(Spans.traceParentOf(new FixedSpan("4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7", null)))
                .contains("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-00");

        ServerRequestObservationContext context = new ServerRequestObservationContext(
                MockServerHttpRequest.get("/x").build(), new MockServerHttpResponse(), new HashMap<>());
        TracingContext tracing = new TracingContext();
        tracing.setSpan(span);
        context.put(TracingContext.class, tracing);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(ServerRequestObservationContext.CURRENT_OBSERVATION_CONTEXT_ATTRIBUTE, context);
        assertThat(HttpTraceResolver.traceParent(attributes))
                .contains("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        assertThat(HttpTraceResolver.traceParent(new HashMap<>())).isEmpty();
        assertThat(Observation.NOOP).isNotNull();
        assertThat(ObservationRegistry.NOOP).isNotNull();
        assertThat(Optional.empty()).isEmpty();
        assertThat(Builder.NOOP).isNotNull();
    }

    /** Span with fixed W3C identifiers (the OpenTelemetry bridge produces this shape). */
    private record FixedSpan(String traceId, String spanId, Boolean sampled) implements Span {

        @Override
        public boolean isNoop() {
            return false;
        }

        @Override
        public io.micrometer.tracing.TraceContext context() {
            return new io.micrometer.tracing.TraceContext() {
                @Override
                public String traceId() {
                    return traceId;
                }

                @Override
                public String parentId() {
                    return null;
                }

                @Override
                public String spanId() {
                    return spanId;
                }

                @Override
                public Boolean sampled() {
                    return sampled;
                }
            };
        }

        @Override
        public Span start() {
            return this;
        }

        @Override
        public Span name(String name) {
            return this;
        }

        @Override
        public Span event(String value) {
            return this;
        }

        @Override
        public Span event(String value, long time, TimeUnit timeUnit) {
            return this;
        }

        @Override
        public Span tag(String key, String value) {
            return this;
        }

        @Override
        public Span error(Throwable throwable) {
            return this;
        }

        @Override
        public void end() {
        }

        @Override
        public void end(long time, TimeUnit timeUnit) {
        }

        @Override
        public void abandon() {
        }

        @Override
        public Span remoteServiceName(String remoteServiceName) {
            return this;
        }

        @Override
        public Span remoteIpAndPort(String ip, int port) {
            return this;
        }
    }
}
