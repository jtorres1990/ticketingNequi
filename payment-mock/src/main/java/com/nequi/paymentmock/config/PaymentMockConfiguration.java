package com.nequi.paymentmock.config;

import com.nequi.paymentmock.application.ControlService;
import com.nequi.paymentmock.application.MockState;
import com.nequi.paymentmock.application.PaymentService;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/** Wiring of the framework-free application services, with an injectable clock and latency scheduler. */
@Configuration(proxyBeanMethods = false)
public class PaymentMockConfiguration {

    @Bean
    MockState mockState() {
        return new MockState();
    }

    @Bean
    Clock paymentMockClock() {
        return Clock.systemUTC();
    }

    /** Timer scheduler of the simulated latency (non-blocking; TC-003, PM-SPK-004). */
    @Bean
    Scheduler latencyScheduler() {
        return Schedulers.parallel();
    }

    @Bean
    ControlService controlService(MockState mockState) {
        return new ControlService(mockState);
    }

    @Bean
    PaymentService paymentService(MockState mockState, Scheduler latencyScheduler, Clock paymentMockClock) {
        return new PaymentService(mockState, latencyScheduler, paymentMockClock);
    }
}
