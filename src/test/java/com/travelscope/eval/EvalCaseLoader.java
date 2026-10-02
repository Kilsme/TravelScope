package com.travelscope.eval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * EvalCase 用例库加载器（FR-S15）：读取 classpath {@link #CASE_DIR} 目录下的 JSON 用例文件，
 * 严格校验后映射为 {@link EvalCase}。纯 Java 实现，无外部依赖。
 *
 * <p>校验策略 fail-fast：未知字段、未知枚举取值、缺失必填项等一律抛 {@link IllegalArgumentException}，
 * 错误消息带来源文件与 caseId，让自生长用例库的笔误在加载期暴露。
 */
public final class EvalCaseLoader {

    /** 用例库在测试资源中的目录名（classpath 相对路径） */
    public static final String CASE_DIR = "evalcases";

    private static final Pattern CASE_ID_PATTERN = Pattern.compile("EC-\\d{3,}");
    private static final Set<String> TOP_LEVEL_KEYS = Set.of("caseId", "category", "userMessages", "expectations");
    private static final Set<String> EXPECTATION_KEYS = Set.of("rules", "rubric");
    private static final Set<String> RULE_KEYS = Set.of("type", "desc", "params");
    private static final Set<String> RUBRIC_KEYS = Set.of("minTotal");

    private EvalCaseLoader() {
    }

    /**
     * 加载全部用例：扫描 classpath evalcases/ 目录下的 *.json，按 caseId 数字升序返回。
     *
     * @throws IllegalStateException 目录不存在或不可枚举
     * @throws IllegalArgumentException 任一用例非法，或 caseId 重复
     */
    public static List<EvalCase> loadAll() {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = EvalCaseLoader.class.getClassLoader();
        }
        URL dir = classLoader.getResource(CASE_DIR);
        if (dir == null) {
            throw new IllegalStateException("用例库目录不存在: classpath:" + CASE_DIR);
        }
        if (!"file".equals(dir.getProtocol())) {
            throw new IllegalStateException("用例库目录不可枚举（非文件系统）: " + dir);
        }
        Path dirPath;
        try {
            dirPath = Path.of(dir.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("用例库目录路径非法: " + dir, e);
        }
        List<EvalCase> cases = new ArrayList<>();
        try (Stream<Path> files = Files.list(dirPath)) {
            files.filter(file -> file.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .forEach(file -> cases.add(load(file)));
        } catch (IOException e) {
            throw new UncheckedIOException("扫描用例库目录失败: " + dir, e);
        }
        Set<String> seen = new HashSet<>();
        for (EvalCase evalCase : cases) {
            if (!seen.add(evalCase.caseId())) {
                throw new IllegalArgumentException("用例编号重复: " + evalCase.caseId());
            }
        }
        cases.sort(Comparator.comparingInt(c -> Integer.parseInt(c.caseId().substring(3))));
        return List.copyOf(cases);
    }

    /**
     * 加载单个用例文件，要求文件名主干（去掉 .json）与 caseId 一致。
     */
    public static EvalCase load(Path file) {
        String fileName = file.getFileName().toString();
        if (!fileName.endsWith(".json")) {
            throw new IllegalArgumentException("用例文件必须以 .json 结尾: " + fileName);
        }
        String json;
        try {
            json = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取用例文件失败: " + file, e);
        }
        EvalCase evalCase = fromJson(json, fileName);
        String stem = fileName.substring(0, fileName.length() - ".json".length());
        if (!stem.equals(evalCase.caseId())) {
            throw new IllegalArgumentException(
                    "文件名与 caseId 不一致: 文件 " + stem + " vs caseId " + evalCase.caseId());
        }
        return evalCase;
    }

    /**
     * 解析并严格校验一段用例 JSON。
     *
     * @param source 来源描述（如文件名），用于错误消息定位
     */
    static EvalCase fromJson(String json, String source) {
        Object root = MiniJson.parse(json);
        if (!(root instanceof Map<?, ?> rootMap)) {
            throw new IllegalArgumentException(source + ": 用例必须是 JSON 对象");
        }
        requireKeys(rootMap, TOP_LEVEL_KEYS, TOP_LEVEL_KEYS, source);

        String caseId = requiredString(rootMap, "caseId", source);
        if (!CASE_ID_PATTERN.matcher(caseId).matches()) {
            throw new IllegalArgumentException(source + ": caseId 非法（应为 EC-XXX 格式）: " + caseId);
        }
        String where = source + "[" + caseId + "]";

        EvalCategory category = parseEnum(rootMap.get("category"), EvalCategory.class, where + ".category");
        List<String> userMessages = parseUserMessages(rootMap.get("userMessages"), where);

        Object expectationsRaw = rootMap.get("expectations");
        if (!(expectationsRaw instanceof Map<?, ?> expectationsMap)) {
            throw new IllegalArgumentException(where + ": expectations 必须是对象");
        }
        requireKeys(expectationsMap, EXPECTATION_KEYS, EXPECTATION_KEYS, where + ".expectations");

        List<EvalCase.Rule> rules = parseRules(expectationsMap.get("rules"), where);
        EvalCase.Rubric rubric = parseRubric(expectationsMap.get("rubric"), where);
        return new EvalCase(caseId, category, userMessages, new EvalCase.Expectations(rules, rubric));
    }

    /** 必填字段齐全且无未知字段：required ⊆ keys ⊆ allowed */
    private static void requireKeys(Map<?, ?> map, Set<String> required, Set<String> allowed, String where) {
        for (String key : required) {
            if (!map.containsKey(key)) {
                throw new IllegalArgumentException(where + ": 缺少必填字段 '" + key + "'");
            }
        }
        for (Object key : map.keySet()) {
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException(where + ": 存在未知字段 '" + key + "'（允许: " + allowed + "）");
            }
        }
    }

    private static String requiredString(Map<?, ?> map, String key, String where) {
        if (!(map.get(key) instanceof String value)) {
            throw new IllegalArgumentException(where + ": '" + key + "' 必须是字符串");
        }
        return value;
    }

    private static <E extends Enum<E>> E parseEnum(Object raw, Class<E> type, String where) {
        if (!(raw instanceof String value)) {
            throw new IllegalArgumentException(where + ": 必须是字符串（已知取值: " + List.of(type.getEnumConstants()) + "）");
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    where + ": 未知取值 '" + value + "'（已知取值: " + List.of(type.getEnumConstants()) + "）");
        }
    }

    private static List<String> parseUserMessages(Object raw, String where) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException(where + ": userMessages 必须是非空数组");
        }
        List<String> messages = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof String message) || message.isBlank()) {
                throw new IllegalArgumentException(where + ": userMessages[" + i + "] 必须是非空白字符串");
            }
            messages.add(message);
        }
        return List.copyOf(messages);
    }

    private static List<EvalCase.Rule> parseRules(Object raw, String where) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException(where + ": expectations.rules 必须是非空数组");
        }
        List<EvalCase.Rule> rules = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            String ruleWhere = where + ".rules[" + i + "]";
            if (!(list.get(i) instanceof Map<?, ?> ruleMap)) {
                throw new IllegalArgumentException(ruleWhere + ": 规则必须是对象");
            }
            requireKeys(ruleMap, Set.of("type"), RULE_KEYS, ruleWhere);
            RuleType type = parseEnum(ruleMap.get("type"), RuleType.class, ruleWhere + ".type");
            String desc = ruleMap.get("desc") == null ? null : requiredString(ruleMap, "desc", ruleWhere);
            Map<String, String> params = parseParams(ruleMap.get("params"), ruleWhere + ".params");
            if (type == RuleType.CONSTRAINT_COVERED) {
                String keyword = params.get("keyword");
                if (keyword == null || keyword.isBlank()) {
                    throw new IllegalArgumentException(ruleWhere + ": CONSTRAINT_COVERED 规则必须提供非空 params.keyword");
                }
            }
            rules.add(new EvalCase.Rule(type, desc, params));
        }
        return List.copyOf(rules);
    }

    private static Map<String, String> parseParams(Object raw, String where) {
        if (raw == null) {
            return Map.of();
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(where + ": 必须是对象");
        }
        Map<String, String> params = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getValue() instanceof String value)) {
                throw new IllegalArgumentException(where + "." + entry.getKey() + ": 参数值必须是字符串");
            }
            params.put(String.valueOf(entry.getKey()), value);
        }
        return Map.copyOf(params);
    }

    private static EvalCase.Rubric parseRubric(Object raw, String where) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(where + ": expectations.rubric 必须是对象");
        }
        String rubricWhere = where + ".rubric";
        requireKeys(map, RUBRIC_KEYS, RUBRIC_KEYS, rubricWhere);
        if (!(map.get("minTotal") instanceof Long value)) {
            throw new IllegalArgumentException(rubricWhere + ".minTotal: 必须是整数，实际: " + map.get("minTotal"));
        }
        if (value < 1 || value > 100) {
            throw new IllegalArgumentException(rubricWhere + ".minTotal: 取值必须是 1~100 的整数，实际: " + value);
        }
        return new EvalCase.Rubric((int) value.longValue());
    }
}
