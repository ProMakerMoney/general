package com.pinbot.botprime.dto;

import java.time.Instant;

public record IndicatorRow(
        String symbol,
        String timeframe,
        Instant open_time,
        double open,
        double high,
        double low,
        double close,
        double volume,
        double quoteVolume,
        double ema11,
        double ema30,
        double ema110,
        double ema200,
        double tema9,
        double rsi2h,
        double smaRsi2h
) {}
