package com.travelscope.collab;

/** 路线未排入原因的全链路稳定错误码。 */
public enum UnplacedReason {
    COMMUTE_OVERFLOW,
    OPENING_WINDOW_CONFLICT,
    TRANSPORT_WINDOW_CONFLICT,
    DAILY_CAPACITY,
    MISSING_COORDINATE,
    DUPLICATE_ENTITY;

    public static UnplacedReason normalize(String value) {
        if (value == null) throw new IllegalArgumentException("unplaced reason 不能为空");
        return switch (value) {
            case "TIME_WINDOW_MISS" -> OPENING_WINDOW_CONFLICT;
            case "MATRIX_MISSING" -> MISSING_COORDINATE;
            default -> valueOf(value);
        };
    }
}
