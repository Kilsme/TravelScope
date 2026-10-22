package com.travelscope.agent.tools;

import com.alibaba.fastjson2.JSONObject;
import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ImageCollector 单测（v5-F2 图片透传）：URL 归一/去重/收集器注入/图集组装与正文去重
 * + 高德 photos 类型漂移与飞猪 mainPic/picUrl 兜底解析。
 */
class ImageCollectorTest {

    // ---------- URL 归一 ----------

    @Test
    @DisplayName("http→https 归一（高德 photos 协议混用 http 居多）；空白返回 null")
    void normalizeUrl() {
        assertThat(ImageCollector.normalizeUrl("http://store.is.autonavi.com/showpic/abc"))
                .isEqualTo("https://store.is.autonavi.com/showpic/abc");
        assertThat(ImageCollector.normalizeUrl("  https://img.alicdn.com/x.jpg  "))
                .isEqualTo("https://img.alicdn.com/x.jpg");
        assertThat(ImageCollector.normalizeUrl(null)).isNull();
        assertThat(ImageCollector.normalizeUrl("  ")).isNull();
    }

    // ---------- 高德 photos 解析（类型漂移兼容） ----------

    @Test
    @DisplayName("photos[0].url 正常解析")
    void amapPhotoUrlParsesFirst() {
        JSONObject poi = new JSONObject();
        poi.put("photos", List.of(
                new JSONObject(java.util.Map.of("title", "灵隐寺", "url",
                        "http://store.is.autonavi.com/showpic/abc")),
                new JSONObject(java.util.Map.of("title", "x", "url", "https://other/pic.jpg"))));
        assertThat(ImageCollector.amapPhotoUrl(poi)).isEqualTo("http://store.is.autonavi.com/showpic/abc");
    }

    @Test
    @DisplayName("photos 空数组/缺失/url 为空数组（高德类型漂移）一律按无图 null")
    void amapPhotoUrlHandlesDrift() {
        JSONObject emptyPhotos = new JSONObject();
        emptyPhotos.put("photos", List.of());
        assertThat(ImageCollector.amapPhotoUrl(emptyPhotos)).isNull();

        JSONObject noPhotos = new JSONObject();
        assertThat(ImageCollector.amapPhotoUrl(noPhotos)).isNull();

        // url 无值时返回空数组而非字符串（官方文档明确的类型漂移）
        JSONObject urlAsArray = new JSONObject();
        JSONObject photo = new JSONObject();
        photo.put("url", List.of());
        urlAsArray.put("photos", List.of(photo));
        assertThat(ImageCollector.amapPhotoUrl(urlAsArray)).isNull();
    }

    // ---------- 飞猪图片字段兜底 ----------

    @Test
    @DisplayName("mainPic 优先、picUrl 兜底、都没有为 null")
    void flyaiImageUrlFallback() {
        JSONObject both = new JSONObject();
        both.put("mainPic", "https://img.alicdn.com/a.jpg");
        both.put("picUrl", "https://img.alicdn.com/b.jpg");
        assertThat(ImageCollector.flyaiImageUrl(both)).isEqualTo("https://img.alicdn.com/a.jpg");

        JSONObject onlyPicUrl = new JSONObject();
        onlyPicUrl.put("picUrl", "https://img.alicdn.com/b.jpg");
        assertThat(ImageCollector.flyaiImageUrl(onlyPicUrl)).isEqualTo("https://img.alicdn.com/b.jpg");

        assertThat(ImageCollector.flyaiImageUrl(new JSONObject())).isNull();
    }

    // ---------- 收集：注入 + 去重 + null ctx 安全 ----------

    @Test
    @DisplayName("collectFrom：ctx 无收集器/ctx 为 null 静默跳过；有收集器按条目收集")
    void collectFromCtxPatterns() {
        // null ctx（程序内 3 参重载路径）不抛
        ImageCollector.collectFrom(null, List.of(new JSONObject(java.util.Map.of(
                "name", "灵隐寺", "imageUrl", "https://x/1.jpg"))));

        // ctx 存在但未注入收集器 → 跳过
        RuntimeContext bare = RuntimeContext.builder().userId("u1").sessionId("c1").build();
        ImageCollector.collectFrom(bare, List.of(new JSONObject(java.util.Map.of(
                "name", "灵隐寺", "imageUrl", "https://x/1.jpg"))));

        // 注入收集器 → 收集 imageUrl 非空条目（无图条目跳过）
        ImageCollector collector = new ImageCollector();
        RuntimeContext ctx = RuntimeContext.builder().userId("u1").sessionId("c1").build();
        ctx.put(ImageCollector.CTX_KEY, collector);
        JSONObject withPic = new JSONObject();
        withPic.put("name", "灵隐寺");
        withPic.put("imageUrl", "https://x/1.jpg");
        withPic.put("source", "高德");
        JSONObject noPic = new JSONObject();
        noPic.put("name", "无名点");
        noPic.put("source", "高德");
        ImageCollector.collectFrom(ctx, List.of(withPic, noPic));

        assertThat(collector.refs()).hasSize(1);
        assertThat(collector.refs().get(0).url()).isEqualTo("https://x/1.jpg");
        assertThat(collector.refs().get(0).name()).isEqualTo("灵隐寺");
        assertThat(collector.refs().get(0).source()).isEqualTo("高德");
    }

    @Test
    @DisplayName("add 按 URL 去重（同名不同 URL 保留，同 URL 只收一次）")
    void addDedupesByUrl() {
        ImageCollector collector = new ImageCollector();
        collector.add("https://x/1.jpg", "灵隐寺", "高德");
        collector.add("https://x/1.jpg", "灵隐寺（重复）", "飞猪");
        collector.add("http://x/2.jpg", "断桥", "高德");

        assertThat(collector.refs()).hasSize(2);
        assertThat(collector.refs().get(1).url()).isEqualTo("https://x/2.jpg");
    }

    // ---------- 图集组装 ----------

    @Test
    @DisplayName("toGalleryMarkdown：图集节格式 + 与已有正文按 URL 去重 + 全被去重时空串")
    void galleryMarkdown() {
        List<ImageCollector.ImageRef> refs = List.of(
                new ImageCollector.ImageRef("https://x/1.jpg", "灵隐寺", "高德"),
                new ImageCollector.ImageRef("https://x/2.jpg", "断桥[残雪]", "飞猪"),
                new ImageCollector.ImageRef(null, "无图", "高德"));

        String gallery = ImageCollector.toGalleryMarkdown(refs, "已有正文");
        assertThat(gallery).contains("📷 相关图片");
        assertThat(gallery).contains("![灵隐寺](https://x/1.jpg)");
        // alt 内方括号转全角防 markdown 冲突
        assertThat(gallery).contains("![断桥（残雪）](https://x/2.jpg)");
        assertThat(gallery).doesNotContain("无图");

        // 已在正文出现过的 URL 不重复附图
        String deduped = ImageCollector.toGalleryMarkdown(refs, "正文里已有 ![灵隐寺](https://x/1.jpg)");
        assertThat(deduped).doesNotContain("https://x/1.jpg");
        assertThat(deduped).contains("https://x/2.jpg");

        // 全部被正文覆盖 → 空串
        assertThat(ImageCollector.toGalleryMarkdown(refs, "1.jpg 与 2.jpg 都在 https://x/1.jpg https://x/2.jpg"))
                .isEmpty();
    }
}
