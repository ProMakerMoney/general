package com.pinbot.botprime.dto;

import java.util.Arrays;

/**
 * Bybit V5 interval enum.
 * API values: 1,3,5,15,30,60,120,240,360,720,D,W,M
 */
public enum BybitInterval {

    _1("1"),
    _3("3"),
    _5("5"),
    _15("15"),
    _30("30"),
    _60("60"),
    _120("120"),
    _240("240"),
    _360("360"),
    _720("720"),
    D("D"),
    W("W"),
    M("M");

    private final String apiValue;

    BybitInterval(String apiValue) {
        this.apiValue = apiValue;
    }

    public String apiValue() {
        return apiValue;
    }

    public static BybitInterval fromApiValue(String apiValue) {
        if (apiValue == null) {
            throw new IllegalArgumentException("BybitInterval apiValue is null");
        }
        String v = apiValue.trim();
        return Arrays.stream(values())
                .filter(i -> i.apiValue.equalsIgnoreCase(v))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown BybitInterval apiValue: " + apiValue));
    }
}
