package com.travelscope.assessment;

import com.travelscope.agent.tools.TransportTool;
import com.travelscope.collab.TaskWorkspace;
import com.travelscope.config.AppProperties;
import com.travelscope.dto.RetrievedFragment;
import com.travelscope.service.TaskResultCache;
import com.travelscope.service.retrieval.PoiRetrievalService;
import com.travelscope.service.retrieval.dto.PoiCandidatePool;
import com.travelscope.service.route.CommuteMatrixBuilder;
import com.travelscope.service.route.RoutePlannerServiceImpl;
import io.agentscope.core.agent.RuntimeContext;
import java.util.ArrayList;
import java.util.List;

/** 只替换外部数据源；本地检索编排、路线求解与契约校验保持真实执行。 */
public final class FixtureServices {
    private FixtureServices() {}

    public static boolean enabled(RuntimeContext runtime) {
        return "FIXTURE".equals(runtime.get("assessment.toolMode"));
    }

    public static PoiCandidatePool retrieve(PoiRetrievalService service, String destination,
            List<String> preferences, int days, RuntimeContext runtime) {
        boolean fail = Boolean.TRUE.equals(runtime.get("assessment.toolFailure"));
        List<RetrievedFragment> fragments = new ArrayList<>();
        if (!fail) {
            for (int index = 1; index <= 2 * days; index++) {
                double longitude = 120.15 + index * .001;
                fragments.add(new RetrievedFragment(destination + "固定测试景点" + index
                        + "｜坐标=" + longitude + ",30.25｜开放时间=09:00-18:00｜游玩时长=60分钟"
                        + "｜来源=FIXTURE（固定数据，不代表真实景点）", "FIXTURE", 1.0 - index * .001,
                        "fixture:" + destination + ":" + index, 1.0));
            }
        }
        return service.retrieve(destination, preferences, days, query -> List.copyOf(fragments),
                (city, query, limit) -> "{\"fixture\":true,\"attractions\":[]}");
    }

    public static RoutePlannerServiceImpl planner(TaskWorkspace workspace) {
        return new RoutePlannerServiceImpl(new CommuteMatrixBuilder(transport(), new TaskResultCache(null), 0),
                workspace);
    }

    private static final TransportTool TRANSPORT = new TransportTool(new AppProperties()) {
        @Override public String geocode(String address) {
            return "{\"fixture\":true,\"location\":\"120.16,30.25\"}";
        }
        @Override public String getDrivingRoute(String origin, String destination) {
            return "{\"fixture\":true,\"duration\":\"900\",\"distance\":\"3000\"}";
        }
        @Override public String getTransitRoute(String origin, String destination, String city) {
            return "{\"fixture\":true,\"routes\":[{\"duration\":\"900\"}]}";
        }
    };

    public static TransportTool transport() { return TRANSPORT; }
}
