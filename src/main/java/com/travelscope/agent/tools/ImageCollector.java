package com.travelscope.agent.tools;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.agentscope.core.agent.RuntimeContext;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 图片收集器（v5-F2 图片透传，2026-10-22）：工具搜索到的图片 URL 收进数组，
 * 展示由代码组装——不靠提示词约束 LLM 输出图片，模型无法丢失或伪造
 * （图集 URL 唯一来源是工具真实返回的数组；模型文本里的图片至多重复，会被去重）。
 * <p>
 * 注入与消费：ChatService 在非 PLANNING 轮（runAgent 路径）经 ctx 注入本类实例
 * （{@link #CTX_KEY}，沿用 CTX_COLLAB_DIR_KEY 的 ctx 键模式）；HotelTool/AttractionTool
 * 搜索入口把条目 imageUrl 写入；runAgent 流收尾用 {@link #toGalleryMarkdown} 组装图集
 * append 到回复（delta 事件 + replyBuf 同步）。PLANNING 泳道不注入——行程图片走
 * 规划链路字段（poi_shortlist 的 imageUrl），由 ItineraryAssembler/TaskResultFormatter 代码渲染。
 * </p>
 */
public final class ImageCollector {

    /** RuntimeContext 注入键（工具方法以不加 @ToolParam 的 ctx 参数取用） */
    public static final String CTX_KEY = "travelscope.image.collector";

    /** 单条图片引用：url（已归一 https）+ 条目名 + 来源标签（飞猪/高德） */
    public record ImageRef(String url, String name, String source) {}

    private final List<ImageRef> refs = new ArrayList<>();
    private final Set<String> seenUrls = new LinkedHashSet<>();

    /** 收集一条（url 空白跳过；按 URL 去重；synchronized——搜索工具可能在并行调用中写入） */
    public synchronized void add(String url, String name, String source) {
        String normalized = normalizeUrl(url);
        if (normalized == null || !seenUrls.add(normalized)) {
            return;
        }
        refs.add(new ImageRef(normalized, name, source));
    }

    /** 已收集引用的快照 */
    public synchronized List<ImageRef> refs() {
        return List.copyOf(refs);
    }

    /**
     * 工具条目列表 → 收集器（ctx 无收集器时静默跳过——程序内 3 参重载调用/单测传 null 均安全）。
     * 条目须含 imageUrl（可为 null）与 name；source 取条目的 source 标签。
     */
    public static void collectFrom(RuntimeContext ctx, List<JSONObject> entries) {
        if (ctx == null) {
            return;
        }
        if (!(ctx.get(CTX_KEY) instanceof ImageCollector collector)) {
            return;
        }
        for (JSONObject entry : entries) {
            String url = entry.getString("imageUrl");
            if (url != null && !url.isBlank()) {
                collector.add(url, entry.getString("name"), entry.getString("source"));
            }
        }
    }

    /**
     * URL 归一：trim + http→https（防 https 前端混合内容拦截；两 CDN 实测 https 全可达，
     * 高德 photos 协议混用 http 居多）；空白返回 null。
     */
    public static String normalizeUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String trimmed = url.trim();
        return trimmed.startsWith("http://")
                ? "https://" + trimmed.substring("http://".length())
                : trimmed;
    }

    /**
     * 数组 → 图集 markdown（runAgent 收尾的代码组装出口）：与已有回复按 URL 去重
     * （LLM 若自写了同图不重复）；无可用图返回空串。
     */
    public static String toGalleryMarkdown(List<ImageRef> refs, String existingReply) {
        List<ImageRef> usable = refs.stream()
                .filter(r -> r.url() != null && !r.url().isBlank())
                .filter(r -> existingReply == null || !existingReply.contains(r.url()))
                .toList();
        if (usable.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\n📷 相关图片\n");
        for (ImageRef ref : usable) {
            sb.append("![").append(altOf(ref)).append("](").append(ref.url()).append(") ");
        }
        return sb.toString();
    }

    /** alt 文本：条目名（方括号转全角防 markdown 语法冲突；空名兜底「图片」） */
    private static String altOf(ImageRef ref) {
        String alt = ref.name() == null ? "" : ref.name().replace("[", "（").replace("]", "）");
        return alt.isBlank() ? "图片" : alt;
    }

    /**
     * 高德 photos 首图（place API extensions=all 返回）。类型漂移兼容：无值时 photos
     * 或 photos[].url 可能返回空数组而非字符串/对象——一律按无图处理返回 null。
     */
    public static String amapPhotoUrl(JSONObject poi) {
        JSONArray photos = poi.getJSONArray("photos");
        if (photos == null || photos.isEmpty()) {
            return null;
        }
        Object first = photos.get(0);
        if (first instanceof JSONObject photo) {
            Object url = photo.get("url");
            return url instanceof String s && !s.isBlank() ? s : null;
        }
        return null;
    }

    /** 飞猪图片字段：search-hotel/search-poi 为 mainPic（skill 协议），keyword-search 形态兜底 picUrl */
    public static String flyaiImageUrl(JSONObject raw) {
        String pic = raw.getString("mainPic");
        if (pic == null || pic.isBlank()) {
            pic = raw.getString("picUrl");
        }
        return normalizeUrl(pic);
    }
}
