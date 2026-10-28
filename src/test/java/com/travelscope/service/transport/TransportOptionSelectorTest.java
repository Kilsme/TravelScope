package com.travelscope.service.transport;

import com.travelscope.collab.TransportSelection;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TransportOptionSelectorTest {

    private final TransportOptionSelector selector = new TransportOptionSelector();

    @Test
    void rejectEarlyDepartureIsHardConstraint() {
        var early = option("G1", "06:30", "08:00", 120, 0);
        var normal = option("G3", "08:30", "10:00", 180, 0);

        var result = selector.select(List.of(early, normal), List.of(option("G4", "19:00", "20:30", 180, 0)),
                new TransportOptionSelector.SelectionPolicy(true, LocalTime.of(8, 0),
                        LocalTime.of(14, 0), LocalTime.of(15, 0), 45));

        assertThat(result.outbound().selected().serviceNo()).isEqualTo("G3");
        assertThat(result.outbound().alternatives()).noneMatch(o -> o.serviceNo().equals("G1"));
    }

    @Test
    void transferIsAllowedWhenNoDirectOptionExists() {
        var oneTransfer = option("G11/G21", "08:00", "11:00", 160, 1);
        var twoTransfers = option("K1/G9/K2", "07:30", "12:30", 120, 2);

        var result = selector.select(List.of(oneTransfer, twoTransfers),
                List.of(option("G22", "19:00", "22:00", 160, 1)),
                TransportOptionSelector.SelectionPolicy.defaults());

        assertThat(result.outbound().selected().serviceNo()).isEqualTo("G11/G21");
        assertThat(result.outbound().selected().transferCount()).isEqualTo(1);
    }

    @Test
    void balancesPriceDurationAndTransfersInsteadOfChoosingCheapest() {
        var cheapSlowTransfer = option("K101/G9", "08:00", "14:00", 100, 1);
        var balancedDirect = option("G7331", "08:30", "10:30", 180, 0);
        var expensiveFast = option("G1", "09:00", "10:30", 420, 0);

        var result = selector.select(List.of(cheapSlowTransfer, balancedDirect, expensiveFast),
                List.of(option("G7332", "19:00", "21:00", 180, 0)),
                TransportOptionSelector.SelectionPolicy.defaults());

        assertThat(result.outbound().selected().serviceNo()).isEqualTo("G7331");
        assertThat(result.outbound().alternatives()).extracting(TransportSelection.TransportOption::serviceNo)
                .containsExactly("G1", "K101/G9");
    }

    @Test
    void inboundRequiresSecurityBufferAfterLastActivity() {
        var tooTight = option("MU1", "16:00", "18:00", 400, 0);
        var safe = option("MU3", "17:30", "19:30", 450, 0);
        var policy = new TransportOptionSelector.SelectionPolicy(false, LocalTime.of(8, 0),
                LocalTime.of(14, 0), LocalTime.of(15, 0), 120);

        var result = selector.select(List.of(option("MU0", "09:00", "11:00", 450, 0)),
                List.of(tooTight, safe), policy);

        assertThat(result.inbound().selected().serviceNo()).isEqualTo("MU3");
        assertThat(result.inbound().alternatives()).noneMatch(o -> o.serviceNo().equals("MU1"));
    }

    private static TransportSelection.TransportOption option(String no, String dep, String arr,
                                                                int price, int transfers) {
        return new TransportSelection.TransportOption(no, "TRAIN", new BigDecimal(price),
                LocalDateTime.parse("2026-10-25T" + dep), LocalDateTime.parse("2026-10-25T" + arr),
                java.time.Duration.between(LocalTime.parse(dep), LocalTime.parse(arr)).toMinutes(),
                transfers, transfers == 0, "飞猪", null);
    }
}
