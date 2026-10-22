package com.travelscope.llm;

import com.travelscope.testutil.ModelGatewayTestSupport;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v5-F3 模型池真实 API 集成测试（沿袭旧 LlmGatewayRealApiTest 的真实链路验证口径）
 * <p>
 * 需 media_model_gateway_api_key（模型网关）环境变量，未配置整类跳过。验证两条真实链路：
 * ① 正常路径零回归（glm-5-3-260814 经池正常回复）；
 * ② 错 Key 主 provider（401）→ 池内 failover 到健康 provider 干净重发 → 用户仍能收到回复
 *    ——首包提交闸门语义的真实链路验证（首包前失败切换、首包后不切换）。
 * </p>
 */
@EnabledIfEnvironmentVariable(named = ModelGatewayTestSupport.API_KEY_ENV, matches = ".+")
class ModelProviderPoolRealApiTest {

    private static ProviderCircuitBreaker breaker(String id) {
        return new ProviderCircuitBreaker(id, 60, 0.95, 30_000);
    }

    private static ModelProviderPool pool(Model primary, Model secondary) {
        return new ModelProviderPool("real-api", List.of(
                new ModelProviderPool.Provider("primary", 1, primary, breaker("real-api/primary"),
                        Duration.ofSeconds(30), Duration.ofSeconds(30)),
                new ModelProviderPool.Provider("secondary", 2, secondary, breaker("real-api/secondary"),
                        Duration.ofSeconds(30), Duration.ofSeconds(30))));
    }

    private static List<Msg> ask(String text) {
        return List.of(Msg.builder().role(MsgRole.USER).textContent(text).build());
    }

    @Test
    @DisplayName("正常路径零回归：主链路模型经池流式返回响应")
    void testNormalPath() {
        ModelProviderPool pool = pool(ModelGatewayTestSupport.mainModel(),
                ModelGatewayTestSupport.lightModel());

        List<ChatResponse> responses = pool.stream(ask("用一句话介绍杭州"), List.of(), null)
                .collectList().block(Duration.ofSeconds(60));

        assertNotNull(responses);
        assertTrue(responses.size() > 0, "正常路径应返回响应块");
        assertEquals(ProviderCircuitBreaker.State.CLOSED,
                pool.providers().get(0).breaker().get(), "成功调用不熔断");
    }

    @Test
    @DisplayName("真实链路 failover：错 Key 主 provider（401）→ 池切健康 provider → 仍能收到回复")
    void testBadKeyFailsOverToHealthyProvider() {
        ModelProviderPool pool = pool(ModelGatewayTestSupport.mainModelWithKey("invalid-key-for-test"),
                ModelGatewayTestSupport.lightModel());

        List<ChatResponse> responses = pool.stream(ask("用一句话介绍西湖"), List.of(), null)
                .collectList().block(Duration.ofSeconds(60));

        assertNotNull(responses, "failover 成功应返回响应（用户不白屏）");
        assertTrue(responses.size() > 0);
        assertTrue(responses.stream().anyMatch(r -> r.getContent() != null && !r.getContent().isEmpty()),
                "健康 provider 应产出实际内容");
        assertEquals(ProviderCircuitBreaker.State.CLOSED,
                pool.providers().get(0).breaker().get(),
                "单次 401 属普通失败，统计未达标不熔断（failover 已在池内完成）");
    }
}
