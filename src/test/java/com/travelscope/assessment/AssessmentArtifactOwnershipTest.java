package com.travelscope.assessment;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import io.agentscope.core.permission.*;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.core.publisher.Flux;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssessmentArtifactOwnershipTest {
    private static final String SESSION="conv-artifact";
    private static final String PLAN="tasks/"+SESSION+"/itinerary_draft.md";
    @TempDir Path workspace;
    private String lastToolOutput="";
    private String toolMode = "LIVE";
    private boolean useRealAssembly;
    private HarnessAgent agent(ObservationStore store,String tool,Map<String,Object> input) throws Exception {
        AtomicInteger calls=new AtomicInteger();
        Model model=new Model(){
            public String getModelName(){return "synthetic-artifact-model";}
            public Flux<ChatResponse> stream(List<Msg> messages,List<ToolSchema> schemas,GenerateOptions options){
                List<ContentBlock> content=tool!=null&&calls.getAndIncrement()==0
                    ?List.of(ToolUseBlock.builder().id(UUID.randomUUID().toString()).name(tool).input(input).content(new ObjectMapper().valueToTree(input).toString()).build())
                    :List.of(TextBlock.builder().text("synthetic complete").build());
                return Flux.just(ChatResponse.builder().content(content).build());
            }
        };
        var toolkit = new io.agentscope.core.tool.Toolkit();
        if (Set.of("write_collab_file", "plan_route").contains(Objects.toString(tool, "")) || useRealAssembly) {
            var writer = new com.travelscope.collab.CollabFileWriter(workspace.toString());
            var taskWorkspace = new com.travelscope.collab.TaskWorkspace(writer, new com.travelscope.collab.CollabFileReader());
            toolkit.registerTool(new com.travelscope.agent.tools.CollabTools(
                    mock(com.travelscope.service.retrieval.PoiRetrievalService.class),
                    mock(com.travelscope.service.route.RoutePlannerServiceImpl.class), taskWorkspace, writer,
                    new com.travelscope.service.ItineraryAssembler(),
                    mock(com.travelscope.service.TaskResultCache.class),
                    useRealAssembly ? assemblyModel() : mock(io.agentscope.core.model.Model.class),
                    mock(com.travelscope.agent.tools.AttractionTool.class)));
        }
        if (!useRealAssembly && Set.of("assemble_itinerary", "revise_poi_selection").contains(Objects.toString(tool, ""))) {
            toolkit.registerTool(new FakeAssemblyTools());
        }
        return HarnessAgent.builder().name("planning-agent").model(model).toolkit(toolkit).workspace(workspace.toString())
            .stateStore(new io.agentscope.core.state.InMemoryAgentStateStore())
            .permissionContext(PermissionContextState.builder().mode(PermissionMode.BYPASS).build())
            .disableDefaultWorkspaceSkills().disableShellTool().middlewares(List.of(new ObservationMiddleware(store))).maxIters(3).build();
    }
    private Model assemblyModel() {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("synthetic-copy-model");
        String copy = "{\"theme\":\"本轮 JSON 行程\",\"notes\":[],\"costs\":[],\"budget\":null,\"pendingConfirm\":[]}";
        when(model.stream(anyList(), anyList(), any())).thenReturn(Flux.just(
                ChatResponse.builder().content(List.of(TextBlock.builder().text(copy).build())).build()));
        return model;
    }
    private RuntimeContext runtime(ObservationContext observation){return RuntimeContext.builder().userId("synthetic-user").sessionId(SESSION).put(ObservationContext.KEY,observation).put("assessment.toolMode",toolMode).build();}
    private ObservationStore store(JdbcTemplate jdbc){var config=new AssessmentProperties();config.setEnabled(true);return new ObservationStore(jdbc,config,new PayloadRedactor(new ObjectMapper()));}
    private void run(HarnessAgent agent,ObservationContext observation){
        try {
            var ctx=runtime(observation);ctx.put(ArtifactSnapshotReader.FILESYSTEM_KEY,agent.getWorkspaceManager().getFilesystem());
            var events=agent.streamEvents(new io.agentscope.core.message.UserMessage("synthetic test"),ctx).collectList().block(Duration.ofSeconds(10));
            String diagnostic=events.stream().filter(e->e instanceof io.agentscope.core.event.ToolResultTextDeltaEvent).map(e->((io.agentscope.core.event.ToolResultTextDeltaEvent)e).getDelta()).collect(java.util.stream.Collectors.joining());
            lastToolOutput=diagnostic;
            assertFalse(events.stream().anyMatch(e->e instanceof io.agentscope.core.event.ToolResultEndEvent t&&t.getState()==ToolResultState.ERROR),diagnostic);
        }finally{agent.close();}
    }

    @Test void plannerExitAndOldFileAreInsufficient() throws Exception {
        var jdbc=mock(JdbcTemplate.class);var store=store(jdbc);
        try {
            var observation=store.begin(SESSION,null);var agent=agent(store,null,Map.of());
            assertTrue(agent.getWorkspaceManager().getFilesystem().write(runtime(observation),PLAN,"previous plan").isSuccess());
            run(agent,observation);assertNull(store.planningArtifact(observation));
            store.finish(observation,"PLANNING_COMPLETED",10,true);store.flush();
            var terminal=mockingDetails(jdbc).getInvocations().stream().filter(i->i.getArgument(0).toString().startsWith("UPDATE assessment_runs SET status=CASE")).findFirst().orElseThrow();
            assertEquals("INCOMPLETE",terminal.getArgument(1));assertEquals(false,terminal.getArgument(8));
        }finally{store.close();}
    }

    @Test void successfulWriteOfIdenticalContentBelongsToCurrentExecution() throws Exception {
        var jdbc=mock(JdbcTemplate.class);var store=store(jdbc);
        try {
            var observation=store.begin(SESSION,null);var agent=agent(store,"edit_file",Map.of("path",PLAN,"old_string","same plan","new_string","same plan","replace_all",false));
            assertTrue(agent.getWorkspaceManager().getFilesystem().write(runtime(observation),PLAN,"same plan").isSuccess());
            run(agent,observation);assertEquals("same plan",store.planningArtifact(observation),lastToolOutput);store.flush();
            var persisted=mockingDetails(jdbc).getInvocations().stream().filter(i->i.getArgument(0).toString().startsWith("INSERT INTO assessment_payloads")&&Arrays.asList(i.getArguments()).contains("ARTIFACT")).toList();
            assertEquals(1,persisted.size());assertEquals(observation.runId(),persisted.getFirst().getArgument(2));assertNotNull(persisted.getFirst().getArgument(3));
            store.finish(observation,"PLANNING_COMPLETED",10,true);
        }finally{store.close();}
    }

    @Test void multiTurnNoWriteDoesNotReusePreviousArtifactAndEditProducesNewSnapshot() throws Exception {
        var jdbc=mock(JdbcTemplate.class);var store=store(jdbc);UUID sample=UUID.randomUUID();
        try {
            var first=store.begin(SESSION,sample);run(agent(store,"write_file",Map.of("path",PLAN,"content","old requirement plan")),first);
            assertEquals("old requirement plan",store.planningArtifact(first),lastToolOutput);store.finish(first,"PLANNING_COMPLETED",10,true);
            var second=store.begin(SESSION,sample);run(agent(store,null,Map.of()),second);
            assertTrue(store.currentArtifacts(second).isEmpty());store.finish(second,"INCOMPLETE",10,false);
            var third=store.begin(SESSION,sample);run(agent(store,"edit_file",Map.of("path",PLAN,"old_string","old requirement","new_string","modified requirement","replace_all",false)),third);
            assertEquals("modified requirement plan",store.planningArtifact(third),lastToolOutput);store.finish(third,"PLANNING_COMPLETED",10,true);
        }finally{store.close();}
    }

    @Test void writeForAnotherConversationCannotCreateThisRunsArtifact() throws Exception {
        var jdbc=mock(JdbcTemplate.class);var store=store(jdbc);
        try {
            var observation=store.begin(SESSION,null);
            run(agent(store,"write_file",Map.of("path","tasks/another-conversation/itinerary_draft.md","content","other plan")),observation);
            assertNull(store.planningArtifact(observation));store.finish(observation,"INCOMPLETE",10,false);
        }finally{store.close();}
    }
    @Test void filesystemErrorTextWithFrameworkSuccessDoesNotProveAWrite() throws Exception {
        var jdbc=mock(JdbcTemplate.class);var store=store(jdbc);
        try {
            var observation=store.begin(SESSION,null);var agent=agent(store,"write_file",Map.of("path",PLAN,"content","replacement"));
            assertTrue(agent.getWorkspaceManager().getFilesystem().write(runtime(observation),PLAN,"previous plan").isSuccess());
            run(agent,observation);assertTrue(lastToolOutput.contains("already exists"));
            assertNull(store.planningArtifact(observation));store.finish(observation,"INCOMPLETE",10,false);
        }finally{store.close();}
    }

    @Test void realAssemblyInFixtureProducesOwnedValidatedMarkdownSnapshot() throws Exception {
        toolMode = "FIXTURE";
        useRealAssembly = true;
        var jdbc = mock(JdbcTemplate.class);
        var store = store(jdbc);
        var tasks = new com.travelscope.collab.TaskWorkspace(
                new com.travelscope.collab.CollabFileWriter(workspace.toString()),
                new com.travelscope.collab.CollabFileReader());
        var destination = new com.travelscope.collab.IntakeResult.FieldValue("杭州", true, null);
        var days = new com.travelscope.collab.IntakeResult.FieldValue("1", true, null);
        tasks.writeIntakeResult("synthetic-user", SESSION, new com.travelscope.collab.IntakeResult(
                SESSION, com.travelscope.collab.IntakeResult.Status.DONE, 0,
                new com.travelscope.collab.IntakeResult.RequirementFields(destination, days, null, null,
                        null, null, null, null), false, "test"));
        tasks.writeRoutePlan("synthetic-user", SESSION, new com.travelscope.collab.RoutePlan(
                List.of(new com.travelscope.collab.RoutePlan.RouteDay(1, null, List.of(
                        new com.travelscope.collab.RoutePlan.RoutePoi("固定测试景点", 120.16, 30.25,
                                "09:00", "10:00", null, null)), 0)),
                new com.travelscope.collab.RoutePlan.SolverMeta("FIXTURE", "FIXTURE", 0, List.of())));
        try {
            var observation = store.begin(SESSION, null);
            run(agent(store, "assemble_itinerary", Map.of("sessionId", SESSION)), observation);
            assertTrue(lastToolOutput.contains("已组装并写入 itinerary_draft.json"), lastToolOutput);
            String markdown = store.planningArtifact(observation);
            assertNotNull(markdown);
            assertTrue(markdown.contains("固定测试景点"), markdown);
            String raw = store.currentArtifacts(observation).get("itinerary_draft.json");
            assertNotNull(raw);
            var draft = new ObjectMapper().readTree(raw);
            assertEquals(4, draft.get("schemaVersion").asInt());
            assertEquals(markdown, draft.get("markdownBody").asText());
            assertEquals("09:00-10:00", draft.get("days").get(0).get("items").get(0).get("time").asText());
            store.finish(observation, "PLANNING_COMPLETED", 10, true);
            store.flush();
            var terminal = mockingDetails(jdbc).getInvocations().stream()
                    .filter(i -> i.getArgument(0).toString().startsWith("UPDATE assessment_runs SET status=CASE"))
                    .findFirst().orElseThrow();
            assertEquals("PLANNING_COMPLETED", terminal.getArgument(1));
            assertEquals(true, terminal.getArgument(8));
        } finally { store.close(); }
    }

    @Test void manualDraftWriteCannotCaptureOrCompletePlanning() throws Exception {
        var store = store(mock(JdbcTemplate.class));
        try {
            var observation = store.begin(SESSION, null);
            run(agent(store, "write_collab_file", Map.of("sessionId", SESSION,
                    "fileName", "itinerary_draft.json", "contentJson", DRAFT_BODY)), observation);
            assertTrue(lastToolOutput.contains("不接受手写"), lastToolOutput);
            assertNull(store.planningArtifact(observation));
            assertTrue(store.currentArtifacts(observation).isEmpty());
            store.finish(observation, "INCOMPLETE", 10, false);
        } finally { store.close(); }
    }

    @Test void jsonSelectionIsPerRunPrefersValidatedJsonAndNeverReusesOldFiles() throws Exception {
        var store = store(mock(JdbcTemplate.class));
        try {
            var run = store.begin(SESSION, null);
            store.recordArtifactMutation(run, UUID.randomUUID(), PLAN, "legacy version");
            store.recordArtifactMutation(run, UUID.randomUUID(), "tasks/" + SESSION + "/itinerary_draft.json",
                    "{\"schemaVersion\":4,\"days\":[],\"markdownBody\":\"JSON version\"}");
            assertEquals("JSON version", store.planningArtifact(run));
            store.finish(run, "PLANNING_COMPLETED", 10, true);
            var next = store.begin(SESSION, null);
            assertNull(store.planningArtifact(next));
            store.recordArtifactMutation(next, UUID.randomUUID(), "tasks/another/itinerary_draft.json",
                    "{\"schemaVersion\":4,\"days\":[],\"markdownBody\":\"other session\"}");
            assertNull(store.planningArtifact(next));
            store.recordArtifactMutation(next, UUID.randomUUID(), "tasks/" + SESSION + "/itinerary_draft.json", "{}");
            assertNull(store.planningArtifact(next));
            store.finish(next, "INCOMPLETE", 10, false);
        } finally { store.close(); }
    }

    @Test void routeCaptureUsesTheWrittenSessionAndDoesNotSnapshotAnotherSessionsOldFile() throws Exception {
        toolMode = "FIXTURE";
        var store = store(mock(JdbcTemplate.class));
        var tasks = new com.travelscope.collab.TaskWorkspace(
                new com.travelscope.collab.CollabFileWriter(workspace.toString()),
                new com.travelscope.collab.CollabFileReader());
        try {
            for (String session : List.of(SESSION, "another-session")) {
                var field = new com.travelscope.collab.IntakeResult.FieldValue("杭州", true, null);
                var days = new com.travelscope.collab.IntakeResult.FieldValue("1", true, null);
                tasks.writeIntakeResult("synthetic-user", session, new com.travelscope.collab.IntakeResult(
                        session, com.travelscope.collab.IntakeResult.Status.DONE, 0,
                        new com.travelscope.collab.IntakeResult.RequirementFields(field, days, null, null,
                                null, null, null, null), false, "test"));
                tasks.writePoiShortlist("synthetic-user", session, new com.travelscope.collab.PoiShortlist(
                        "杭州", "FIXTURE", List.of(new com.travelscope.collab.PoiShortlist.PoiItem(
                                "固定测试景点", 120.16, 30.25, 4.5, "09:00-18:00", 60, "FIXTURE", null))));
            }
            var first = store.begin(SESSION, null);
            run(agent(store, "plan_route", Map.of("sessionId", SESSION)), first);
            assertTrue(store.currentArtifacts(first).containsKey("route_plan.json"), lastToolOutput);
            store.finish(first, "INCOMPLETE", 10, false);
            var next = store.begin(SESSION, null);
            run(agent(store, "plan_route", Map.of("sessionId", "another-session")), next);
            assertFalse(lastToolOutput.contains("ERROR"), lastToolOutput);
            assertTrue(store.currentArtifacts(next).isEmpty());
            store.finish(next, "INCOMPLETE", 10, false);
        } finally { store.close(); }
    }

    // ---------- 组装/重选工具的草案捕获（代码落盘不经 write_collab_file 的盲区修复） ----------

    private static final String DRAFT_JSON = "tasks/" + SESSION + "/itinerary_draft.json";
    private static final String DRAFT_MARKDOWN = "# 西安一日\n正文";
    private static final String DRAFT_BODY = "{\"schemaVersion\":4,\"days\":[{\"day\":1,\"date\":\"2026-10-24\","
            + "\"theme\":\"古城\",\"items\":[{\"time\":\"09:00-10:00\",\"activity\":\"游览\",\"location\":\"钟楼\","
            + "\"cost\":\"¥30\",\"note\":\"\"}]}],\"budget\":{\"currency\":\"CNY\",\"totalEstimate\":\"100\","
            + "\"breakdown\":[]},\"notes\":[],\"pendingConfirm\":[],\"markdownBody\":\"# 西安一日\\n正文\"}";

    /** 假组装/重选工具：捕获分支只认工具名+返回前缀，草案文件由测试预写进工作区 */
    public static class FakeAssemblyTools {
        @io.agentscope.core.tool.Tool(description = "fake assemble_itinerary")
        public String assemble_itinerary(
                @io.agentscope.core.tool.ToolParam(name = "sessionId", description = "s") String sessionId) {
            return "已组装并写入 itinerary_draft.json（1 天，markdownBody 100 字符，代码渲染）。"
                    + "核对本摘要无误后直接进入第 5 步一行收尾。";
        }

        @io.agentscope.core.tool.Tool(description = "fake revise_poi_selection")
        public String revise_poi_selection(
                @io.agentscope.core.tool.ToolParam(name = "sessionId", description = "s") String sessionId) {
            return "POI 修订完成：新增 [网师园]，删除 []；已重排路线并重组装 itinerary_draft.json，请重新评审。";
        }
    }

    @Test void assembleItineraryCapturesDraftWrittenByCode() throws Exception {
        var jdbc = mock(JdbcTemplate.class); var store = store(jdbc);
        try {
            var observation = store.begin(SESSION, null);
            var agent = agent(store, "assemble_itinerary", Map.of("sessionId", SESSION));
            assertTrue(agent.getWorkspaceManager().getFilesystem()
                    .write(runtime(observation), DRAFT_JSON, DRAFT_BODY).isSuccess());
            run(agent, observation);
            assertEquals(DRAFT_MARKDOWN, store.planningArtifact(observation), lastToolOutput);
            store.finish(observation, "PLANNING_COMPLETED", 10, true);
        } finally { store.close(); }
    }

    @Test void revisePoiSelectionCapturesReassembledDraft() throws Exception {
        var jdbc = mock(JdbcTemplate.class); var store = store(jdbc);
        try {
            var observation = store.begin(SESSION, null);
            var agent = agent(store, "revise_poi_selection", Map.of("sessionId", SESSION));
            assertTrue(agent.getWorkspaceManager().getFilesystem()
                    .write(runtime(observation), DRAFT_JSON, DRAFT_BODY).isSuccess());
            run(agent, observation);
            assertEquals(DRAFT_MARKDOWN, store.planningArtifact(observation), lastToolOutput);
            store.finish(observation, "PLANNING_COMPLETED", 10, true);
        } finally { store.close(); }
    }

    @Test void assembleForAnotherSessionDoesNotCapture() throws Exception {
        var jdbc = mock(JdbcTemplate.class); var store = store(jdbc);
        try {
            var observation = store.begin(SESSION, null);
            var agent = agent(store, "assemble_itinerary", Map.of("sessionId", "another-session"));
            run(agent, observation);
            assertNull(store.planningArtifact(observation));
            store.finish(observation, "INCOMPLETE", 10, false);
        } finally { store.close(); }
    }

}
