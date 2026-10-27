package com.travelscope.service.poi;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * POI 名称纯函数归一化：只生成比较键，不决定实体是否删除或合并。
 * 城市前缀与景区弱后缀仅从名称边界剥离，主体词全部保留，避免父景区和子景点误合并。
 */
public final class PoiNameNormalizer {
    private static final Pattern PARENTHESIZED = Pattern.compile("[（(]([^()（）]+)[）)]");
    private static final Pattern WEAK_SUFFIX = Pattern.compile("(?:风景名胜区|风景区|景区)$");
    private static final Pattern SPACES_AND_PUNCT = Pattern.compile("[\\s·•・_\\-—]+", Pattern.UNICODE_CHARACTER_CLASS);

    /** 本 Slice 所需的确定性常用字映射；先经 NFKC，再统一常见繁体 POI 字符。 */
    private static final Map<Character, Character> TRADITIONAL_TO_SIMPLIFIED = Map.ofEntries(
            Map.entry('臺', '台'), Map.entry('台', '台'), Map.entry('宮', '宫'),
            Map.entry('館', '馆'), Map.entry('風', '风'), Map.entry('區', '区'),
            Map.entry('園', '园'), Map.entry('門', '门'), Map.entry('樓', '楼'),
            Map.entry('觀', '观'), Map.entry('廟', '庙'), Map.entry('龍', '龙'),
            Map.entry('馬', '马'), Map.entry('島', '岛'), Map.entry('廣', '广'),
            Map.entry('東', '东'), Map.entry('華', '华'), Map.entry('歷', '历'),
            Map.entry('史', '史'), Map.entry('藝', '艺'), Map.entry('術', '术'));

    private PoiNameNormalizer() {
    }

    public static Result normalize(String originalName, String city) {
        if (originalName == null || originalName.isBlank()) {
            throw new IllegalArgumentException("POI 名称不能为空");
        }
        String normalized = normalizeCharacters(originalName).trim();
        List<String> aliases = new ArrayList<>();
        Matcher matcher = PARENTHESIZED.matcher(normalized);
        while (matcher.find()) {
            String alias = matcher.group(1).trim();
            if (!alias.isBlank()) aliases.add(alias);
        }
        String withoutAliases = matcher.replaceAll("").trim();
        String canonicalName = WEAK_SUFFIX.matcher(withoutAliases).replaceFirst("").trim();
        if (canonicalName.isBlank()) {
            canonicalName = withoutAliases;
        }
        String keyName = stripCityPrefix(canonicalName, city);
        String key = SPACES_AND_PUNCT.matcher(keyName).replaceAll("")
                .toLowerCase(Locale.ROOT);
        if (key.isBlank()) {
            throw new IllegalArgumentException("POI 名称归一化后为空");
        }
        return new Result(originalName, canonicalName, key, List.copyOf(aliases));
    }

    private static String stripCityPrefix(String name, String city) {
        if (city == null || city.isBlank()) return name;
        String normalizedCity = normalizeCharacters(city).trim();
        String cityBase = normalizedCity.endsWith("市")
                ? normalizedCity.substring(0, normalizedCity.length() - 1) : normalizedCity;
        if (!cityBase.isBlank() && name.startsWith(cityBase) && name.length() > cityBase.length()) {
            return name.substring(cityBase.length()).trim();
        }
        return name;
    }

    private static String normalizeCharacters(String value) {
        String nfkc = Normalizer.normalize(value, Normalizer.Form.NFKC);
        StringBuilder result = new StringBuilder(nfkc.length());
        for (int i = 0; i < nfkc.length(); i++) {
            char c = nfkc.charAt(i);
            result.append(TRADITIONAL_TO_SIMPLIFIED.getOrDefault(c, c));
        }
        return result.toString();
    }

    public record Result(String originalName, String canonicalName,
                         String normalizationKey, List<String> aliases) {
        public Result {
            Objects.requireNonNull(originalName, "originalName");
            Objects.requireNonNull(canonicalName, "canonicalName");
            Objects.requireNonNull(normalizationKey, "normalizationKey");
            aliases = List.copyOf(Objects.requireNonNull(aliases, "aliases"));
        }
    }
}
