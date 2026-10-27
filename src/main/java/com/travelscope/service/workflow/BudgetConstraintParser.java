package com.travelscope.service.workflow;

import com.travelscope.collab.BudgetConstraint;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 将预算原文归一化为最小可计算约束；无法确认金额口径时显式标记歧义。 */
@Component
public class BudgetConstraintParser {
    private static final Pattern NUMBER = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(万|千|k|K)?");
    private static final Pattern RANGE = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*(万|千|k|K)?\\s*[-~到至]\\s*(\\d+(?:\\.\\d+)?)\\s*(万|千|k|K)?");

    public ParseResult parse(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return new ParseResult(null, false, null);
        }
        String raw = rawText.trim();
        String compact = raw.replaceAll("\\s+", "");
        BudgetConstraint.Scope scope = scopeOf(compact);
        BudgetConstraint.TravelStyle style = styleOf(compact);
        BigDecimal amount = firstAmount(compact);
        BigDecimal min = null;
        BigDecimal max = null;
        Matcher range = RANGE.matcher(compact);
        if (range.find()) {
            min = scaled(range.group(1), range.group(2));
            max = scaled(range.group(3), range.group(4));
        } else if (amount != null) {
            if (containsAny(compact, "以上", "起", "不少于", "至少")) {
                min = amount;
            } else if (containsAny(compact, "左右", "大约", "约")) {
                min = amount.multiply(new BigDecimal("0.9"));
                max = amount.multiply(new BigDecimal("1.1"));
            } else {
                max = amount;
            }
        }
        BudgetConstraint constraint = new BudgetConstraint(scope, min, max, "CNY", style, raw);
        boolean ambiguous = amount != null && scope == null;
        return new ParseResult(constraint, ambiguous,
                ambiguous ? "BUDGET_SCOPE_AMBIGUOUS" : null);
    }

    private static BudgetConstraint.Scope scopeOf(String text) {
        if (containsAny(text, "酒店每晚", "住宿每晚", "房费每晚", "每晚酒店")) {
            return BudgetConstraint.Scope.HOTEL_PER_NIGHT;
        }
        if (containsAny(text, "人均", "每人", "一个人")) {
            return BudgetConstraint.Scope.PER_PERSON_TOTAL;
        }
        if (containsAny(text, "总共", "总预算", "合计", "全程")
                || text.matches(".*[一二两三四五六七八九十\\d]+个人.*")) {
            return BudgetConstraint.Scope.GROUP_TOTAL;
        }
        return null;
    }

    private static BudgetConstraint.TravelStyle styleOf(String text) {
        if (containsAny(text, "高端", "豪华", "奢华")) return BudgetConstraint.TravelStyle.LUXURY;
        if (containsAny(text, "舒适", "品质", "中档")) return BudgetConstraint.TravelStyle.COMFORT;
        if (containsAny(text, "穷游", "经济", "省钱", "低预算")) return BudgetConstraint.TravelStyle.BUDGET;
        return null;
    }

    private static BigDecimal firstAmount(String text) {
        Matcher matcher = NUMBER.matcher(text);
        if (!matcher.find()) return null;
        return scaled(matcher.group(1), matcher.group(2));
    }

    private static BigDecimal scaled(String raw, String unit) {
        BigDecimal value = new BigDecimal(raw);
        if ("万".equals(unit)) value = value.multiply(new BigDecimal("10000"));
        if ("千".equals(unit) || (unit != null && "k".equalsIgnoreCase(unit))) {
            value = value.multiply(new BigDecimal("1000"));
        }
        return value.stripTrailingZeros();
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) if (text.contains(value)) return true;
        return false;
    }

    public record ParseResult(BudgetConstraint constraint, boolean requiresClarification,
                              String reasonCode) {
    }
}
