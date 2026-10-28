package com.travelscope.service.price;

import com.travelscope.collab.HotelSelection;
import com.travelscope.collab.TransportSelection;
import com.travelscope.price.Money;
import com.travelscope.price.PriceFact;
import com.travelscope.price.PriceUnit;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/** 既有票价、酒店价和门票文本到统一 PriceFact 的边界适配器。 */
public final class PriceFactAdapters {
    private PriceFactAdapters() {}

    public static PriceFact fromTransport(TransportSelection.TransportOption option, Instant fetchedAt) {
        return new PriceFact(new Money(option.price(), "CNY"), PriceUnit.PER_PERSON_TRIP,
                option.source(), fetchedAt, false);
    }

    public static PriceFact fromHotel(HotelSelection.HotelOption option, Instant fetchedAt) {
        return new PriceFact(new Money(option.nightlyPrice(), "CNY"), PriceUnit.PER_ROOM_NIGHT,
                option.source(), fetchedAt, false);
    }

    public static PriceFact fromTicket(String rawPrice, String source, Instant fetchedAt) {
        Objects.requireNonNull(rawPrice, "rawPrice");
        String numeric = rawPrice.replaceAll("[^0-9.]", "");
        if (numeric.isBlank()) throw new IllegalArgumentException("无法解析门票价格: " + rawPrice);
        return new PriceFact(new Money(new BigDecimal(numeric), "CNY"), PriceUnit.PER_TICKET,
                source, fetchedAt, rawPrice.contains("起") || rawPrice.contains("约"));
    }
}
