package com.travelscope.collab;

import java.util.List;
import java.util.Objects;

/**
 * 工作区契约：poi_shortlist.json（schemaVersion=4）
 * <p>
 * 生产者：planning-agent（对 PoiRetrievalService 候选池做 LLM 筛选排序后落盘）；
 * 消费者：planning-agent 自身（组装/回炉复用）。
 * 对应 v3 的 poi_shortlist.md。
 * </p>
 * <p>
 * schemaVersion 恒为 {@link #SCHEMA_VERSION}（紧凑构造器强校验）；JSON 键与组件同名。
 * </p>
 */
public record PoiShortlist(
        int schemaVersion,
        String destination,
        String retrievalNote,
        List<PoiItem> pois) {

    /** 契约版本 */
    public static final int SCHEMA_VERSION = 4;

    public PoiShortlist {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "schemaVersion 必须为 " + SCHEMA_VERSION + "，实际: " + schemaVersion);
        }
        Objects.requireNonNull(destination, "destination");
        // retrievalNote 可空：检索轮次/规模说明（如「5 变体并行，去重后 14 个候选」）
        pois = List.copyOf(Objects.requireNonNull(pois, "pois"));
    }

    /** 便捷构造：schemaVersion 固定为 {@link #SCHEMA_VERSION} */
    public PoiShortlist(String destination, String retrievalNote, List<PoiItem> pois) {
        this(SCHEMA_VERSION, destination, retrievalNote, pois);
    }

    /**
     * POI 候选。source 必填：来源标记（RAG(chunk=…) / ES(keyword=…) / RAG+ES / searchPois(keyword=…)）。
     * lng/lat 可空（待 geocode 补齐）；rating/openHours/durationMin/reason 均可空；
     * imageUrl 可空（v5-F2 图片透传：飞猪 mainPic/高德 photos 首图，https 归一——
     * 由 select_poi_shortlist 从候选池/实证解析继承，ItineraryAssembler 据此渲染图集）。
     */
    public record PoiItem(
            String name,
            Double lng,
            Double lat,
            Double rating,
            String openHours,
            Integer durationMin,
            String source,
            String reason,
            String imageUrl) {

        public PoiItem {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(source, "source");
        }

        /** 旧调用便捷构造：无图片（v5-F2 前存量语义，imageUrl=null） */
        public PoiItem(String name, Double lng, Double lat, Double rating, String openHours,
                       Integer durationMin, String source, String reason) {
            this(name, lng, lat, rating, openHours, durationMin, source, reason, null);
        }
    }
}
