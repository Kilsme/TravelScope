package com.travelscope.service.retrieval;

import com.alibaba.fastjson2.JSON;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 检索变体生成器（S1.7，v4 计划 §5.3 第 1 步）：由目的地与偏好关键词生成
 * 3~5 个检索变体，供 S1.8 PoiRetrievalService 并行双路检索——纯代码规则，零 LLM。
 * <p>
 * 生成规则（顺序即优先级）：
 * <ol>
 *   <li>目的地本体；</li>
 *   <li>目的地别名（词典 {@code resources/retrieval/city-aliases.json}，可经构造器注入替换）；</li>
 *   <li>偏好展开词（如 自然风景→[自然风光,山水,公园]，<b>替换偏好原文</b>——变体追求
 *       换词多样性，原文与变体重复时无增益）；</li>
 *   <li>兜底类别词（目的地+「景点」、目的地+「必去」）。</li>
 * </ol>
 * LinkedHashSet 去重保序，上限 {@link #MAX_VARIANTS}。
 * </p>
 * <p>
 * <b>退化路径</b>：别名与全部偏好展开都未命中词典时，返回
 * {@code [目的地, 目的地+景点, 偏好原文…]}（偏好原文此时是唯一的多样性来源）——
 * 永不返回空。
 * </p>
 */
public class QueryExpander {

    /** 变体上限（v4 计划 §5.3：生成 3~5 个检索变体） */
    static final int MAX_VARIANTS = 5;

    private static final String DICT_RESOURCE = "/retrieval/city-aliases.json";

    private final Map<String, List<String>> cityAliases;
    private final Map<String, List<String>> preferenceExpansions;

    /** 默认构造：加载随包词典（classpath /retrieval/city-aliases.json；缺失/畸形快速失败） */
    public QueryExpander() {
        Dictionary dict = loadDictionary();
        this.cityAliases = dict.cityAliases();
        this.preferenceExpansions = dict.preferenceExpansions();
    }

    /** 注入构造：测试与自定义词典（S1.8 可按需替换词条） */
    public QueryExpander(Map<String, List<String>> cityAliases,
                         Map<String, List<String>> preferenceExpansions) {
        this.cityAliases = Map.copyOf(Objects.requireNonNull(cityAliases, "cityAliases"));
        this.preferenceExpansions = Map.copyOf(
                Objects.requireNonNull(preferenceExpansions, "preferenceExpansions"));
    }

    /**
     * 生成检索变体。
     *
     * @param destination 目的地（非空，如「杭州」）
     * @param preferences 偏好关键词（可空；null/空白项被过滤）
     * @return 3~5 个去重保序的检索变体；词典未命中时退化为
     *         [目的地, 目的地+景点, 偏好原文…]——永不返回空
     * @throws IllegalArgumentException destination 为 null/空白
     */
    public List<String> expand(String destination, List<String> preferences) {
        if (destination == null || destination.isBlank()) {
            throw new IllegalArgumentException("destination 不能为空");
        }
        String dest = destination.trim();
        List<String> prefs = preferences == null ? List.of()
                : preferences.stream()
                        .filter(p -> p != null && !p.isBlank())
                        .map(String::trim)
                        .toList();

        List<String> aliases = cityAliases.getOrDefault(dest, List.of());
        // 偏好 → 展开词（LinkedHashMap 保序；未命中的偏好记空列表）
        Map<String, List<String>> expansions = new LinkedHashMap<>();
        for (String pref : prefs) {
            expansions.putIfAbsent(pref, preferenceExpansions.getOrDefault(pref, List.of()));
        }
        boolean anyHit = !aliases.isEmpty()
                || expansions.values().stream().anyMatch(list -> !list.isEmpty());

        LinkedHashSet<String> variants = new LinkedHashSet<>();
        if (!anyHit) {
            // 退化路径：词典全未命中——偏好原文是唯一多样性来源
            variants.add(dest);
            variants.add(dest + "景点");
            prefs.forEach(variants::add);
            return List.copyOf(variants);
        }
        // 命中路径：本体 → 别名 → 偏好展开词（替换原文）→ 兜底类别词
        variants.add(dest);
        variants.addAll(aliases);
        expansions.values().forEach(variants::addAll);
        variants.add(dest + "景点");
        variants.add(dest + "必去");
        return variants.stream().limit(MAX_VARIANTS).toList();
    }

    /** 随包词典加载（缺失/畸形视为打包错误，快速失败） */
    private static Dictionary loadDictionary() {
        try (InputStream in = QueryExpander.class.getResourceAsStream(DICT_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("检索词典缺失: classpath " + DICT_RESOURCE);
            }
            return JSON.parseObject(in.readAllBytes(), Dictionary.class);
        } catch (IOException e) {
            throw new IllegalStateException("检索词典读取失败: " + DICT_RESOURCE, e);
        }
    }

    /** 词典文件结构（resources/retrieval/city-aliases.json）；缺省段视为空词典 */
    record Dictionary(Map<String, List<String>> cityAliases,
                      Map<String, List<String>> preferenceExpansions) {

        public Dictionary {
            cityAliases = cityAliases == null ? Map.of() : cityAliases;
            preferenceExpansions = preferenceExpansions == null ? Map.of() : preferenceExpansions;
        }
    }
}
