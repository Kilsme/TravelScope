package com.travelscope.assessment;

import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.actuate.autoconfigure.tracing.OpenTelemetryAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class AssessmentTraceBootTest {
    @Test void bootRegistersBusinessProcessorExactlyOnce() {
        var processor=mock(AssessmentTraceProcessor.class);
        when(processor.isStartRequired()).thenReturn(true);when(processor.isEndRequired()).thenReturn(true);
        when(processor.shutdown()).thenReturn(io.opentelemetry.sdk.common.CompletableResultCode.ofSuccess());
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                org.springframework.boot.actuate.autoconfigure.opentelemetry.OpenTelemetryAutoConfiguration.class,
                OpenTelemetryAutoConfiguration.class))
            .withUserConfiguration(AssessmentTraceConfiguration.class)
            .withBean(Resource.class,Resource::getDefault)
            .withBean(ObservationRegistry.class,ObservationRegistry::create)
            .withBean(AssessmentTraceProcessor.class,()->processor)
            .withPropertyValues("management.tracing.sampling.probability=0")
            .run(context->{
                assertNull(context.getStartupFailure());
                var sdk=context.getBean(SdkTracerProvider.class);
                var span=sdk.get("boot-integration").spanBuilder("one-business-call")
                    .setAttribute(AssessmentTraceProcessor.key("run_id"),java.util.UUID.randomUUID().toString()).startSpan();
                assertTrue(span.isRecording());span.end();
                verify(processor,times(1)).onStart(any(),any());
                verify(processor,times(1)).onEnd(any());
            });
    }
}
