package com.nequi.ticketing.bootstrap.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import java.net.InetSocketAddress;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;

/**
 * Security of the application port outside the API (ADR-032, ADR-037): only the liveness and readiness groups
 * ({@code /livez}, {@code /readyz}, the health endpoint registered in the load balancer) are public; in the
 * {@code worker} role every other path is denied, and in the {@code api} role the adapter's chain denies every
 * path outside the six operations. Metrics and management live on the internal management port.
 */
@Configuration(proxyBeanMethods = false)
public class HealthSecurityConfiguration {

    static final String[] HEALTH_PATHS = {"/livez", "/readyz"};

    /**
     * Internal management port (ADR-032, ADR-037): not registered in the load balancer and exposing only health,
     * info, metrics and the Prometheus endpoint; the security chains of the application port do not apply to it.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityWebFilterChain managementPortChain(ServerHttpSecurity http, Environment environment) {
        ServerWebExchangeMatcher managementPort = exchange -> {
            Integer port = environment.getProperty("local.management.port", Integer.class);
            InetSocketAddress local = exchange.getRequest().getLocalAddress();
            return port != null && local != null && local.getPort() == port
                    ? ServerWebExchangeMatcher.MatchResult.match()
                    : ServerWebExchangeMatcher.MatchResult.notMatch();
        };
        return http.securityMatcher(managementPort)
                .authorizeExchange(exchanges -> exchanges.anyExchange().permitAll())
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .requestCache(ServerHttpSecurity.RequestCacheSpec::disable)
                .build();
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    SecurityWebFilterChain healthSecurityChain(ServerHttpSecurity http) {
        return http.securityMatcher(ServerWebExchangeMatchers.pathMatchers(HEALTH_PATHS))
                .authorizeExchange(exchanges -> exchanges.anyExchange().permitAll())
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .requestCache(ServerHttpSecurity.RequestCacheSpec::disable)
                .build();
    }

    /** {@code worker} role: no API on the application port. */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    @ConditionalOnProperty(name = "ticketing.role", havingValue = "worker")
    SecurityWebFilterChain workerDenyAllChain(ServerHttpSecurity http) {
        return http.authorizeExchange(exchanges -> exchanges.anyExchange().denyAll())
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .requestCache(ServerHttpSecurity.RequestCacheSpec::disable)
                .build();
    }
}
