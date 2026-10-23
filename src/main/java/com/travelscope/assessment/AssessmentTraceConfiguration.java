package com.travelscope.assessment;

import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import org.springframework.boot.actuate.autoconfigure.tracing.SdkTracerProviderBuilderCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The same SDK feeds PostgreSQL and Boot's existing OTLP exporter independently. */
@Configuration(proxyBeanMethods = false)
public class AssessmentTraceConfiguration {
    @Bean
    SdkTracerProviderBuilderCustomizer assessmentTraceCustomizer(
            @Value("${management.tracing.sampling.probability:0.1}") double probability) {
        var diagnostics = Sampler.parentBased(Sampler.traceIdRatioBased(probability));
        Sampler sampler = businessSampler(diagnostics);
        // Boot already registers SpanProcessor beans. Adding the bean again doubles every callback.
        return builder -> builder.setSampler(sampler);
    }

    static Sampler businessSampler(Sampler diagnostics) {
        return new Sampler() {
            public SamplingResult shouldSample(io.opentelemetry.context.Context parent, String traceId,
                    String name, io.opentelemetry.api.trace.SpanKind kind,
                    io.opentelemetry.api.common.Attributes attributes,
                    java.util.List<io.opentelemetry.sdk.trace.data.LinkData> links) {
                if (attributes.get(AssessmentTraceProcessor.key("run_id")) != null)
                    return SamplingResult.recordAndSample();
                return diagnostics.shouldSample(parent, traceId, name, kind, attributes, links);
            }
            public String getDescription() { return "TravelScope business spans always sampled"; }
        };
    }
}
