package com.nequi.ticketing.infrastructure.observability;

import com.nequi.ticketing.application.port.in.DispositionReason;
import com.nequi.ticketing.application.port.in.MessageDisposition;
import com.nequi.ticketing.application.port.in.ProcessOrderCommand;
import com.nequi.ticketing.application.port.in.ProcessOrderUseCase;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import reactor.core.publisher.Mono;

/**
 * Process Order (CMP-007) inside the span {@code ticketing.order.process}, child of the trace of the purchase that
 * the message carries (ADR-037), counting each result by action and reason ({@code ALREADY_TERMINAL} = duplicate
 * or late delivery discarded, aws-target §7 "duplicados descartados"). The disposition is passed through
 * unchanged.
 */
final class ObservedProcessOrder implements ProcessOrderUseCase {

    static final String QUEUE = "orders";

    private final ProcessOrderUseCase delegate;
    private final Telemetry telemetry;

    ObservedProcessOrder(ProcessOrderUseCase delegate, Telemetry telemetry) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.telemetry = telemetry;
    }

    @Override
    public Mono<MessageDisposition> process(ProcessOrderCommand command) {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("orderId", String.valueOf(command.orderId()));
        tags.put("receiveCount", String.valueOf(command.delivery().receiveCount()));
        return telemetry.spans().child("ticketing.order.process", tags, delegate.process(command)
                .doOnNext(disposition -> {
                    Dispositions.count(telemetry, QUEUE, disposition);
                    if (disposition.reason() == DispositionReason.EXHAUSTED) {
                        telemetry.log().warn("order.processing.exhausted",
                                        "Order closed on the last reception after transient failures")
                                .with("orderId", command.orderId()).with("correlationId", command.correlationId())
                                .write();
                    }
                }));
    }
}
