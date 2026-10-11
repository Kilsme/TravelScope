package com.travelscope.agent;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import com.travelscope.testutil.ModelGatewayTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LlmGatewayModel 真实 API 集成测试（FR-S09 检测标准 2/3 的真实链路验证）
 * <p>
 * 需 media_model_gateway_api_key（模型网关）环境变量，未配置整类跳过。验证两条真实链路：
 * ① 正常路径零回归（主链路 glm-5-3-260814 经装饰器正常回复）；
 * ② 错 Key 主实例（401）→ fallback 到 deepseek-v4-flash-0731（正确 Key）→ 用户仍能收到回复
 *    ——主/备同网关，该场景对应「主实例凭证失效」级别的故障（网关整体故障时
 *    fallback 同失效，由熔断 OPEN + SSE error 兜底，不在此测试范围）。
 * </p>
 */
@EnabledIfEnvironmentVariable(named = ModelGatewayTestSupport.API_KEY_ENV, matches = ".+")
class LlmGatewayRealApiTest {

    private static CircuitBreaker cb() {
        return CircuitBreaker.of("real-api-cb", CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .failureRateThreshold(50)
                .minimumNumberOfCalls(5)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .build());
    }

    @Test
    @DisplayName("正常路径零回归：主链路模型经装饰器流式返回响应")
    void testNormalPath() {
        LlmGatewayModel model = new LlmGatewayModel(ModelGatewayTestSupport.mainModel(),
                ModelGatewayTestSupport.lightModel(), cb(), Duration.ofSeconds(30), true);

        List<ChatResponse> responses = model.stream(
                        List.of(io.agentscope.core.message.Msg.builder()
                                .role(io.agentscope.core.message.MsgRole.USER)
                                .textContent("用一句话介绍杭州").build()),
                        List.of(), null)
                .collectList().block(Duration.ofSeconds(60));

        assertNotNull(responses);
        assertTrue(responses.size() > 0, "正常路径应返回响应块");
    }

    @Test
    @DisplayName("检测标准3 真实链路：错 Key 主实例（401）→ fallback → deepseek-v4-flash-0731 → 仍能收到回复")
    void testBadKeyFallsBackToLightModel() {
        LlmGatewayModel model = new LlmGatewayModel(
                ModelGatewayTestSupport.mainModelWithKey("invalid-key-for-test"),
                ModelGatewayTestSupport.lightModel(), cb(), Duration.ofSeconds(30), true);

        List<ChatResponse> responses = model.stream(
                        List.of(io.agentscope.core.message.Msg.builder()
                                .role(io.agentscope.core.message.MsgRole.USER)
                                .textContent("用一句话介绍西湖").build()),
                        List.of(), null)
                .collectList().block(Duration.ofSeconds(60));

        assertNotNull(responses, "fallback 成功应返回响应（用户不白屏）");
        assertTrue(responses.size() > 0);
        assertTrue(responses.stream().anyMatch(r -> r.getContent() != null
                        && !r.getContent().isEmpty()),
                "fallback 的轻量模型应产出实际内容");
    }
}
