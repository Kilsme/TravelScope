package com.travelscope.agent;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
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
 * 需 API_KEY（DashScope）环境变量，未配置整类跳过。验证两条真实链路：
 * ① 正常路径零回归（plus 经装饰器正常回复）；
 * ② 错 Key 实例（401）→ fallback 到 turbo（正确 Key）→ 用户仍能收到回复。
 * </p>
 */
@EnabledIfEnvironmentVariable(named = "API_KEY", matches = "sk-.+")
class LlmGatewayRealApiTest {

    private static CircuitBreaker cb() {
        return CircuitBreaker.of("real-api-cb", CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .failureRateThreshold(50)
                .minimumNumberOfCalls(5)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .build());
    }

    private static Model plus(String apiKey) {
        return DashScopeChatModel.builder()
                .apiKey(apiKey).modelName("qwen-plus").stream(true).build();
    }

    private static Model turbo(String apiKey) {
        return DashScopeChatModel.builder()
                .apiKey(apiKey).modelName("qwen-turbo").stream(true).build();
    }

    @Test
    @DisplayName("正常路径零回归：plus 经装饰器流式返回响应")
    void testNormalPath() {
        String apiKey = System.getenv("API_KEY");
        LlmGatewayModel model = new LlmGatewayModel(plus(apiKey), turbo(apiKey),
                cb(), Duration.ofSeconds(30), true);

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
    @DisplayName("检测标准3 真实链路：错 Key（401）→ fallback → qwen-turbo → 仍能收到回复")
    void testBadKeyFallsBackToTurbo() {
        String apiKey = System.getenv("API_KEY");
        LlmGatewayModel model = new LlmGatewayModel(plus("sk-invalid-key-for-test"),
                turbo(apiKey), cb(), Duration.ofSeconds(30), true);

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
                "fallback 的 turbo 应产出实际内容");
    }
}
