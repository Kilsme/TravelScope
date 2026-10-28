package com.travelscope.collab;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import com.travelscope.price.PriceFact;
import com.travelscope.price.PriceUnit;
import java.util.List;
import java.util.Objects;

/** 工作区契约：transport_selection.json。去返程各含一个主选和最多两个备选。 */
public record TransportSelection(int schemaVersion, DirectionSelection outbound,
                                 DirectionSelection inbound) {
    public static final int SCHEMA_VERSION = 4;

    public TransportSelection {
        if (schemaVersion != SCHEMA_VERSION) throw new IllegalArgumentException("schemaVersion 必须为 4");
        Objects.requireNonNull(outbound, "outbound");
        Objects.requireNonNull(inbound, "inbound");
    }

    public TransportSelection(DirectionSelection outbound, DirectionSelection inbound) {
        this(SCHEMA_VERSION, outbound, inbound);
    }

    public record DirectionSelection(TransportOption selected, List<TransportOption> alternatives) {
        public DirectionSelection {
            Objects.requireNonNull(selected, "selected");
            alternatives = List.copyOf(Objects.requireNonNull(alternatives, "alternatives"));
            if (alternatives.size() > 2) throw new IllegalArgumentException("alternatives 最多两个");
        }
    }

    public record TransportOption(String serviceNo, String mode, BigDecimal price,
                                  LocalDateTime departureTime, LocalDateTime arrivalTime,
                                  long durationMinutes, int transferCount, boolean direct,
                                  String source, String bookingUrl) {
        public TransportOption {
            Objects.requireNonNull(serviceNo, "serviceNo");
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(price, "price");
            Objects.requireNonNull(departureTime, "departureTime");
            Objects.requireNonNull(arrivalTime, "arrivalTime");
            Objects.requireNonNull(source, "source");
            if (price.signum() < 0 || durationMinutes <= 0 || transferCount < 0)
                throw new IllegalArgumentException("价格、耗时或换乘数非法");
            if (!arrivalTime.isAfter(departureTime)) throw new IllegalArgumentException("到达时间必须晚于出发时间");
        }

        public PriceFact priceFact(Instant fetchedAt) {
            return new PriceFact(price, "CNY", PriceUnit.PER_PERSON_TRIP, source, fetchedAt, false);
        }
    }
}
