package com.travelscope.assessment;

import java.util.UUID;
/** Immutable association, copied explicitly through RuntimeContext. Never resolved by session recency. */
public record ObservationContext(UUID runId, UUID turnId, String traceId, UUID parentId, UUID sampleId, String purpose) {
    public ObservationContext(UUID runId,UUID turnId,String traceId,UUID parentId,UUID sampleId){this(runId,turnId,traceId,parentId,sampleId,"PLANNING");}
    public ObservationContext withPurpose(String purpose){return new ObservationContext(runId,turnId,traceId,parentId,sampleId,purpose);}
    public static final String KEY = "assessment.observation";
    public ObservationContext child(UUID spanId) { return new ObservationContext(runId, turnId, traceId, spanId, sampleId,purpose); }
}
