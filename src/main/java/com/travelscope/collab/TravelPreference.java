package com.travelscope.collab;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/** 行程节奏、每日活动时间窗和步行承受度，并记录每项值的来源。 */
public record TravelPreference(
        Pace pace,
        DailyWindow dailyWindow,
        WalkingTolerance walkingTolerance,
        Source paceSource,
        Source dailyWindowSource,
        Source walkingToleranceSource) {

    public enum Pace { RELAXED, MODERATE, INTENSIVE }
    public enum WalkingTolerance { LOW, MEDIUM, HIGH }
    public enum Source { USER_EXPLICIT, DERIVED_SPECIAL_NEEDS, SYSTEM_DEFAULT }

    public TravelPreference {
        Objects.requireNonNull(pace, "pace");
        Objects.requireNonNull(dailyWindow, "dailyWindow");
        Objects.requireNonNull(walkingTolerance, "walkingTolerance");
        Objects.requireNonNull(paceSource, "paceSource");
        Objects.requireNonNull(dailyWindowSource, "dailyWindowSource");
        Objects.requireNonNull(walkingToleranceSource, "walkingToleranceSource");
    }

    public record DailyWindow(String earliestStart, String latestEnd) {
        public DailyWindow {
            Objects.requireNonNull(earliestStart, "earliestStart");
            Objects.requireNonNull(latestEnd, "latestEnd");
            try {
                if (!LocalTime.parse(earliestStart).isBefore(LocalTime.parse(latestEnd))) {
                    throw new IllegalArgumentException("earliestStart 必须早于 latestEnd");
                }
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("时间窗必须为 HH:mm", e);
            }
        }
    }
}
