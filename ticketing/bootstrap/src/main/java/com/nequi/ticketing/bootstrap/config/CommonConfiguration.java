package com.nequi.ticketing.bootstrap.config;

import com.nequi.ticketing.application.port.out.Clock;
import com.nequi.ticketing.application.port.out.IdGenerator;
import com.nequi.ticketing.infrastructure.adapter.out.dynamodb.DynamoDbResources;
import com.nequi.ticketing.infrastructure.adapter.out.sqs.SqsPublication;
import com.nequi.ticketing.infrastructure.adapter.out.system.RandomUuidGenerator;
import com.nequi.ticketing.infrastructure.adapter.out.system.SystemClock;
import com.nequi.ticketing.infrastructure.observability.LogDispatcher;
import com.nequi.ticketing.infrastructure.observability.Telemetry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Composition shared by both roles (CMP-021): configuration, Clock and Id generator adapters, observability
 * (CMP-018) and the AWS resources both roles use (DynamoDB, SQS publication: the {@code api} publishes MSG-001
 * and MSG-002, the {@code worker} republishes both). The resources are closed after the ordered shutdown of the
 * lifecycle phase (ADR-037).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TicketingProperties.class)
public class CommonConfiguration {

    @Bean
    SettingsFactory settingsFactory(TicketingProperties properties) {
        SettingsFactory factory = new SettingsFactory(properties);
        factory.role();
        return factory;
    }

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock ticketingClock() {
        return new SystemClock();
    }

    @Bean
    @ConditionalOnMissingBean(IdGenerator.class)
    IdGenerator ticketingIdGenerator() {
        return new RandomUuidGenerator();
    }

    /** Destroyed after the beans that depend on it (Spring destroys dependents first): pending records are written. */
    @Bean(destroyMethod = "close")
    LogDispatcher logDispatcher(SettingsFactory settings) {
        return LogDispatcher.start(settings.observability().logQueueCapacity());
    }

    @Bean
    Telemetry telemetry(MeterRegistry registry, LogDispatcher dispatcher, SettingsFactory settings,
            ObjectProvider<Tracer> tracer) {
        return new Telemetry(registry, dispatcher, settings.observability(), tracer.getIfAvailable(() -> Tracer.NOOP));
    }

    @Bean(destroyMethod = "close")
    DynamoDbResources dynamoDbResources(SettingsFactory settings, Clock clock, Telemetry telemetry) {
        return DynamoDbResources.open(settings.dynamoDbConnection(), settings.dynamoDbAdapter(), clock,
                telemetry.dynamoDbEvents());
    }

    @Bean(destroyMethod = "close")
    SqsPublication sqsPublication(SettingsFactory settings, Clock clock, Telemetry telemetry) {
        SqsPublication publication = SqsPublication.open(settings.sqsConnection(), settings.sqsPublisher(), clock,
                telemetry.sqsEvents());
        telemetry.bindCircuit("sqs-publication", publication.publisher().circuit());
        return publication;
    }

    @Bean
    TicketingPorts ticketingPorts(DynamoDbResources dynamoDb, SqsPublication publication, Telemetry telemetry) {
        var persistence = dynamoDb.persistence();
        return new TicketingPorts(
                telemetry.observeCatalog(persistence.eventCatalog()),
                telemetry.observeInventory(persistence.ticketInventory()),
                telemetry.observeLifecycle(persistence.orderLifecycleStore()),
                persistence.orderReader(),
                persistence.idempotencyStore(),
                publication.publisher(),
                publication.publisher());
    }
}
