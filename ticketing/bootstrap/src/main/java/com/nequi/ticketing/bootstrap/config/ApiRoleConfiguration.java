package com.nequi.ticketing.bootstrap.config;

import com.nequi.ticketing.application.port.in.StartPurchaseUseCase;
import com.nequi.ticketing.application.port.out.Clock;
import com.nequi.ticketing.application.port.out.IdGenerator;
import com.nequi.ticketing.application.usecase.ApiUseCaseSettings;
import com.nequi.ticketing.application.usecase.EventCatalogService;
import com.nequi.ticketing.application.usecase.EventManagementService;
import com.nequi.ticketing.application.usecase.OrderQueryService;
import com.nequi.ticketing.application.usecase.PurchaseService;
import com.nequi.ticketing.infrastructure.adapter.in.web.AccessTokenSettings;
import com.nequi.ticketing.infrastructure.adapter.in.web.AccessTokens;
import com.nequi.ticketing.infrastructure.adapter.in.web.ApiExceptionHandler;
import com.nequi.ticketing.infrastructure.adapter.in.web.ApiProblems;
import com.nequi.ticketing.infrastructure.adapter.in.web.ApiRoutes;
import com.nequi.ticketing.infrastructure.adapter.in.web.ApiSecurity;
import com.nequi.ticketing.infrastructure.adapter.in.web.RequestBodyLimitFilter;
import com.nequi.ticketing.infrastructure.adapter.in.web.SubjectRateLimiter;
import com.nequi.ticketing.infrastructure.adapter.in.web.TracePropagationFilter;
import com.nequi.ticketing.infrastructure.adapter.in.web.WebApiSettings;
import com.nequi.ticketing.infrastructure.adapter.in.web.WebApiUseCases;
import com.nequi.ticketing.infrastructure.adapter.sqs.TraceContext;
import com.nequi.ticketing.infrastructure.observability.HttpTraceResolver;
import com.nequi.ticketing.infrastructure.observability.Telemetry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.http.codec.CodecCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.WebExceptionHandler;
import org.springframework.web.server.WebFilter;

/**
 * Composition of the {@code api} role (CMP-021): the use cases of CMP-003 to CMP-006 over the outbound ports and
 * the HTTP adapter of INC-008 (CMP-001, CMP-002, CMP-016, CMP-017) on the application port under {@code /api/v1}.
 * Filter order: trace propagation (CMP-018), body limit of 256 KB before any other processing (ADR-032), then the
 * security chains; the adapter's exception handler precedes the error handler of Spring Boot.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "ticketing.role", havingValue = "api")
public class ApiRoleConfiguration {

    @Bean
    ApiUseCaseSettings apiUseCaseSettings(SettingsFactory settings) {
        return settings.apiUseCases();
    }

    @Bean
    WebApiSettings webApiSettings(SettingsFactory settings) {
        return settings.web();
    }

    @Bean
    AccessTokenSettings accessTokenSettings(SettingsFactory settings) {
        return settings.accessTokens();
    }

    @Bean
    WebApiUseCases webApiUseCases(TicketingPorts ports, Clock clock, IdGenerator ids, ApiUseCaseSettings settings,
            Telemetry telemetry) {
        EventManagementService management = new EventManagementService(ports.eventCatalog(), ports.idempotencyStore(),
                ports.provisioningPublisher(), clock, ids, settings);
        EventCatalogService catalog = new EventCatalogService(ports.eventCatalog(), ports.ticketInventory(), clock,
                settings);
        StartPurchaseUseCase purchases = telemetry.observePurchases(new PurchaseService(ports.eventCatalog(),
                ports.lifecycleStore(), ports.orderReader(), ports.idempotencyStore(), ports.orderPublisher(), clock,
                ids, settings));
        return new WebApiUseCases(management, catalog, management, telemetry.observeAvailability(catalog), purchases,
                new OrderQueryService(ports.orderReader()));
    }

    @Bean
    ApiProblems apiProblems(WebApiSettings settings, Telemetry telemetry) {
        return new ApiProblems(settings, telemetry.webApiEvents());
    }

    /** The adapter's own decoder (ADR-032): issuer and JWK set URL configured separately, validators of ADR-033. */
    @Bean
    ReactiveJwtDecoder accessTokenDecoder(AccessTokenSettings tokens) {
        return AccessTokens.decoder(tokens, java.time.Clock.systemUTC());
    }

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    SecurityWebFilterChain apiSecurityChain(ServerHttpSecurity http, WebApiSettings settings,
            AccessTokenSettings tokens, ReactiveJwtDecoder decoder, ApiProblems problems) {
        return ApiSecurity.securityWebFilterChain(http, settings, tokens, decoder, problems);
    }

    @Bean
    RouterFunction<ServerResponse> apiRoutes(WebApiSettings settings, WebApiUseCases useCases, ApiProblems problems,
            Telemetry telemetry) {
        return ApiRoutes.routes(settings, useCases, problems, new SubjectRateLimiter(settings), telemetry.webApiEvents());
    }

    @Bean
    WebFilter tracePropagationFilter() {
        return new OrderedWebFilter(Ordered.HIGHEST_PRECEDENCE,
                new TracePropagationFilter(HttpTraceResolver::traceParent, TraceContext::with));
    }

    @Bean
    WebFilter requestBodyLimitFilter(WebApiSettings settings, ApiProblems problems) {
        return new OrderedWebFilter(Ordered.HIGHEST_PRECEDENCE + 1, new RequestBodyLimitFilter(settings, problems));
    }

    @Bean
    @Order(-2)
    WebExceptionHandler apiExceptionHandler(ApiProblems problems) {
        return new ApiExceptionHandler(problems);
    }

    /** Every codec buffers at most the request body limit (256 KB, ADR-032), as in the adapter's own strategies. */
    @Bean
    CodecCustomizer requestBodyCodecLimit(WebApiSettings settings) {
        return configurer -> configurer.defaultCodecs().maxInMemorySize(settings.maximumBodyBytes());
    }
}
