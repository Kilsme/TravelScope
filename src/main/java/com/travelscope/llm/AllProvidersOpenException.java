package com.travelscope.llm;

/**
 * v5-F3：池内全部 provider 熔断（SSE 场景转「AI 服务暂时不可用，请联系管理员」；
 * embedding 池抛出时由 RagServiceImpl 既有 catch 把语义路本轮退化为 BM25）。
 */
public class AllProvidersOpenException extends RuntimeException {

    public AllProvidersOpenException(String message) {
        super(message);
    }
}
