package com.travelscope.service.transport;

import com.travelscope.collab.TransportSelection;
import com.travelscope.collab.TransportSelection.DirectionSelection;
import com.travelscope.collab.TransportSelection.TransportOption;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** 从实时交通候选中确定性选择去返程主方案与最多两个备选。 */
public class TransportOptionSelector {

    public TransportSelection select(List<TransportOption> outbound, List<TransportOption> inbound,
                                     SelectionPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        return new TransportSelection(selectDirection(outbound, policy, true),
                selectDirection(inbound, policy, false));
    }

    private DirectionSelection selectDirection(List<TransportOption> input, SelectionPolicy policy,
                                               boolean outbound) {
        List<TransportOption> eligible = Objects.requireNonNull(input, "options").stream()
                .filter(o -> eligible(o, policy, outbound))
                .sorted(comparator(input)).toList();
        if (eligible.isEmpty()) throw new IllegalArgumentException(outbound ? "无满足首日约束的去程方案" : "无满足返程缓冲的方案");
        return new DirectionSelection(eligible.getFirst(), eligible.stream().skip(1).limit(2).toList());
    }

    private boolean eligible(TransportOption option, SelectionPolicy p, boolean outbound) {
        if (p.rejectEarlyDeparture() && option.departureTime().toLocalTime().isBefore(p.earliestDeparture())) return false;
        if (outbound) return !option.arrivalTime().toLocalTime().isAfter(p.latestOutboundArrival());
        LocalTime earliest = p.lastActivityEnd().plusMinutes(p.returnBufferMinutes());
        return !option.departureTime().toLocalTime().isBefore(earliest);
    }

    private Comparator<TransportOption> comparator(List<TransportOption> all) {
        BigDecimal minPrice = all.stream().map(TransportOption::price).min(BigDecimal::compareTo).orElse(BigDecimal.ONE);
        long minDuration = all.stream().mapToLong(TransportOption::durationMinutes).min().orElse(1);
        return Comparator.comparingDouble((TransportOption o) -> score(o, minPrice, minDuration))
                .thenComparing(TransportOption::departureTime)
                .thenComparing(TransportOption::serviceNo);
    }

    private double score(TransportOption o, BigDecimal minPrice, long minDuration) {
        double priceRatio = o.price().divide(minPrice.max(BigDecimal.ONE), 6, RoundingMode.HALF_UP).doubleValue();
        double durationRatio = (double) o.durationMinutes() / Math.max(1, minDuration);
        return priceRatio * 0.45 + durationRatio * 0.40 + o.transferCount() * 0.75;
    }

    public record SelectionPolicy(boolean rejectEarlyDeparture, LocalTime earliestDeparture,
                                  LocalTime latestOutboundArrival, LocalTime lastActivityEnd,
                                  int returnBufferMinutes) {
        public SelectionPolicy {
            Objects.requireNonNull(earliestDeparture, "earliestDeparture");
            Objects.requireNonNull(latestOutboundArrival, "latestOutboundArrival");
            Objects.requireNonNull(lastActivityEnd, "lastActivityEnd");
            if (returnBufferMinutes < 0) throw new IllegalArgumentException("returnBufferMinutes 不能为负");
        }

        public static SelectionPolicy defaults() {
            return new SelectionPolicy(false, LocalTime.of(6, 0), LocalTime.of(14, 0),
                    LocalTime.of(17, 0), 60);
        }
    }
}
