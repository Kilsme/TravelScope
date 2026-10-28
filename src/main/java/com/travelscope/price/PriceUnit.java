package com.travelscope.price;

/** 价格计量单位，避免同一数字在每人、每晚、每张票之间误用。 */
public enum PriceUnit {
    PER_PERSON_TRIP,
    PER_ROOM_NIGHT,
    PER_TICKET,
    PER_PERSON,
    TOTAL
}
