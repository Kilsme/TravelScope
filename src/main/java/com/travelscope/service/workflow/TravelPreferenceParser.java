package com.travelscope.service.workflow;

import com.travelscope.collab.TravelPreference;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 从明确需求及特殊同行人信息推导路线约束；明确输入始终覆盖默认值。 */
public class TravelPreferenceParser {
    private static final Pattern WINDOW = Pattern.compile("([01]?\\d|2[0-3]):([0-5]\\d)\\s*[-~到至]\\s*([01]?\\d|2[0-3]):([0-5]\\d)");

    public TravelPreference parse(String explicit, String special) {
        String input = explicit == null ? "" : explicit;
        String all = input + " " + (special == null ? "" : special);
        boolean senior = containsAny(all, "老人", "老年", "长辈", "父母");

        TravelPreference.Pace pace = senior ? TravelPreference.Pace.RELAXED : TravelPreference.Pace.MODERATE;
        TravelPreference.Source paceSource = senior
                ? TravelPreference.Source.DERIVED_SPECIAL_NEEDS : TravelPreference.Source.SYSTEM_DEFAULT;
        if (containsAny(input, "RELAXED", "轻松", "休闲", "慢节奏")) {
            pace = TravelPreference.Pace.RELAXED;
            paceSource = TravelPreference.Source.USER_EXPLICIT;
        } else if (containsAny(input, "INTENSIVE", "紧凑", "高强度", "特种兵")) {
            pace = TravelPreference.Pace.INTENSIVE;
            paceSource = TravelPreference.Source.USER_EXPLICIT;
        } else if (containsAny(input, "MODERATE", "适中", "正常节奏")) {
            pace = TravelPreference.Pace.MODERATE;
            paceSource = TravelPreference.Source.USER_EXPLICIT;
        }

        TravelPreference.WalkingTolerance walking = senior
                ? TravelPreference.WalkingTolerance.LOW : TravelPreference.WalkingTolerance.MEDIUM;
        TravelPreference.Source walkingSource = senior
                ? TravelPreference.Source.DERIVED_SPECIAL_NEEDS : TravelPreference.Source.SYSTEM_DEFAULT;
        if (containsAny(input, "LOW", "少走路", "不能久走", "步行少")) {
            walking = TravelPreference.WalkingTolerance.LOW;
            walkingSource = TravelPreference.Source.USER_EXPLICIT;
        } else if (containsAny(input, "HIGH", "能走", "喜欢徒步", "多走路")) {
            walking = TravelPreference.WalkingTolerance.HIGH;
            walkingSource = TravelPreference.Source.USER_EXPLICIT;
        }

        TravelPreference.DailyWindow window = new TravelPreference.DailyWindow("09:00", "21:00");
        TravelPreference.Source windowSource = TravelPreference.Source.SYSTEM_DEFAULT;
        String[] times = explicitWindow(input);
        if (times != null) {
            window = new TravelPreference.DailyWindow(times[0], times[1]);
            windowSource = TravelPreference.Source.USER_EXPLICIT;
        }
        return new TravelPreference(pace, window, walking, paceSource, windowSource, walkingSource);
    }

    private static String[] explicitWindow(String text) {
        Matcher matcher = WINDOW.matcher(text);
        if (matcher.find()) {
            return new String[]{pad(matcher.group(1)) + ":" + matcher.group(2),
                    pad(matcher.group(3)) + ":" + matcher.group(4)};
        }
        String start = containsAny(text, "十点开始", "10点开始", "上午十点开始") ? "10:00" : null;
        String end = containsAny(text, "八点半结束", "8点半结束", "晚上八点半", "20点半") ? "20:30" : null;
        return start != null || end != null
                ? new String[]{start == null ? "09:00" : start, end == null ? "21:00" : end} : null;
    }

    private static String pad(String hour) {
        return hour.length() == 1 ? "0" + hour : hour;
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) if (text.contains(value)) return true;
        return false;
    }
}
