package com.travelscope.agent;

/**
 * 用户意图类型（应用层意图路由用）
 * <p>
 * 由意图分类器（IntentClassifier）通过结构化输出判定，ChatService 据此选择处理路径：
 * <pre>
 * CHAT      → 闲聊/咨询，直接回答，不委派规划 Agent
 * TOOL_CALL → 单点实时查询（天气/酒店/景点/交通/火车票/机票），调工具后回答，不委派规划 Agent
 * PLANNING  → 完整行程规划，主 Agent 拆分任务并委派规划 Agent
 * RAG       → 知识库问答（攻略/政策/文化），预留分支，知识库不可用时回退主 Agent 直接回答
 * </pre>
 * </p>
 */
public enum IntentType {

    /** 闲聊/能力咨询：直接回答 */
    CHAT,

    /** 单点实时查询：调用工具回答 */
    TOOL_CALL,

    /** 行程规划：委派规划 Agent */
    PLANNING,

    /** 知识库问答：RAG 检索后回答（预留） */
    RAG
}
