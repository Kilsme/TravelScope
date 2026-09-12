package com.travelscope.dto;

/**
 * 单条规划任务（代码层强制拆分的结构化输出单元）
 */
public class PlanningTask {

    /** 任务 ID，如 T1、T2 */
    public String taskId;

    /** 任务描述（做什么、约束是什么） */
    public String description;

    /** 建议使用的工具/技能名（如 weather-query、mcp__c12306__get-tickets） */
    public String suggestedTool;

    /** 优先级：P0 必做 / P1 重要 / P2 可选 */
    public String priority;
}
