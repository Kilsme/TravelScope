package com.travelscope.llm;

import com.travelscope.assessment.ObservationStore;
import com.travelscope.common.TracingHelper;
import com.travelscope.config.AgentConfig;
import com.travelscope.config.AppProperties;
import io.agentscope.core.model.Model;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * v5-F3 池装配差异测试（剧本⑥-config 侧）：AgentConfig 按 ProviderSpec 逐项构建——
 * priority 排序、use-glm-formatter 差异（GLM 挂 GLMFormatter、Kimi 不挂——经
 * ObservedModel.delegate → OpenAIChatModel.formatter 反射核验）、per-provider 超时覆盖、
 * model-pool 缺省时回落 model-gateway 单模型。全程指向 127.0.0.1:9 黑洞地址（构造期零网络调用）。
 */
class ModelPoolWiringTest {

    private static AgentConfig config(AppProperties props) {
        AgentConfig config = new AgentConfig(props, null, null, null, null, null, null, TracingHelper.NOOP);
        ReflectionTestUtils.setField(config, "observationStore", mock(ObservationStore.class));
        return config;
    }

    private static AppProperties baseProps() {
        AppProperties props = new AppProperties();
        props.getModelGateway().setBaseUrl("http://127.0.0.1:9/v1");
        props.getModelGateway().setApiKey("SYNTHETIC-NOT-A-CREDENTIAL");
        return props;
    }

    @Test
    @DisplayName("chat 池按 spec 构建：priority 排序 / glm-formatter 差异 / 超时覆盖 / 能力委托")
    void chatPoolBuildsPerProviderDifferences() {
        AppProperties props = baseProps();
        AppProperties.ProviderSpec glm = new AppProperties.ProviderSpec();
        glm.setId("glm");
        glm.setModel("glm-5-3-260814");
        glm.setPriority(1);
        glm.setUseGlmFormatter(true);
        glm.setFirstPacketTimeoutSeconds(7L);   // per-provider 超时覆盖
        AppProperties.ProviderSpec kimi = new AppProperties.ProviderSpec();
        kimi.setId("kimi");
        kimi.setModel("Kimi-k3");
        kimi.setPriority(2);
        kimi.setUseGlmFormatter(false);          // 非 GLM 系不挂 GLMFormatter
        kimi.setNativeStructuredOutputWithTools(false);
        props.getModelPool().getChat().getMain().setProviders(List.of(glm, kimi));

        ModelProviderPool pool = (ModelProviderPool) config(props)
                .gatewayChatModel(new ProviderProbeScheduler());

        assertEquals("main", pool.poolName());
        assertEquals(2, pool.providers().size());
        assertEquals("glm", pool.providers().get(0).id(), "priority 升序");
        assertEquals("kimi", pool.providers().get(1).id());
        assertEquals("glm-5-3-260814", pool.getModelName(), "能力委托当前生效 provider");
        assertEquals(Duration.ofSeconds(7), pool.providers().get(0).firstPacketTimeout(), "超时覆盖生效");
        assertEquals(Duration.ofSeconds(10), pool.providers().get(1).firstPacketTimeout(), "未覆盖走全局默认");

        // use-glm-formatter 差异：GLM 挂 GLMFormatter，Kimi 走网关默认格式（OpenAIChatFormatter）
        Object glmRaw = ReflectionTestUtils.getField(pool.providers().get(0).raw(), "delegate");
        Object kimiRaw = ReflectionTestUtils.getField(pool.providers().get(1).raw(), "delegate");
        Object glmFormatter = ReflectionTestUtils.getField(glmRaw, "formatter");
        assertNotNull(glmFormatter, "GLM provider 应有 formatter");
        assertTrue(glmFormatter instanceof io.agentscope.extensions.model.openai.compat.glm.GLMFormatter,
                "GLM provider 应挂 GLMFormatter，实际 " + glmFormatter.getClass().getName());
        assertFalse(ReflectionTestUtils.getField(kimiRaw, "formatter")
                        instanceof io.agentscope.extensions.model.openai.compat.glm.GLMFormatter,
                "Kimi provider 不应挂 GLMFormatter（走网关默认格式）");
    }

    @Test
    @DisplayName("model-pool 缺省回落：reviewer 池回落 model-gateway.reviewer-model 单模型（含 GLMFormatter）")
    void chatPoolFallsBackToModelGatewayWhenUnconfigured() {
        AppProperties props = baseProps();
        props.getModelGateway().setReviewerModel("glm-5-3-260814");

        ModelProviderPool reviewer = (ModelProviderPool) config(props)
                .gatewayReviewerModel(new ProviderProbeScheduler());

        assertEquals("reviewer", reviewer.poolName());
        assertEquals(1, reviewer.providers().size());
        assertEquals("glm-5-3-260814", reviewer.getModelName());
        Object raw = ReflectionTestUtils.getField(reviewer.providers().get(0).raw(), "delegate");
        assertNotNull(ReflectionTestUtils.getField(raw, "formatter"), "缺省兼容走 GLMFormatter");
        assertEquals(Model.class.cast(reviewer).supportsNativeStructuredOutputWithTools(),
                props.getModelGateway().isNativeStructuredOutputWithTools(),
                "未覆盖的结构化输出开关沿用 model-gateway 默认");
    }
}
