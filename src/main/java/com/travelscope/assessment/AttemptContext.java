package com.travelscope.assessment;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
public record AttemptContext(UUID group,AtomicInteger next) {
    public static final String KEY="assessment.attempt";
    public AttemptContext(){this(UUID.randomUUID(),new AtomicInteger());}
}
