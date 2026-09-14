package com.travelscope.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 行程需求状态机（v3 需求文档 3.3 决策三 / FR-S02）
 * <p>
 * 「判断缺什么」是纯确定性逻辑，由代码承载（本类的 {@link #missingFields()}），
 * intake-agent 调用 RequirementTools 查询/写回，判断环节零模型调用。
 * 状态跨 spawn 持久在 TripRequirementStore（userId:sessionId 双键隔离）。
 * </p>
 * <p>
 * TODO: 迁移 Redis hash {@code trip:req:{userId}:{sessionId}}（FR-S02，与 AgentState 同隔离级别）。
 * </p>
 */
public class TripRequirementState {

    /** 需求收集状态（v3 需求文档 3.3） */
    public enum Status {
        /** 收集中（仍有必填缺项） */
        COLLECTING,
        /** 必填项已收齐 */
        DONE,
        /** 反问超限后带默认值放行（方案中需标注「待确认」） */
        DEGRADED
    }

    /** 必填字段（缺失即反问） */
    public static final List<String> REQUIRED_FIELDS = List.of(
            "destination", "days", "startDate", "fromCity");

    /** 选填字段（缺失不反问，规划时按默认处理） */
    public static final List<String> OPTIONAL_FIELDS = List.of(
            "budget", "preference", "people", "special");

    public String destination;
    public Integer days;
    public String startDate;
    public String fromCity;
    public String budget;
    public String preference;
    public Integer people;
    public String special;

    /** 当前状态 */
    public Status status = Status.COLLECTING;

    /** 已反问轮次（上限 3 轮，超限 DEGRADED 放行） */
    public int clarifyCycles = 0;

    /**
     * 计算仍缺失的必填字段（纯代码，零模型调用）
     */
    public List<String> missingFields() {
        List<String> missing = new ArrayList<>();
        if (isBlank(destination)) {
            missing.add("destination");
        }
        if (days == null || days <= 0) {
            missing.add("days");
        }
        if (isBlank(startDate)) {
            missing.add("startDate");
        }
        if (isBlank(fromCity)) {
            missing.add("fromCity");
        }
        return missing;
    }

    /**
     * 生成给 Agent 看的状态摘要（get_missing_fields 工具返回值）
     */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        List<String> missing = missingFields();
        sb.append("已收集字段: ").append(collectedDescription()).append('\n');
        if (missing.isEmpty()) {
            sb.append("必填项已全部收齐，状态: ").append(status);
        } else {
            sb.append("仍缺必填项: ").append(String.join(", ", missing))
                    .append("（状态: ").append(status)
                    .append("，已反问 ").append(clarifyCycles).append("/3 轮）");
        }
        return sb.toString();
    }

    private String collectedDescription() {
        List<String> parts = new ArrayList<>();
        if (!isBlank(destination)) {
            parts.add("目的地=" + destination);
        }
        if (days != null && days > 0) {
            parts.add("天数=" + days);
        }
        if (!isBlank(startDate)) {
            parts.add("出发日期=" + startDate);
        }
        if (!isBlank(fromCity)) {
            parts.add("出发城市=" + fromCity);
        }
        if (!isBlank(budget)) {
            parts.add("预算=" + budget);
        }
        if (!isBlank(preference)) {
            parts.add("偏好=" + preference);
        }
        if (people != null && people > 0) {
            parts.add("人数=" + people);
        }
        if (!isBlank(special)) {
            parts.add("特殊要求=" + special);
        }
        return parts.isEmpty() ? "（暂无）" : String.join("; ", parts);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
