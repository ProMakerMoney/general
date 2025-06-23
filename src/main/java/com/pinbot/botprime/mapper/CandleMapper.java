package com.pinbot.botprime.mapper;

import com.pinbot.botprime.model.Candle;

import java.math.BigDecimal;
import java.util.List;

public final class CandleMapper {

    private CandleMapper() {}

    /**
     * @param raw  элемент массива `list` из ответа Bybit
     *             формат: [ openTime, open, high, low, close, volume, turnover ]
     */
    public static Candle map(List<String> raw, String symbol, String interval) {
        return Candle.builder()
                .symbol(symbol)
                .interval(interval)
                .timestamp(Long.parseLong(raw.get(0)))
                .open(new BigDecimal(raw.get(1)))
                .high(new BigDecimal(raw.get(2)))
                .low (new BigDecimal(raw.get(3)))
                .close(new BigDecimal(raw.get(4)))
                .volume(new BigDecimal(raw.get(5)))
                .build();
    }
}