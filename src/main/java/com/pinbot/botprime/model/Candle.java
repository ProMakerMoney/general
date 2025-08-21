package com.pinbot.botprime.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Getter
public class Candle {
    private long time;            // epoch millis (open time)
    private double open;
    private double high;
    private double low;
    private double close;
    private double volume;
    private double quoteVolume;

    public double getTypicalPrice() {
        return (high + low + close) / 3.0;
    }

    public double getHL2() {
        return (high + low) / 2.0;
    }
}