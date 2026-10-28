package com.travelscope.price;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

/** 精确金额值对象；禁止使用 double。 */
public record Money(BigDecimal amount, String currency) {
    public Money {
        Objects.requireNonNull(amount, "amount");
        if (amount.signum() < 0) throw new IllegalArgumentException("amount 不能为负数");
        currency = currency == null || currency.isBlank() ? "CNY" : currency.toUpperCase();
        Currency.getInstance(currency);
    }

    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money multiply(long quantity) {
        if (quantity < 0) throw new IllegalArgumentException("quantity 不能为负数");
        return new Money(amount.multiply(BigDecimal.valueOf(quantity)), currency);
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other");
        if (!currency.equals(other.currency)) throw new IllegalArgumentException("币种不一致");
    }
}
