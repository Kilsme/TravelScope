package com.travelscope.price;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/** 带单位、来源和时效的统一金额事实；JSON 契约保持 amount/currency 扁平字段。 */
public record PriceFact(BigDecimal amount, String currency, PriceUnit unit, String source,
                        Instant fetchedAt, boolean estimated) {
    public PriceFact {
        Money validated = new Money(amount, currency);
        amount = validated.amount();
        currency = validated.currency();
        Objects.requireNonNull(unit, "unit");
        if (source == null || source.isBlank()) throw new IllegalArgumentException("source 不能为空");
        Objects.requireNonNull(fetchedAt, "fetchedAt");
    }

    public PriceFact(Money money, PriceUnit unit, String source,
                     Instant fetchedAt, boolean estimated) {
        this(money.amount(), money.currency(), unit, source, fetchedAt, estimated);
    }

    public Money toMoney() {
        return new Money(amount, currency);
    }
}
