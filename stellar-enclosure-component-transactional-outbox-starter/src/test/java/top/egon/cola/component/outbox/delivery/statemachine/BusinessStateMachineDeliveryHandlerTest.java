package top.egon.cola.component.outbox.delivery.statemachine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import top.egon.cola.component.common.core.validation.ValidationUtils;
import top.egon.cola.component.outbox.autoconfigure.OutboxStateMachineProperties;
import top.egon.cola.component.outbox.delivery.DeliveryContext;
import top.egon.cola.component.outbox.delivery.DeliveryResult;
import top.egon.cola.component.outbox.statemachine.BusinessStateMachineDefinitionStrategy;
import top.egon.cola.component.outbox.statemachine.BusinessStateMachineEvent;
import top.egon.cola.component.outbox.statemachine.BusinessStateMachineService;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BusinessStateMachineDeliveryHandlerTest {

    private static final String DESTINATION = "order-payment:1";
    private static final Instant OCCURRED_AT = Instant.parse("2026-09-25T00:00:00Z");
    private static ValidatorFactory validatorFactory;

    @BeforeAll
    static void createValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @Test
    void shouldValidateExactRegisteredDestination() {
        BusinessStateMachineDefinitionStrategy definition = mock(BusinessStateMachineDefinitionStrategy.class);
        when(definition.destination()).thenReturn(DESTINATION);
        BusinessStateMachineDeliveryHandler handler = handler(
                mock(BusinessStateMachineService.class),
                Map.of(DESTINATION, definition));

        handler.validateDestination(DESTINATION);
    }

    @Test
    void shouldDeserializeAndDelegateAValidBusinessEvent() throws Exception {
        BusinessStateMachineService service = mock(BusinessStateMachineService.class);
        BusinessStateMachineEvent event = event();
        DeliveryContext context = context(event, mapper().writeValueAsString(event));
        when(service.process(any(BusinessStateMachineEvent.class), any(DeliveryContext.class)))
                .thenReturn(DeliveryResult.success());
        BusinessStateMachineDefinitionStrategy definition = mock(BusinessStateMachineDefinitionStrategy.class);
        when(definition.destination()).thenReturn(DESTINATION);
        BusinessStateMachineDeliveryHandler handler = handler(service, Map.of(DESTINATION, definition));

        DeliveryResult result = handler.deliver(context);

        assertThat(result).isEqualTo(DeliveryResult.success());
        org.mockito.ArgumentCaptor<BusinessStateMachineEvent> eventCaptor =
                org.mockito.ArgumentCaptor.forClass(BusinessStateMachineEvent.class);
        verify(service).process(eventCaptor.capture(), any(DeliveryContext.class));
        assertThat(eventCaptor.getValue().getEventId()).isEqualTo(event.getEventId());
        assertThat(eventCaptor.getValue().getPayload()).isEqualTo(event.getPayload());
    }

    @Test
    void shouldRejectHeaderBodyIdentityMismatchBeforeCallingService() throws Exception {
        BusinessStateMachineService service = mock(BusinessStateMachineService.class);
        BusinessStateMachineEvent event = event();
        DeliveryContext valid = context(event, mapper().writeValueAsString(event));
        DeliveryContext mismatch = new DeliveryContext(
                valid.messageId(), valid.channel(), valid.destination(), valid.payload(), valid.contentType(),
                valid.schemaVersion(), Map.of(
                        "egon-event-id", "different-event",
                        "egon-tenant-id", event.getTenantId().toString(),
                        "egon-machine-key", event.getMachineKey(),
                        "egon-definition-version", event.getDefinitionVersion().toString()),
                valid.traceId(), valid.attempt(), valid.maxAttempts(), valid.deadline());
        BusinessStateMachineDefinitionStrategy definition = mock(BusinessStateMachineDefinitionStrategy.class);
        when(definition.destination()).thenReturn(DESTINATION);
        BusinessStateMachineDeliveryHandler handler = handler(service, Map.of(DESTINATION, definition));

        DeliveryResult result = handler.deliver(mismatch);

        assertThat(result.kind()).isEqualTo(DeliveryResult.Kind.PERMANENT_FAILURE);
        assertThat(result.code()).isEqualTo("OUTBOX_SM_EVENT_INVALID");
        verify(service, never()).process(any(), any());
    }

    @Test
    void shouldRejectUnknownTopLevelJsonFields() throws Exception {
        BusinessStateMachineService service = mock(BusinessStateMachineService.class);
        BusinessStateMachineEvent event = event();
        String payload = mapper().writeValueAsString(event).replace("{", "{\"unknown\":true,");
        DeliveryContext context = context(event, payload);
        BusinessStateMachineDefinitionStrategy definition = mock(BusinessStateMachineDefinitionStrategy.class);
        when(definition.destination()).thenReturn(DESTINATION);
        BusinessStateMachineDeliveryHandler handler = handler(service, Map.of(DESTINATION, definition));

        DeliveryResult result = handler.deliver(context);

        assertThat(result.kind()).isEqualTo(DeliveryResult.Kind.PERMANENT_FAILURE);
        assertThat(result.code()).isEqualTo("OUTBOX_SM_EVENT_INVALID");
        verify(service, never()).process(any(), any());
    }

    @Test
    void shouldClassifyMissingDefinitionUsingConfiguredRetryabilityAndKeepStrictValidation() throws Exception {
        BusinessStateMachineService service = mock(BusinessStateMachineService.class);
        BusinessStateMachineEvent v2Event = BusinessStateMachineEvent.builder()
                .eventId("event-v2")
                .tenantId(7L)
                .machineKey("order-payment")
                .definitionVersion(2)
                .businessId("order-1")
                .eventType("PAYMENT_CONFIRMED")
                .expectedVersion(3L)
                .occurredAt(OCCURRED_AT)
                .payload(mapper().createObjectNode().put("paymentReference", "pay-1"))
                .build();
        DeliveryContext v2Context = new DeliveryContext(
                "transport-v2",
                BusinessStateMachineDeliveryHandler.CHANNEL,
                "order-payment:2",
                mapper().writeValueAsString(v2Event),
                "application/json",
                "1",
                Map.of(
                        "egon-event-id", v2Event.getEventId(),
                        "egon-tenant-id", v2Event.getTenantId().toString(),
                        "egon-machine-key", v2Event.getMachineKey(),
                        "egon-definition-version", v2Event.getDefinitionVersion().toString()),
                "trace-1",
                1,
                10,
                OCCURRED_AT.plusSeconds(30)
        );
        OutboxStateMachineProperties retryableProperties = new OutboxStateMachineProperties();
        BusinessStateMachineDeliveryHandler retryableHandler = new BusinessStateMachineDeliveryHandler(
                service,
                Map.of(),
                mapper(),
                new ValidationUtils(validatorFactory.getValidator()),
                retryableProperties
        );

        DeliveryResult retryableResult = retryableHandler.deliver(v2Context);
        assertThat(retryableResult.kind()).isEqualTo(DeliveryResult.Kind.RETRYABLE_FAILURE);
        assertThat(retryableResult.code()).isEqualTo("OUTBOX_SM_DEFINITION_MISSING");
        assertThatThrownBy(() -> retryableHandler.validateDestination("order-payment:2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("OUTBOX_SM_DEFINITION_MISSING");

        OutboxStateMachineProperties strictProperties = new OutboxStateMachineProperties();
        strictProperties.getBusiness().setRetryOnMissingDefinition(false);
        BusinessStateMachineDeliveryHandler strictHandler = new BusinessStateMachineDeliveryHandler(
                service,
                Map.of(),
                mapper(),
                new ValidationUtils(validatorFactory.getValidator()),
                strictProperties
        );
        DeliveryResult permanentResult = strictHandler.deliver(v2Context);
        assertThat(permanentResult.kind()).isEqualTo(DeliveryResult.Kind.PERMANENT_FAILURE);
        assertThat(permanentResult.code()).isEqualTo("OUTBOX_SM_DEFINITION_MISSING");
        verify(service, never()).process(any(), any());
    }

    private static BusinessStateMachineDeliveryHandler handler(
            BusinessStateMachineService service,
            Map<String, BusinessStateMachineDefinitionStrategy> definitions
    ) {
        return new BusinessStateMachineDeliveryHandler(
                service,
                definitions,
                mapper(),
                new ValidationUtils(validatorFactory.getValidator()));
    }

    private static BusinessStateMachineEvent event() {
        return BusinessStateMachineEvent.builder()
                .eventId("event-1")
                .tenantId(7L)
                .machineKey("order-payment")
                .definitionVersion(1)
                .businessId("order-1")
                .eventType("PAYMENT_CONFIRMED")
                .expectedVersion(3L)
                .occurredAt(OCCURRED_AT)
                .payload(mapper().createObjectNode().put("paymentReference", "pay-1"))
                .build();
    }

    private static DeliveryContext context(BusinessStateMachineEvent event, String payload) {
        return new DeliveryContext(
                "transport-1",
                BusinessStateMachineDeliveryHandler.CHANNEL,
                DESTINATION,
                payload,
                "application/json",
                "1",
                Map.of(
                        "egon-event-id", event.getEventId(),
                        "egon-tenant-id", event.getTenantId().toString(),
                        "egon-machine-key", event.getMachineKey(),
                        "egon-definition-version", event.getDefinitionVersion().toString()),
                "trace-1",
                1,
                10,
                OCCURRED_AT.plusSeconds(30)
        );
    }

    private static ObjectMapper mapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }
}
