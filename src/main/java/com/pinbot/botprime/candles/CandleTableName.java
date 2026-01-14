package com.pinbot.botprime.candles;

import com.pinbot.botprime.dto.BybitInterval;

import java.util.Locale;
import java.util.regex.Pattern;

public final class CandleTableName {

    private static final Pattern SYMBOL_PATTERN = Pattern.compile("^[A-Z0-9]{2,50}$");

    private CandleTableName() {
    }

    public static String of(String symbol, BybitInterval interval) {
        if (symbol == null || interval == null) {
            throw new IllegalArgumentException("symbol/interval must not be null");
        }

        String s = symbol.trim().toUpperCase(Locale.ROOT);
        if (!SYMBOL_PATTERN.matcher(s).matches()) {
            throw new IllegalArgumentException("Invalid symbol: " + symbol);
        }

        String tf = interval.apiValue().trim().toLowerCase(Locale.ROOT);
        return s.toLowerCase(Locale.ROOT) + "_" + tf + "_candles";
    }
}
