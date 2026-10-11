package com.travelscope.testutil;

import io.agentscope.extensions.model.openai.OpenAIChatModel;

/**
 * 测试用模型网关构造辅助（2026-10-05 对话模型迁移）：与 AgentConfig 生产装配同款——
 * OpenAIChatModel 直连模型网关（标准 OpenAI chat completions 协议 + Bearer 认证）。
 * <p>
 * base-url 从环境变量 {@code media_model_gateway_base_url} 读取，未设置时用
 * 默认网关地址（与 application.yml 的 travelscope.model-gateway.base-url 一致）；
 * api-key 从 {@code media_model_gateway_api_key} 读取（也是各 RealApi 测试的门控变量）。
 * </p>
 */
public final class ModelGatewayTestSupport {

    /** 默认网关地址（与 application.yml 一致） */
    public static final String DEFAULT_BASE_URL = "http://ddns.trdyun.xyz:10003/v1";

    /** 主链路对话模型 */
    public static final String MAIN_MODEL = "glm-5-3-260814";

    /** 质检评分模型（当前与主链路同款，独立常量便于区分用途） */
    public static final String REVIEWER_MODEL = "glm-5-3-260814";

    /** 轻量模型（L2 意图分类 / 会话摘要 / fallback） */
    public static final String LIGHT_MODEL = "deepseek-v4-flash-0731";

    /** 门控用环境变量名（网关 API Key） */
    public static final String API_KEY_ENV = "media_model_gateway_api_key";

    private ModelGatewayTestSupport() {
    }

    public static String baseUrl() {
        String url = System.getenv("media_model_gateway_base_url");
        return (url == null || url.isBlank()) ? DEFAULT_BASE_URL : url;
    }

    public static String apiKey() {
        return System.getenv(API_KEY_ENV);
    }

    /** 主链路模型（glm-5-3-260814） */
    public static OpenAIChatModel mainModel() {
        return ofModel(MAIN_MODEL);
    }

    /** 质检评分模型（glm-5-3-260814，与生产 gatewayReviewerModel 同源配置） */
    public static OpenAIChatModel reviewerModel() {
        return ofModel(REVIEWER_MODEL);
    }

    /** 轻量模型（deepseek-v4-flash-0731，与生产 gatewayLightModel 同源配置） */
    public static OpenAIChatModel lightModel() {
        return ofModel(LIGHT_MODEL);
    }

    /** 按模型名构造（错 Key 注入等特殊场景） */
    public static OpenAIChatModel ofModel(String modelName) {
        return OpenAIChatModel.builder()
                .baseUrl(baseUrl())
                .apiKey(apiKey())
                .modelName(modelName)
                .stream(true)
                .build();
    }

    /** 指定 apiKey 构造主链路模型（fallback 真实链路测试：主实例错 Key → 401 → fallback 正确 Key） */
    public static OpenAIChatModel mainModelWithKey(String apiKey) {
        return OpenAIChatModel.builder()
                .baseUrl(baseUrl())
                .apiKey(apiKey)
                .modelName(MAIN_MODEL)
                .stream(true)
                .build();
    }
}
