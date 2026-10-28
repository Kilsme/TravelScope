package com.travelscope.service.price;

import com.alibaba.fastjson2.JSON;
import com.travelscope.collab.HotelSelection;
import com.travelscope.collab.TransportSelection;
import com.travelscope.price.Money;
import com.travelscope.price.PriceFact;
import com.travelscope.price.PriceUnit;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PriceFactTest {
    @Test
    void moneyAndPriceFactJsonRoundTripKeepsDecimalExactly() {
        PriceFact fact = new PriceFact(new Money(new BigDecimal("57.10"), "CNY"),
                PriceUnit.PER_PERSON_TRIP, "飞猪", Instant.parse("2026-10-28T03:00:00Z"), false);

        PriceFact parsed = JSON.parseObject(JSON.toJSONString(fact), PriceFact.class);

        assertThat(parsed).isEqualTo(fact);
        assertThat(parsed.amount().toPlainString()).isEqualTo("57.10");
    }

    @Test
    void negativeMoneyIsRejected() {
        assertThatThrownBy(() -> new Money(new BigDecimal("-0.01"), "CNY"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void adaptersUnifyTransportHotelAndTicketPrices() {
        Instant fetchedAt = Instant.parse("2026-10-28T03:00:00Z");
        var transport = new TransportSelection.TransportOption("G1", "TRAIN", new BigDecimal("57.00"),
                LocalDateTime.parse("2026-10-25T08:00"), LocalDateTime.parse("2026-10-25T09:00"),
                60, 0, true, "飞猪", null);
        var hotel = new HotelSelection.HotelOption("湖景酒店", new BigDecimal("600.00"), 120.1, 30.2,
                4, true, null, null, 500, true, true, "飞猪", null);

        assertThat(PriceFactAdapters.fromTransport(transport, fetchedAt).unit())
                .isEqualTo(PriceUnit.PER_PERSON_TRIP);
        assertThat(PriceFactAdapters.fromHotel(hotel, fetchedAt).unit()).isEqualTo(PriceUnit.PER_ROOM_NIGHT);
        assertThat(PriceFactAdapters.fromTicket("¥40.00", "飞猪", fetchedAt).amount())
                .isEqualByComparingTo("40.00");
    }
}
