package com.travelscope.assessment;
import io.agentscope.core.model.*;
import io.agentscope.core.message.Msg;
import reactor.core.publisher.Flux;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
/** Wrap raw providers, below the gateway: one span per actual subscription / attempt. */
public final class ObservedModel implements Model {
    private final Model delegate; private final ObservationStore store;
    public ObservedModel(Model delegate,ObservationStore store) { this.delegate=delegate; this.store=store; }
    public Flux<ChatResponse> stream(List<Msg> messages,List<ToolSchema> tools,GenerateOptions options) {
        return Flux.deferContextual(reactorContext -> {
            ObservationContext ctx=reactorContext.getOrDefault(ObservationContext.KEY,null);
            if(ctx==null || !store.enabled()) return delegate.stream(messages,tools,options);
            String actualModel=options!=null&&options.getModelName()!=null?options.getModelName():delegate.getModelName();
            var span=store.span(ctx,"MODEL",actualModel,null,messages);
            AttemptContext attempt=reactorContext.getOrDefault(AttemptContext.KEY,null);
            if(attempt!=null){int index=attempt.next().incrementAndGet();store.write(ctx.runId(),()->store.attempt(span.id,attempt.group(),index));}
            UsageSnapshot usage=new UsageSnapshot();
            AtomicReference<String> request=new AtomicReference<>();
            java.util.List<ChatResponse> output=new java.util.ArrayList<>();
            AtomicReference<Throwable> error=new AtomicReference<>();
            return Flux.defer(()->delegate.stream(messages,tools,options))
                .doOnNext(r->{ usage.accept(r.getUsage());
                    if(r.getMetadata()!=null && r.getMetadata().get("request_id")!=null) request.set(String.valueOf(r.getMetadata().get("request_id")));
                    output.add(r); })
                .doOnComplete(()->{span.usage(actualModel,usage.value(),request.get());span.end("SUCCEEDED",output,null);})
                .doOnError(e->{error.set(e);span.usage(actualModel,usage.value(),request.get());span.end("FAILED",output,e);})
                .doFinally(signal->{ if(signal==reactor.core.publisher.SignalType.CANCEL){span.usage(actualModel,usage.value(),request.get());span.end("CANCELLED",output,error.get());} });
        });
    }
    public String getModelName(){return delegate.getModelName();}
    public boolean supportsNativeStructuredOutput(){return delegate.supportsNativeStructuredOutput();}
    public boolean supportsNativeStructuredOutputWithTools(){return delegate.supportsNativeStructuredOutputWithTools();}
    public int getContextWindowSize(){return delegate.getContextWindowSize();}
}
