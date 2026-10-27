package com.travelscope.service.workflow;

import com.travelscope.collab.TravelPreference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TravelPreferenceParserTest {
    private final TravelPreferenceParser parser = new TravelPreferenceParser();

    @Test
    void seniorCompanionDefaultsToRelaxedWithDerivedSource() {
        TravelPreference value = parser.parse(null, "带老人出行");

        assertThat(value.pace()).isEqualTo(TravelPreference.Pace.RELAXED);
        assertThat(value.walkingTolerance()).isEqualTo(TravelPreference.WalkingTolerance.LOW);
        assertThat(value.paceSource()).isEqualTo(TravelPreference.Source.DERIVED_SPECIAL_NEEDS);
        assertThat(value.dailyWindow().earliestStart()).isEqualTo("09:00");
    }

    @Test
    void systemDefaultsAreUsedWhenLegacyIntakeHasNoPreference() {
        TravelPreference value = parser.parse(null, null);

        assertThat(value.pace()).isEqualTo(TravelPreference.Pace.MODERATE);
        assertThat(value.walkingTolerance()).isEqualTo(TravelPreference.WalkingTolerance.MEDIUM);
        assertThat(value.dailyWindow().earliestStart()).isEqualTo("09:00");
        assertThat(value.dailyWindow().latestEnd()).isEqualTo("21:00");
        assertThat(value.paceSource()).isEqualTo(TravelPreference.Source.SYSTEM_DEFAULT);
    }

    @Test
    void explicitWindowWinsOverSeniorDefaults() {
        TravelPreference value = parser.parse("带老人，早上十点开始，晚上八点半结束", "带老人出行");

        assertThat(value.pace()).isEqualTo(TravelPreference.Pace.RELAXED);
        assertThat(value.dailyWindow().earliestStart()).isEqualTo("10:00");
        assertThat(value.dailyWindow().latestEnd()).isEqualTo("20:30");
        assertThat(value.dailyWindowSource()).isEqualTo(TravelPreference.Source.USER_EXPLICIT);
    }
}
