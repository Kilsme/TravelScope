package com.travelscope.collab;

/**
 * 工作区契约校验失败异常（S0.2）。
 * <p>
 * 消息约定：{@code [{DTO类型名}] {字段路径} {原因}[；原文前200字符: {...}]}——
 * 定位到具体文件类型与字段（conv-97 覆写事故教训：坏内容必须能快速定位来源与位置）。
 * </p>
 */
public class CollabValidationException extends RuntimeException {

    public CollabValidationException(String message) {
        super(message);
    }

    public CollabValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
