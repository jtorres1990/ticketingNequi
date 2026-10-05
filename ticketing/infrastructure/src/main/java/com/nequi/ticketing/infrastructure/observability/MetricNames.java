package com.nequi.ticketing.infrastructure.observability;

/**
 * Catalog of the metrics produced by the application (CMP-018, {@code ticketing.aws-target.v2.md} §7, ADR-037
 * alarm catalog). The Prometheus endpoint renders the dots as underscores and adds {@code _total} to counters
 * and {@code _seconds_*} to timers. Queue depth and age of the oldest message of the queues and DLQs, and the
 * resources of the tasks, are service metrics of SQS and of the platform; the JVM, process and HTTP server
 * metrics ({@code http.server.requests}) are the standard ones of Micrometer and Spring Boot.
 */
public final class MetricNames {

    /** Business: reservations by {@code outcome} ({@code created}, {@code replayed}, {@code rejected}) and {@code code}. */
    public static final String RESERVATIONS = "ticketing.reservations";
    /** Business: Orders that reached a terminal state, by {@code status} and {@code cause}. */
    public static final String ORDERS_TERMINAL = "ticketing.orders.terminal";
    /** Business: delay between {@code expiresAt} and the expiration that released the Tickets (ADR-028). */
    public static final String EXPIRATION_LAG = "ticketing.orders.expiration.lag";
    /** Business: expirations whose lag exceeded the configured threshold (alarm "retraso de expiración > 15 s"). */
    public static final String EXPIRATION_LAG_EXCEEDED = "ticketing.orders.expiration.lag.exceeded";
    /** Business: Orders expired without a PaymentAttempt. */
    public static final String EXPIRED_WITHOUT_PAYMENT = "ticketing.orders.expired.without.payment";
    /** Business: Orders put in quarantine (alarm "Orders en cuarentena > 0"). */
    public static final String ORDERS_QUARANTINED = "ticketing.orders.quarantined";
    /** Business: late approvals recorded on Orders already closed (AP-032). */
    public static final String LATE_APPROVALS = "ticketing.orders.late.approvals";
    /** Business: payment reversals by {@code outcome} ({@code requested}, {@code confirmed}, {@code rescheduled}, {@code exhausted}). */
    public static final String PAYMENT_REVERSALS = "ticketing.payment.reversals";
    /** Business: duration of the Payment Mock operations by {@code operation} and {@code outcome}. */
    public static final String PAYMENT_DURATION = "ticketing.payment.duration";
    /** Technical: failed Payment Mock calls by {@code operation} and {@code reason}. */
    public static final String PAYMENT_CALL_FAILURES = "ticketing.payment.call.failures";
    /** Technical: Payment Mock operations without a provider result by {@code operation} and {@code reason}. */
    public static final String PAYMENT_UNAVAILABLE = "ticketing.payment.unavailable";
    /** Business: processed messages by {@code queue}, {@code action} and {@code reason} (duplicates: {@code ALREADY_TERMINAL}). */
    public static final String MESSAGES_PROCESSED = "ticketing.messages.processed";
    /** Business: provisioning by {@code outcome} ({@code started}, {@code enabled}, {@code failed}, {@code republished}). */
    public static final String PROVISIONING = "ticketing.provisioning";
    /** Business: duration of the provisioning message run that enabled the Event. */
    public static final String PROVISIONING_DURATION = "ticketing.provisioning.duration";
    /** Technical: cycles of the periodic processes by {@code process} and {@code outcome}. */
    public static final String SCHEDULER_CYCLES = "ticketing.scheduler.cycles";
    /** Technical: duration of the cycles of the periodic processes by {@code process}. */
    public static final String SCHEDULER_CYCLE_DURATION = "ticketing.scheduler.cycle.duration";
    /** Business: candidates handled by the periodic processes by {@code process} and {@code outcome}. */
    public static final String SCHEDULER_ITEMS = "ticketing.scheduler.items";
    /** Technical: triggers skipped by overlap ({@code reason=overlap}) or pause ({@code reason=paused}). */
    public static final String SCHEDULER_TRIGGERS_SKIPPED = "ticketing.scheduler.triggers.skipped";
    /** Technical (CMP-026): 1 for the current state of each circuit, by {@code circuit} and {@code state}. */
    public static final String CIRCUIT_STATE = "ticketing.circuit.state";
    /** Technical (CMP-026): circuit transitions by {@code circuit} and {@code to} (alarm on {@code OPEN}). */
    public static final String CIRCUIT_TRANSITIONS = "ticketing.circuit.transitions";
    /** Technical: SQS adapter failures by {@code queue} and {@code kind}. */
    public static final String SQS_FAILURES = "ticketing.sqs.failures";
    /** Technical: DynamoDB requests by {@code operation} and {@code outcome} (latency, throttling, conflicts). */
    public static final String DYNAMODB_REQUESTS = "ticketing.dynamodb.requests";
    /** Technical: HTTP requests that ended with {@code INTERNAL_ERROR}. */
    public static final String HTTP_UNCLASSIFIED = "ticketing.http.unclassified.failures";
    /** Technical: API-004 requests rejected by the per-subject rate limiter. */
    public static final String HTTP_RATE_LIMITED = "ticketing.http.rate.limited";
    /** Technical: availability queries (API-003). */
    public static final String AVAILABILITY_REQUESTS = "ticketing.availability.requests";
    /** Technical: available-count queries sent to the store; hit ratio of the 1 s cache = 1 - queries / requests. */
    public static final String AVAILABILITY_COUNT_QUERIES = "ticketing.availability.count.queries";
    /** Technical: structured log records dropped by a full log queue. */
    public static final String LOG_DROPPED = "ticketing.log.dropped";
    /** Technical: structured log records waiting to be written. */
    public static final String LOG_PENDING = "ticketing.log.pending";

    private MetricNames() {
    }
}
