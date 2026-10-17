package com.travelscope.service.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S1.7 验证：QueryExpander 检索变体生成。
 * 覆盖：命中词典 5 变体（上限截断）/ 未命中退化 3 项 / 去重保序 /
 * 默认构造加载随包词典 / 空白目的地快速失败。
 */
class QueryExpanderTest {

    private static QueryExpander expander() {
        return new QueryExpander(
                Map.of("杭州", List.of("西湖", "杭城")),
                Map.of("自然风景", List.of("自然风光", "山水", "公园")));
    }

    // ---------- 用例 1：命中词典 → 5 变体（上限截断） ----------
    @Test
    void dictionaryHitProducesFiveVariants() {
        List<String> variants = expander().expand("杭州", List.of("自然风景"));

        assertThat(variants).containsExactly("杭州", "西湖", "杭城", "自然风光", "山水");
        assertThat(variants).hasSize(5);
    }

    // ---------- 用例 2：词典未命中 → 退化 3 项，永不返回空 ----------
    @Test
    void dictionaryMissDegradesToThreeItems() {
        List<String> variants = expander().expand("马鞍山", List.of("小众秘境"));

        assertThat(variants).containsExactly("马鞍山", "马鞍山景点", "小众秘境");
    }

    // ---------- 用例 3：去重保序（偏好原文与展开词重叠时只出现一次） ----------
    @Test
    void dedupPreservesOrder() {
        // 「自然风光」既是偏好（其展开未命中）又是「自然风景」的展开词——只应出现一次
        List<String> variants = expander().expand("杭州", List.of("自然风景", "自然风光"));

        assertThat(variants).containsExactly("杭州", "西湖", "杭城", "自然风光", "山水");
        assertThat(variants.stream().distinct().count())
                .as("无重复").isEqualTo(variants.size());
    }

    // ---------- 用例 4：默认构造加载随包词典 ----------
    @Test
    void defaultConstructorLoadsBundledDictionary() {
        QueryExpander expander = new QueryExpander();

        // 随包词典含杭州别名：命中路径且西湖在前
        assertThat(expander.expand("杭州", List.of())).contains("西湖", "杭城");
        // 随包词典含偏好展开
        assertThat(expander.expand("杭州", List.of("人文古迹"))).contains("古迹", "博物馆");
        // 未收录城市走退化路径（无偏好时两项，仍不为空）
        assertThat(expander.expand("鹤岗", List.of())).containsExactly("鹤岗", "鹤岗景点");
    }

    // ---------- 用例 5：空白目的地快速失败 ----------
    @Test
    void blankDestinationRejected() {
        assertThatThrownBy(() -> expander().expand("  ", List.of("自然风景")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("destination");
    }
}
