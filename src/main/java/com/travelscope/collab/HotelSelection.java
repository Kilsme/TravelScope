package com.travelscope.collab;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import com.travelscope.price.PriceFact;
import com.travelscope.price.PriceUnit;
import java.util.List;
import java.util.Objects;

/** 确定性酒店选择契约；selected 是路线与预算共同使用的唯一记录。 */
public record HotelSelection(int schemaVersion, HotelOption selected,
                             List<HotelOption> alternatives, List<String> explanation) {
    public static final int SCHEMA_VERSION = 4;
    public HotelSelection {
        if (schemaVersion != SCHEMA_VERSION) throw new IllegalArgumentException("schemaVersion 必须为 4");
        Objects.requireNonNull(selected, "selected");
        alternatives = List.copyOf(Objects.requireNonNull(alternatives, "alternatives"));
        explanation = List.copyOf(Objects.requireNonNull(explanation, "explanation"));
        if (alternatives.size() > 2) throw new IllegalArgumentException("alternatives 最多两个");
    }
    public HotelSelection(HotelOption selected, List<HotelOption> alternatives, List<String> explanation) {
        this(SCHEMA_VERSION, selected, alternatives, explanation);
    }

    public record HotelOption(String name, BigDecimal nightlyPrice, Double lng, Double lat,
                              int starLevel, boolean continuousAvailability,
                              LocalDate checkIn, LocalDate checkOut, double stationDistanceMeters,
                              boolean metroAccessible, boolean refundable,
                              String source, String bookingUrl) {
        public HotelOption {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(nightlyPrice, "nightlyPrice");
            Objects.requireNonNull(lng, "lng");
            Objects.requireNonNull(lat, "lat");
            Objects.requireNonNull(source, "source");
            if (nightlyPrice.signum() < 0 || starLevel < 0 || stationDistanceMeters < 0)
                throw new IllegalArgumentException("酒店价格、星级或车站距离非法");
        }

        public long nights() {
            return checkIn == null || checkOut == null ? 0 : java.time.temporal.ChronoUnit.DAYS.between(checkIn, checkOut);
        }

        public BigDecimal totalPrice() {
            return nightlyPrice.multiply(BigDecimal.valueOf(Math.max(1, nights())));
        }

        public PriceFact priceFact(Instant fetchedAt) {
            return new PriceFact(nightlyPrice, "CNY", PriceUnit.PER_ROOM_NIGHT,
                    source, fetchedAt, false);
        }
    }
}
