package com.pinbot.botprime.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
// ПОЛЕЙ 7 штук: startMs, open, high, low, close, volume, quoteVolume
public class CandleDto {
    private long startMs;
    private double open;
    private double high;
    private double low;
    private double close;
    private double volume;
    private double quoteVolume;
}

