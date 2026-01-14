package com.pinbot.botprime.dto;

import java.util.Arrays;

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
        return Arrays.stream(values())
                .filter(v -> v.apiValue.equalsIgnoreCase(apiValue.trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown BybitInterval apiValue: " + apiValue));
    }
}
