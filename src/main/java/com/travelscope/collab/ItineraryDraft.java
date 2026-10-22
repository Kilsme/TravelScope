package com.travelscope.collab;

import java.util.List;
import java.util.Objects;

/**
 * 工作区契约：itinerary_draft.json（schemaVersion=4）
 * <p>
 * 生产者：itinerary-agent（组装草案）；消费者：reviewer-agent / travel-master。
 * 对应 v3 的 itinerary_draft.md：结构化字段供代码消费，{@link #markdownBody} 承载
 * 给 reviewer 阅读的草案正文长文本。
 * </p>
 * <p>
 * schemaVersion 恒为 {@link #SCHEMA_VERSION}（紧凑构造器强校验）；JSON 键与组件同名。
 * </p>
 */
public record ItineraryDraft(
        int schemaVersion,
        List<DraftDay> days,
        Budget budget,
        List<String> notes,
        List<String> pendingConfirm,
        String markdownBody) {

    /** 契约版本 */
    public static final int SCHEMA_VERSION = 4;

    public ItineraryDraft {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "schemaVersion 必须为 " + SCHEMA_VERSION + "，实际: " + schemaVersion);
        }
        days = List.copyOf(Objects.requireNonNull(days, "days"));
        // budget 可空（需求未提供预算时）
        notes = notes == null ? List.of() : List.copyOf(notes);
        pendingConfirm = pendingConfirm == null ? List.of() : List.copyOf(pendingConfirm);
        Objects.requireNonNull(markdownBody, "markdownBody");
    }

    /** 便捷构造：schemaVersion 固定为 {@link #SCHEMA_VERSION} */
    public ItineraryDraft(List<DraftDay> days, Budget budget, List<String> notes,
                          List<String> pendingConfirm, String markdownBody) {
        this(SCHEMA_VERSION, days, budget, notes, pendingConfirm, markdownBody);
    }

    /** 分日行程。day 从 1 起；date/theme 可空。 */
    public record DraftDay(
            int day,
            String date,
            String theme,
            List<DraftItem> items) {

        public DraftDay {
            if (day < 1) {
                throw new IllegalArgumentException("day 从 1 起，实际: " + day);
            }
            items = List.copyOf(Objects.requireNonNull(items, "items"));
        }
    }

    /**
     * 行程条目：时间 | 活动 | 地点 | 费用（可空）| 备注（可空）| 图片 URL（可空，v5-F2）。
     * time 形如 "09:00-11:30"；imageUrl 由 ItineraryAssembler 按 poi_shortlist 匹配挂载
     * （随草案 JSON 持久化——ItineraryProgress 逐天事件与修订增量链自动继承）。
     */
    public record DraftItem(
            String time,
            String activity,
            String location,
            String cost,
            String note,
            String imageUrl) {

        public DraftItem {
            Objects.requireNonNull(time, "time");
            Objects.requireNonNull(activity, "activity");
            Objects.requireNonNull(location, "location");
        }

        /** 旧调用便捷构造：无图片（v5-F2 前存量语义，imageUrl=null） */
        public DraftItem(String time, String activity, String location, String cost, String note) {
            this(time, activity, location, cost, note, null);
        }
    }

    /** 预算汇总。currency 默认 "CNY"；breakdown 可空（分项估算）。 */
    public record Budget(
            String currency,
            String totalEstimate,
            List<BudgetItem> breakdown) {

        public Budget {
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(totalEstimate, "totalEstimate");
            breakdown = breakdown == null ? List.of() : List.copyOf(breakdown);
        }
    }

    /** 预算分项：类别（交通/住宿/门票/餐饮…）| 金额文本 | 备注（可空）。 */
    public record BudgetItem(
            String category,
            String amount,
            String note) {

        public BudgetItem {
            Objects.requireNonNull(category, "category");
            Objects.requireNonNull(amount, "amount");
        }
    }
}
