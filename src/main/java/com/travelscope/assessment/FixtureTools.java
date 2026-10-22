package com.travelscope.assessment;
import io.agentscope.core.tool.*;
import io.agentscope.core.message.ToolResultBlock;
import reactor.core.publisher.Mono;
import java.util.*;
/** Wrap every external tool, including harness-added web/shell tools. File/state/delegation tools stay local. */
public final class FixtureTools {
    private FixtureTools(){}
    static boolean local(String name){return name.startsWith("agent_")||name.startsWith("task_")||Set.of(
        "read_file","write_file","edit_file","list_files","glob_files","grep_files","load_skill_through_path",
        "memory_get","memory_save","session_history","session_search","session_list",
        "get_missing_fields","update_requirement_state","ask_user","register_task_result","get_cached_task_result",
        "create_task_backlog","update_task_status","get_task_progress","planning_gate_hint","review_retry_hint",
        "retrieve_poi_candidates","plan_route","assemble_itinerary","write_collab_file").contains(name);}
    public static synchronized void install(Toolkit toolkit){
        for(String name:List.copyOf(toolkit.getToolNames())) {
            AgentTool delegate=toolkit.getTool(name);
            if(delegate==null||local(name)||delegate instanceof Wrapped)continue;
            toolkit.removeTool(name);toolkit.registerAgentTool(new Wrapped(delegate));
        }
    }
    private record Wrapped(AgentTool delegate) implements AgentTool {
        public String getName(){return delegate.getName();}
        public String getDescription(){return delegate.getDescription();}
        public Map<String,Object> getParameters(){return delegate.getParameters();}
        public Boolean getStrict(){return delegate.getStrict();}
        public Map<String,Object> getOutputSchema(){return delegate.getOutputSchema();}
        public boolean isReadOnly(){return delegate.isReadOnly();}
        public Mono<ToolResultBlock> callAsync(ToolCallParam param){
            String mode=param.getRuntimeContext()==null?null:param.getRuntimeContext().get("assessment.toolMode");
            if(!"FIXTURE".equals(mode))return delegate.callAsync(param);
            Boolean fail=param.getRuntimeContext().get("assessment.toolFailure");
            if(Boolean.TRUE.equals(fail))return Mono.just(ToolResultBlock.error("FIXTURE injected external tool failure"));
            String scenario=param.getRuntimeContext().get("assessment.fixtureCase");
            String weather="EC-017".equals(scenario)?"暴雨预警，户外行程不可行":"晴 20C";
            boolean closed="EC-016".equals(scenario), found=!"EC-015".equals(scenario);
            String body="{\"fixture\":true,\"tool\":\""+getName()+"\",\"weather\":\""+weather+"\",\"closed\":"+closed+",\"found\":"+found+",\"durationMinutes\":30,\"distanceMeters\":3000,\"price\":100,\"availability\":\"UNKNOWN\",\"note\":\"固定测试数据，不代表真实地点、票价或开放时间\"}";
            return Mono.just(ToolResultBlock.text(body));
        }
    }
}
