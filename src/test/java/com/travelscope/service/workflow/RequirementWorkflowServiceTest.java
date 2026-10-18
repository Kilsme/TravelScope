package com.travelscope.service.workflow;

import com.travelscope.agent.tools.TransportTool;
import com.travelscope.collab.CollabFileReader;
import com.travelscope.collab.CollabFileWriter;
import com.travelscope.collab.IntakeResult;
import com.travelscope.collab.TaskWorkspace;
import com.travelscope.config.AppProperties;
import com.travelscope.dto.TripRequirementState;
import com.travelscope.service.TripRequirementStore;
import com.travelscope.service.workflow.dto.CollectOutcome;
import com.travelscope.service.workflow.dto.RequirementExtraction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * S2.2 验证：RequirementWorkflowService 需求采集状态机（清单卡片方案）。
 * 覆盖：一次收齐 DONE+落盘 / 缺项 NEED_USER_INPUT+formSpec / 增量修改只动提及字段 /
 * __form__ 直接 merge 不过 LLM / 留空提交与按推荐安排 DEGRADED+默认值+落盘标记 /
 * 抽取全空回提示 / DONE 幂等直通 / 取消复位。
 * 打桩：StubExtractor（覆写公有 extract）；ThrowingRedisTemplate（照抄 RequirementToolsTest
 * 的内存降级触发模式）；TaskWorkspace 指向 @TempDir。
 */
class RequirementWorkflowServiceTest {

    /** 抽取桩：按消息映射返回 canned 结果；boom=true 时抛异常（证明某路径不过 LLM） */
    static class StubExtractor extends RequirementExtractor {
        private final Map<String, RequirementExtraction> canned;
        private final boolean boom;
        int calls;

        StubExtractor(Map<String, RequirementExtraction> canned, boolean boom) {
            super(null, new AppProperties());
            this.canned = canned;
            this.boom = boom;
        }

        @Override
        public RequirementExtraction extract(String userMessage) {
            calls++;
            if (boom) {
                throw new RuntimeException("stub boom");
            }
            return canned.getOrDefault(userMessage, RequirementExtraction.EMPTY);
        }
    }

    /** opsForHash/expire 全抛异常 → 内存降级（RequirementToolsTest 同款模式） */
    static class ThrowingRedisTemplate extends StringRedisTemplate {
        @Override
        public <HK, HV> org.springframework.data.redis.core.HashOperations<String, HK, HV> opsForHash() {
            throw new IllegalStateException("redis down");
        }

        @Override
        public Boolean expire(String key, Duration timeout) {
            throw new IllegalStateException("redis down");
        }
    }

    @TempDir
    Path tempDir;

    private TripRequirementStore store;
    private TaskWorkspace workspace;
    private TransportTool transport;

    @BeforeEach
    void setUp() {
        store = new TripRequirementStore(new ThrowingRedisTemplate());
        workspace = new TaskWorkspace(new CollabFileWriter(tempDir.toString()), new CollabFileReader());
        // 默认地点可识别（返回合法 location）；个别用例覆写为 error 模拟查无此地
        transport = mock(TransportTool.class);
        when(transport.geocode(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> "{\"location\":\"120.15,30.25\",\"city\":\""
                        + inv.getArgument(0) + "\"}");
    }

    private static RequirementExtraction ext(Map<String, Object> fields) {
        return new RequirementExtraction(fields, "stub");
    }

    private static TripRequirementState preset(String destination, Integer days,
                                               String startDate, String fromCity,
                                               TripRequirementState.Status status) {
        TripRequirementState s = new TripRequirementState();
        s.destination = destination;
        s.days = days;
        s.startDate = startDate;
        s.fromCity = fromCity;
        s.status = status;
        return s;
    }

    // ---------- 用例 1：一条消息收齐 → DONE + 落盘 ----------
    @Test
    void allPresentDoneAndPersisted() {
        StubExtractor extractor = new StubExtractor(Map.of(
                "帮我规划杭州两日游，从上海出发，下周六走",
                ext(Map.of("destination", "杭州", "days", 2,
                        "startDate", "2026-10-24", "fromCity", "上海"))), false);
        RequirementWorkflowService service = new RequirementWorkflowService(extractor, store, workspace, transport);

        CollectOutcome out = service.handle("帮我规划杭州两日游，从上海出发，下周六走", "u1", "conv-1");

        assertThat(out.status()).isEqualTo(CollectOutcome.Status.COLLECTED);
        TripRequirementState state = store.get("u1", "conv-1");
        assertThat(state.status).isEqualTo(TripRequirementState.Status.DONE);
        // 落盘读回
        assertThat(workspace.readIntakeResult("u1", "conv-1")).hasValueSatisfying(r -> {
            assertThat(r.status()).isEqualTo(IntakeResult.Status.DONE);
            assertThat(r.degraded()).isFalse();
            assertThat(r.fields().destination().value()).isEqualTo("杭州");
            assertThat(r.fields().destination().confirmed()).isTrue();
            assertThat(r.fields().days().value()).isEqualTo("2");
            assertThat(r.clarifyCycles()).isZero();
        });
    }

    // ---------- 用例 2：缺 2 必填 → NEED_USER_INPUT + formSpec 标注 ----------
    @Test
    void missingTwoNeedInputWithFormSpec() {
        StubExtractor extractor = new StubExtractor(Map.of(
                "帮我规划杭州两日游", ext(Map.of("destination", "杭州", "days", 2))), false);
        RequirementWorkflowService service = new RequirementWorkflowService(extractor, store, workspace, transport);

        CollectOutcome out = service.handle("帮我规划杭州两日游", "u1", "conv-2");

        assertThat(out.status()).isEqualTo(CollectOutcome.Status.NEED_USER_INPUT);
        assertThat(out.hint()).isNull();
        assertThat(out.formSpec()).hasSize(8);
        Map<String, RequirementFormSpec.FieldSpec> byName = new java.util.LinkedHashMap<>();
        out.formSpec().forEach(f -> byName.put(f.name(), f));
        assertThat(byName.get("destination").filled()).isTrue();
        assertThat(byName.get("destination").value()).isEqualTo("杭州");
        assertThat(byName.get("startDate").filled()).isFalse();
        assertThat(byName.get("startDate").required()).isTrue();
        assertThat(byName.get("fromCity").filled()).isFalse();
        assertThat(byName.get("fromCity").required()).isTrue();
        assertThat(byName.get("budget").required()).isFalse();
        assertThat(byName.get("budget").options()).containsExactly("人均500内", "500-1000", "1000-2000", "2000以上");
        // 必填在前展示顺序
        assertThat(out.formSpec().get(0).name()).isEqualTo("destination");
    }

    // ---------- 用例 3：增量修改只动提及字段 ----------
    @Test
    void incrementalModificationOnlyTouchesMentionedField() {
        store.save("u1", "conv-3", preset("杭州", 3, "2026-10-24", null,
                TripRequirementState.Status.COLLECTING));
        StubExtractor extractor = new StubExtractor(Map.of(
                "出发地改成南京", ext(Map.of("fromCity", "南京"))), false);
        RequirementWorkflowService service = new RequirementWorkflowService(extractor, store, workspace, transport);

        CollectOutcome out = service.handle("出发地改成南京", "u1", "conv-3");

        assertThat(out.status()).isEqualTo(CollectOutcome.Status.COLLECTED);
        TripRequirementState state = store.get("u1", "conv-3");
        assertThat(state.fromCity).isEqualTo("南京");
        assertThat(state.destination).isEqualTo("杭州");
        assertThat(state.days).isEqualTo(3);
        assertThat(state.startDate).isEqualTo("2026-10-24");
        assertThat(state.status).isEqualTo(TripRequirementState.Status.DONE);
    }

    // ---------- 用例 4：__form__ 直接 merge 不过 LLM ----------
    @Test
    void formSubmitMergesWithoutLlm() {
        StubExtractor extractor = new StubExtractor(Map.of(), true);  // 调用即抛
        RequirementWorkflowService service = new RequirementWorkflowService(extractor, store, workspace, transport);

        CollectOutcome out = service.handle(
                "{\"__form__\":{\"destination\":\"杭州\",\"days\":2,"
                        + "\"startDate\":\"2026-10-25\",\"fromCity\":\"上海\"}}", "u1", "conv-4");

        assertThat(out.status()).isEqualTo(CollectOutcome.Status.COLLECTED);
        assertThat(extractor.calls).as("表单提交不应过 LLM").isZero();
        assertThat(store.get("u1", "conv-4").status).isEqualTo(TripRequirementState.Status.DONE);
    }

    // ---------- 用例 5：留空提交 → DEGRADED + 默认值 + 落盘标记 ----------
    @Test
    void blankSubmitDegradesWithDefaults() {
        StubExtractor extractor = new StubExtractor(Map.of(), true);
        RequirementWorkflowService service = new RequirementWorkflowService(extractor, store, workspace, transport);
        String tomorrow = LocalDate.now().plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE);

        CollectOutcome out = service.handle("{\"__form__\":{\"destination\":\"杭州\"}}", "u1", "conv-5");

        assertThat(out.status()).isEqualTo(CollectOutcome.Status.COLLECTED);
        TripRequirementState state = store.get("u1", "conv-5");
        assertThat(state.status).isEqualTo(TripRequirementState.Status.DEGRADED);
        assertThat(state.days).isEqualTo(2);
        assertThat(state.startDate).isEqualTo(tomorrow);
        assertThat(state.fromCity).isEqualTo("杭州");
        assertThat(workspace.readIntakeResult("u1", "conv-5")).hasValueSatisfying(r -> {
            assertThat(r.status()).isEqualTo(IntakeResult.Status.DEGRADED);
            assertThat(r.degraded()).isTrue();
            assertThat(r.fields().destination().confirmed()).isTrue();
            assertThat(r.fields().days().value()).isEqualTo("2");
            assertThat(r.fields().days().confirmed()).isFalse();
            assertThat(r.fields().days().note()).isEqualTo("默认值");
            assertThat(r.fields().fromCity().note()).isEqualTo("默认值");
        });
    }

    // ---------- 用例 6：按推荐安排（skip）同 DEGRADED 路径 ----------
    @Test
    void skipActionFollowsSameDegradedPath() {
        StubExtractor extractor = new StubExtractor(Map.of(), true);
        RequirementWorkflowService service = new RequirementWorkflowService(extractor, store, workspace, transport);

        CollectOutcome out = service.handle(
                "{\"__form__\":{\"__action__\":\"skip\",\"destination\":\"杭州\",\"days\":3}}",
                "u1", "conv-6");

        assertThat(out.status()).isEqualTo(CollectOutcome.Status.COLLECTED);
        TripRequirementState state = store.get("u1", "conv-6");
        assertThat(state.status).isEqualTo(TripRequirementState.Status.DEGRADED);
        assertThat(state.days).isEqualTo(3);
        assertThat(state.fromCity).isEqualTo("杭州");
    }

    // ---------- 用例 7：抽取全空 → NEED_USER_INPUT + 提示文案 ----------
    @Test
    void emptyExtractionReturnsHint() {
        StubExtractor extractor = new StubExtractor(Map.of(), false);  // 未收录 → EMPTY
        RequirementWorkflowService service = new RequirementWorkflowService(extractor, store, workspace, transport);

        CollectOutcome out = service.handle("随便改改", "u1", "conv-7");

        assertThat(out.status()).isEqualTo(CollectOutcome.Status.NEED_USER_INPUT);
        assertThat(out.hint()).contains("可直接点卡片修改");
        assertThat(out.formSpec()).hasSize(8);
        // 状态未被污染
        assertThat(store.get("u1", "conv-7").status).isEqualTo(TripRequirementState.Status.COLLECTING);
    }

    // ---------- 用例 8：DONE 幂等直通（不重抽、不重写文件） ----------
    @Test
    void doneStateIsIdempotent() {
        store.save("u1", "conv-8", preset("杭州", 2, "2026-10-24", "上海",
                TripRequirementState.Status.DONE));
        StubExtractor extractor = new StubExtractor(Map.of(), true);
        RequirementWorkflowService service = new RequirementWorkflowService(extractor, store, workspace, transport);

        CollectOutcome out = service.handle("再来一次", "u1", "conv-8");

        assertThat(out.status()).isEqualTo(CollectOutcome.Status.COLLECTED);
        assertThat(extractor.calls).isZero();
        assertThat(store.get("u1", "conv-8").status).isEqualTo(TripRequirementState.Status.DONE);
        assertThat(workspace.readIntakeResult("u1", "conv-8"))
                .as("幂等直通不应产生落盘").isEmpty();
    }

    // ---------- 用例 9：取消 → CANCELLED + 状态机复位 ----------
    @Test
    void cancelActionResetsStateMachine() {
        store.save("u1", "conv-9", preset("杭州", null, null, null,
                TripRequirementState.Status.COLLECTING));
        StubExtractor extractor = new StubExtractor(Map.of(), true);
        RequirementWorkflowService service = new RequirementWorkflowService(extractor, store, workspace, transport);

        CollectOutcome out = service.handle("{\"__form__\":{\"__action__\":\"cancel\"}}",
                "u1", "conv-9");

        assertThat(out.status()).isEqualTo(CollectOutcome.Status.CANCELLED);
        TripRequirementState state = store.get("u1", "conv-9");
        assertThat(state.status).isEqualTo(TripRequirementState.Status.COLLECTING);
        assertThat(state.destination).as("复位后无残留字段").isNull();
    }

    // ---------- 用例 10：geocode 查无此地 → 打回该字段，状态机不被污染 ----------
    @Test
    void unknownPlaceRejectedByGeocode() {
        when(transport.geocode("不存在的地名"))
                .thenReturn("{\"error\":\"未找到地理编码结果\"}");
        StubExtractor extractor = new StubExtractor(Map.of(), true);
        RequirementWorkflowService service = new RequirementWorkflowService(extractor, store, workspace, transport);

        CollectOutcome out = service.handle(
                "{\"__form__\":{\"destination\":\"杭州\",\"days\":2,"
                        + "\"startDate\":\"2026-10-25\",\"fromCity\":\"不存在的地名\"}}",
                "u1", "conv-10");

        assertThat(out.status()).isEqualTo(CollectOutcome.Status.NEED_USER_INPUT);
        assertThat(out.errors()).extracting(CollectOutcome.FieldError::field)
                .containsExactly("fromCity");
        assertThat(out.errors().get(0).message()).contains("不存在的地名");
        assertThat(out.formSpec()).as("已填内容回推不丢").hasSize(8);
        assertThat(out.formSpec()).anySatisfy(f -> {
            if ("destination".equals(f.name())) {
                assertThat(f.value()).isEqualTo("杭州");
            }
        });
        // 状态机未被污染：未落盘、未置终态
        TripRequirementState state = store.get("u1", "conv-10");
        assertThat(state.status).isEqualTo(TripRequirementState.Status.COLLECTING);
        assertThat(workspace.readIntakeResult("u1", "conv-10")).isEmpty();
    }

    // ---------- 用例 11：合法地点 → 正常收齐 ----------
    @Test
    void validPlacePassesGeocodeAndCollects() {
        StubExtractor extractor = new StubExtractor(Map.of(), true);
        RequirementWorkflowService service = new RequirementWorkflowService(extractor, store, workspace, transport);

        CollectOutcome out = service.handle(
                "{\"__form__\":{\"destination\":\"杭州\",\"days\":2,"
                        + "\"startDate\":\"2026-10-25\",\"fromCity\":\"上海\"}}",
                "u1", "conv-11");

        assertThat(out.status()).isEqualTo(CollectOutcome.Status.COLLECTED);
        assertThat(out.errors()).isEmpty();
        assertThat(store.get("u1", "conv-11").status).isEqualTo(TripRequirementState.Status.DONE);
        assertThat(workspace.readIntakeResult("u1", "conv-11")).isPresent();
    }
}
