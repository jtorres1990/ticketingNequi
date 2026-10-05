package com.nequi.ticketing.bootstrap.config;

import com.nequi.ticketing.application.port.out.Clock;
import com.nequi.ticketing.application.port.out.PaymentGateway;
import com.nequi.ticketing.application.usecase.EnqueueRepublishService;
import com.nequi.ticketing.application.usecase.EventProvisioningService;
import com.nequi.ticketing.application.usecase.OrderProcessingService;
import com.nequi.ticketing.application.usecase.PaymentReversalService;
import com.nequi.ticketing.application.usecase.ProvisioningCleanupService;
import com.nequi.ticketing.application.usecase.ReservationExpirationService;
import com.nequi.ticketing.application.usecase.WorkerUseCaseSettings;
import com.nequi.ticketing.infrastructure.adapter.in.scheduler.WorkerScheduler;
import com.nequi.ticketing.infrastructure.adapter.in.sqs.CircuitGateBinding;
import com.nequi.ticketing.infrastructure.adapter.in.sqs.SqsConsumers;
import com.nequi.ticketing.infrastructure.adapter.in.sqs.SwitchableConsumptionGate;
import com.nequi.ticketing.infrastructure.adapter.out.payment.PaymentMockGateway;
import com.nequi.ticketing.infrastructure.observability.Telemetry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Composition of the {@code worker} role (CMP-021): the use cases of CMP-007, CMP-008, CMP-015, CMP-022 to CMP-024,
 * the Payment gateway with its circuit (CMP-013, CMP-026), the two consumer loops (CMP-012, CMP-025), the periodic
 * scheduler (CMP-014) and the gates of the Orders loop and of the reversals bound to the Payment Mock circuit
 * ({@link CircuitGateBinding}). {@link WorkerRuntime} starts and stops them in order (ADR-037).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "ticketing.role", havingValue = "worker")
public class WorkerRoleConfiguration {

    @Bean
    WorkerUseCaseSettings workerUseCaseSettings(SettingsFactory settings) {
        return settings.workerUseCases(settings.workerId());
    }

    @Bean
    PaymentMockGateway paymentMockGateway(SettingsFactory settings, Clock clock, Telemetry telemetry) {
        PaymentMockGateway gateway = PaymentMockGateway.create(settings.paymentGateway(), clock,
                telemetry.paymentEvents());
        telemetry.bindCircuit("payment-mock", gateway.circuit());
        return gateway;
    }

    @Bean
    WorkerRuntime workerRuntime(SettingsFactory settings, TicketingPorts ports, PaymentMockGateway gateway,
            Clock clock, WorkerUseCaseSettings useCases, Telemetry telemetry) {
        PaymentGateway payments = telemetry.observePayments(gateway);
        SwitchableConsumptionGate ordersGate = new SwitchableConsumptionGate();
        SwitchableConsumptionGate reversalsGate = new SwitchableConsumptionGate();
        CircuitGateBinding binding = CircuitGateBinding.bind(gateway.circuit(), ordersGate, reversalsGate);

        OrderProcessingService processing = new OrderProcessingService(ports.orderReader(), ports.lifecycleStore(),
                payments, clock, useCases);
        EventProvisioningService provisioning = new EventProvisioningService(ports.eventCatalog(),
                ports.ticketInventory(), clock, useCases);
        SqsConsumers consumers = SqsConsumers.open(settings.sqsConnection(), settings.ordersConsumer(),
                telemetry.observeOrderProcessing(processing), ordersGate, settings.provisioningConsumer(),
                telemetry.observeProvisioning(provisioning), telemetry.sqsEvents());

        WorkerScheduler scheduler = WorkerScheduler.create(
                new ReservationExpirationService(ports.orderReader(), ports.lifecycleStore(), clock, useCases),
                new EnqueueRepublishService(ports.orderReader(), ports.lifecycleStore(), ports.orderPublisher(), clock,
                        useCases),
                new PaymentReversalService(ports.orderReader(), ports.lifecycleStore(), payments, clock, useCases),
                new ProvisioningCleanupService(ports.eventCatalog(), ports.ticketInventory(),
                        ports.provisioningPublisher(), clock, useCases),
                reversalsGate,
                settings.scheduler(),
                telemetry.schedulerEvents());
        return new WorkerRuntime(consumers, scheduler, binding);
    }
}
