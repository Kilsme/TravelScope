package com.travelscope.assessment;

import com.alibaba.fastjson2.JSON;
import com.travelscope.agent.tools.AttractionTool;
import com.travelscope.agent.tools.CollabTools;
import com.travelscope.collab.*;
import com.travelscope.eval.*;
import com.travelscope.service.RagServiceImpl;
import com.travelscope.service.retrieval.PoiRetrievalService;
import com.travelscope.service.route.RoutePlannerServiceImpl;
import io.agentscope.core.agent.RuntimeContext;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssessmentFixtureServicesTest {
    @TempDir Path directory;

    @Test void fixtureRunsRealRetrievalAndRouteAlgorithmsWithoutLiveProviders() {
        var rag = mock(RagServiceImpl.class);
        var attractions = mock(AttractionTool.class);
        var livePlanner = mock(RoutePlannerServiceImpl.class);
        var retrieval = new PoiRetrievalService(rag, attractions);
        var writer = new CollabFileWriter(directory.toString());
        var workspace = new TaskWorkspace(writer, new CollabFileReader());
        var tools = new CollabTools(retrieval, livePlanner, workspace, writer);
        var runtime = RuntimeContext.builder().userId("fixture-user").sessionId("fixture-session")
                .put("assessment.toolMode", "FIXTURE").build();
        var pool = JSON.parseObject(tools.retrieve_poi_candidates("杭州", 2, "自然风景", runtime));
        assertEquals(4, pool.getJSONArray("candidates").size());
        assertTrue(pool.getJSONObject("stats").getIntValue("variantCount") > 0);
        assertEquals(4, pool.getJSONObject("stats").getIntValue("totalAfterDedup"));
        workspace.writeIntakeResult("fixture-user", "fixture-session", new IntakeResult("fixture-session",
                IntakeResult.Status.DONE, 0, new IntakeResult.RequirementFields(
                field("杭州"), field("2"), field("2026-10-24"), field("上海"),
                field(null), field(null), field(null), field(null)), false, "test"));
        workspace.writePoiShortlist("fixture-user", "fixture-session", new PoiShortlist("杭州", "FIXTURE", List.of(
                new PoiShortlist.PoiItem("测试景点一", 120.16, 30.25, 4.5, "09:00-18:00", 60, "FIXTURE", null),
                new PoiShortlist.PoiItem("测试景点二", 120.17, 30.25, 4.5, "09:00-18:00", 60, "FIXTURE", null))));
        String route = tools.plan_route("fixture-session", null, null, runtime);
        assertFalse(route.startsWith("ERROR"), route);
        var plan = workspace.readRoutePlan("fixture-user", "fixture-session").orElseThrow();
        assertEquals(2, plan.days().size());
        assertEquals("NN+2opt", plan.solverMeta().algorithm());
        verifyNoInteractions(rag, attractions, livePlanner);
        runtime.put("assessment.toolFailure", true);
        assertTrue(tools.plan_route("fixture-session", null, null, runtime).startsWith("ERROR"));
    }

    @Test void missingInputUsesCurrentRunStateAndDeclaredDependenciesNameActualTools() {
        var missing = EvalCaseLoader.loadAll().stream().filter(c -> c.category() == EvalCategory.MISSING_INFORMATION)
                .findFirst().orElseThrow();
        var evidence = new EvalRunArtifact("请补全需求", null, null, null, directory);
        assertEquals("PASS", AssessmentRules.check(missing, evidence, List.of(), "WAITING_INPUT").getFirst().status());
        assertEquals("UNKNOWN", AssessmentRules.check(missing, evidence, List.of(), "FAILED").getFirst().status());
        for (var c : EvalCaseLoader.loadAll()) for (var rule : c.expectations().rules()) {
            if (rule.type() == RuleType.TOOL_ORDER) {
                assertEquals("retrieve_poi_candidates", rule.params().get("before"));
                assertEquals("plan_route", rule.params().get("after"));
            }
        }
    }

    private static IntakeResult.FieldValue field(String value) {
        return new IntakeResult.FieldValue(value, value != null, null);
    }
}
