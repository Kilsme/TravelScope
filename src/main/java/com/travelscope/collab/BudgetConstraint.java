package com.travelscope.collab;

import java.math.BigDecimal;

/** 可计算的预算约束；rawText 始终保留用户原文。 */
public record BudgetConstraint(
        Scope scope,
        BigDecimal min,
        BigDecimal max,
        String currency,
        TravelStyle travelStyle,
        String rawText) {

    public enum Scope {
        PER_PERSON_TOTAL,
        GROUP_TOTAL,
        HOTEL_PER_NIGHT
    }

    public enum TravelStyle {
        BUDGET,
        COMFORT,
        LUXURY
    }

    public BudgetConstraint {
        currency = currency == null || currency.isBlank() ? "CNY" : currency;
        if (min != null && min.signum() < 0) {
            throw new IllegalArgumentException("min 不能为负数");
        }
        if (max != null && max.signum() < 0) {
            throw new IllegalArgumentException("max 不能为负数");
        }
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw new IllegalArgumentException("min 不能大于 max");
        }
        if (rawText == null || rawText.isBlank()) {
            throw new IllegalArgumentException("rawText 不能为空");
        }
    }
}
