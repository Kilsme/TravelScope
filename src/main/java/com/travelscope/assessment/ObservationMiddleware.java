package com.travelscope.assessment;
import io.agentscope.core.agent.*;
import io.agentscope.core.middleware.*;
import io.agentscope.core.event.*;
import reactor.core.publisher.Flux;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.springframework.stereotype.Component;
@Component
public class ObservationMiddleware implements MiddlewareBase {
    private final ObservationStore store;
    private final com.fasterxml.jackson.databind.ObjectMapper toolJson=new com.fasterxml.jackson.databind.ObjectMapper();
    public ObservationMiddleware(ObservationStore store){this.store=store;}
    public int order(){return -800;}
    public Flux<AgentEvent> onAgent(Agent agent,RuntimeContext ctx,AgentInput input,Function<AgentInput,Flux<AgentEvent>> next) {
        return Flux.defer(()->{
            String mode=ctx.get("assessment.toolMode");
            if("FIXTURE".equals(mode)&&agent.getToolkit()!=null)FixtureTools.install(agent.getToolkit());
            if(!store.enabled())return next.apply(input);
            ObservationContext association=ctx.get(ObservationContext.KEY);
            boolean own=association==null;
            if(own) association=store.begin(ctx.getSessionId(),null);
            var root=association;
            var handle=store.span(root,"AGENT",agent.getName(),null,input.msgs());
            // Each agent receives a copied RuntimeContext from the framework. Parent metadata is immutable.
            ctx.put(ObservationContext.KEY,handle.child());
            AtomicReference<Throwable> error=new AtomicReference<>();
            long started=System.nanoTime();
            return Flux.defer(()->next.apply(input))
                .doOnComplete(()->handle.end("SUCCEEDED",null,null))
                .doOnError(e->{error.set(e);handle.end("FAILED",null,e);})
                .doFinally(signal->{String status=signal==reactor.core.publisher.SignalType.CANCEL?"CANCELLED":error.get()!=null?"FAILED":"SUCCEEDED";
                    handle.end(status,null,error.get());
                    if(own)store.finish(root,status,(System.nanoTime()-started)/1e6,false);})
                .contextWrite(c->c.put(ObservationContext.KEY,handle.child()));
        });
    }
    public Flux<AgentEvent> onModelCall(Agent agent,RuntimeContext ctx,ModelCallInput input,Function<ModelCallInput,Flux<AgentEvent>> next) {
        ObservationContext association=ctx.get(ObservationContext.KEY);
        return Flux.defer(()->{
            if(association==null||!store.enabled())return next.apply(input);
            var logical=store.span(association,"MODEL_CALL",agent.getName(),null,input.options());
            var attempt=new AttemptContext();
            return Flux.defer(()->next.apply(input))
                .doOnComplete(()->logical.end("SUCCEEDED",null,null))
                .doOnError(e->logical.end("FAILED",null,e))
                .doFinally(signal->{if(signal==reactor.core.publisher.SignalType.CANCEL)logical.end("CANCELLED",null,null);})
                .contextWrite(c->c.put(ObservationContext.KEY,logical.child()).put(AttemptContext.KEY,attempt));
        });
    }
    public Flux<AgentEvent> onActing(Agent agent,RuntimeContext ctx,ActingInput input,Function<ActingInput,Flux<AgentEvent>> next) {
        return Flux.defer(()->{
            ObservationContext association=ctx.get(ObservationContext.KEY);
            if(association==null || !store.enabled())return next.apply(input);
            Map<String,ObservationStore.Handle> active=new ConcurrentHashMap<>();
            Map<String,StringBuilder> output=new ConcurrentHashMap<>();
            return next.apply(input).doOnNext(event->{
                if(event instanceof ToolResultStartEvent e) {
                    var call=input.toolCalls().stream().filter(t->Objects.equals(t.getId(),e.getToolCallId())).findFirst();
                    if(call.isEmpty())return;
                    active.computeIfAbsent(e.getToolCallId(),id->store.span(association,"TOOL",e.getToolCallName(),id,call.map(t->t.getInput()).orElse(null)));
                } else if(event instanceof ToolResultTextDeltaEvent e) {
                    output.computeIfAbsent(e.getToolCallId(),id->new StringBuilder()).append(e.getDelta());
                } else if(event instanceof ToolResultDataDeltaEvent e) {
                    var h=active.get(e.getToolCallId());if(h!=null)store.payload(association,h.id,"OUTPUT_DATA",e.getToolCallName(),e.getData());
                } else if(event instanceof ToolResultEndEvent e) {
                    var h=active.remove(e.getToolCallId());
                    if(h!=null){
                        StringBuilder text=output.remove(e.getToolCallId());
                        h.end(e.getState().name(),text,null);
                        if(e.getState()==io.agentscope.core.message.ToolResultState.SUCCESS){
                            input.toolCalls().stream().filter(call->Objects.equals(call.getId(),e.getToolCallId())).findFirst()
                                .ifPresent(call->captureArtifact(ctx,association,h.id,call,text==null?"":text.toString()));
                        }
                    }
                }
            }).doFinally(signal->active.values().forEach(h->h.end(signal==reactor.core.publisher.SignalType.CANCEL?"CANCELLED":"INTERRUPTED",null,null)));
        });
    }
    private void captureArtifact(RuntimeContext runtime,ObservationContext association,UUID operation,
            io.agentscope.core.message.ToolUseBlock call,String result){
        // AgentScope 2.0.3 FilesystemTool returns Error: text even when the framework call itself succeeds.
        // Match its success acknowledgement, not just ToolResultState.SUCCESS.
        try {
            result=result.strip();
            if(result.startsWith("\""))result=toolJson.readValue(result,String.class);
            if("write_file".equals(call.getName())&&result.startsWith("Written to ")){
                Object body=call.getInput().get("content");
                if(body instanceof String text)store.recordArtifactMutation(association,operation,result.substring("Written to ".length()).strip(),text);
            }else if("write_collab_file".equals(call.getName())&&result.startsWith("已写入 tasks/")){
                String path=result.substring("已写入 ".length()).strip();
                store.recordArtifactMutation(association,operation,path,ArtifactSnapshotReader.read(runtime,path));
            }else if("plan_route".equals(call.getName())&&result.startsWith("{")){
                String session=Objects.toString(call.getInput().get("sessionId"),"");
                if(!session.equals(runtime.getSessionId()))return;
                String path="tasks/"+session+"/route_plan.json";
                store.recordArtifactMutation(association,operation,path,ArtifactSnapshotReader.read(runtime,path));
            }else if("edit_file".equals(call.getName())&&result.startsWith("Edited ")){
                int suffix=result.lastIndexOf(" (");
                if(suffix<0)return;
                String path=result.substring("Edited ".length(),suffix);
                store.recordArtifactMutation(association,operation,path,ArtifactSnapshotReader.read(runtime,path));
            }
        }catch(Exception e){store.artifactCaptureFailed(association);}
    }

}
