package com.nequi.ticketing.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * CMP-021 Bootstrap: one executable for the two roles of the same image (TC-006, ADR-037), selected by
 * {@code ticketing.role} ({@code TICKETING_ROLE=api|worker}). The user-details and management-security
 * auto-configurations are excluded: the security of the application port is the adapter's own (ADR-032) and the
 * management port is internal and not registered in the load balancer (ADR-037).
 */
@SpringBootApplication(excludeName = {
        "org.springframework.boot.security.autoconfigure.ReactiveUserDetailsServiceAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.actuate.web.reactive.ReactiveManagementWebSecurityAutoConfiguration"
})
public class TicketingApplication {

    public static void main(String[] args) {
        SpringApplication.run(TicketingApplication.class, args);
    }
}
