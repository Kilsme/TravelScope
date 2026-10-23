package com.travelscope.assessment;

import com.travelscope.common.TurnCancellation;
import io.opentelemetry.api.trace.SpanContext;
import java.util.UUID;
/** Immutable native parent context propagated explicitly across Reactor and AgentScope threads. */
public record ObservationContext(UUID runId, UUID turnId, String traceId, UUID parentId, UUID sampleId,
                                 String purpose, SpanContext spanContext, TurnCancellation cancellation) {
    public ObservationContext(UUID runId,UUID turnId,String traceId,UUID parentId,UUID sampleId,String purpose,SpanContext spanContext){this(runId,turnId,traceId,parentId,sampleId,purpose,spanContext,null);}
    public ObservationContext(UUID runId,UUID turnId,String traceId,UUID parentId,UUID sampleId){this(runId,turnId,traceId,parentId,sampleId,"PLANNING",null);}
    public ObservationContext(UUID runId,UUID turnId,String traceId,UUID parentId,UUID sampleId,String purpose){this(runId,turnId,traceId,parentId,sampleId,purpose,null);}
    public ObservationContext withCancellation(TurnCancellation token){return new ObservationContext(runId,turnId,traceId,parentId,sampleId,purpose,spanContext,token);}
    public ObservationContext withPurpose(String purpose){return new ObservationContext(runId,turnId,traceId,parentId,sampleId,purpose,spanContext,cancellation);}
    public ObservationContext withSpanContext(SpanContext nativeParent){return new ObservationContext(runId,turnId,nativeParent.getTraceId(),parentId,sampleId,purpose,nativeParent,cancellation);}
    public static final String KEY="assessment.observation";
    public ObservationContext child(UUID spanId){return child(spanId,spanContext);}
    public ObservationContext child(UUID spanId,SpanContext nativeParent){return new ObservationContext(runId,turnId,nativeParent!=null&&nativeParent.isValid()?nativeParent.getTraceId():traceId,spanId,sampleId,purpose,nativeParent,cancellation);}
}
