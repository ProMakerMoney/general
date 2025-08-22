package com.pinbot.botprime.service;

import com.pinbot.botprime.model.Candle;
import com.pinbot.botprime.persistence.CandleEntity;
import com.pinbot.botprime.repository.CandleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class IndicatorComputeService {

    private final CandleRepository candleRepository;

    @Transactional(readOnly = true)
    public void computeAndStore(String symbol, String timeframe) {
        log.info("INDICATORS: start compute symbol={} tf={}", symbol, timeframe);

        List<Candle> candles = loadCandles(symbol, timeframe);
        if (candles.isEmpty()) {
            log.warn("INDICATORS: no candles found for {} {}", symbol, timeframe);
            return;
        }

        List<Double> closes = candles.stream().map(Candle::getClose).collect(Collectors.toList());
        List<Double> hl2s = candles.stream().map(c -> (c.getHigh() + c.getLow()) / 2.0).collect(Collectors.toList());

        List<Double> ema11  = ema(closes, 11);
        List<Double> ema30  = ema(closes, 30);
        List<Double> ema110 = ema(closes, 110);
        List<Double> ema200 = ema(closes, 200);
        List<Double> tema9  = sma(tema(hl2s, 9), 10);  // Pine-style

        int last = candles.size();
        int fromIdx = Math.max(0, last - 10);
        for (int i = fromIdx; i < last; i++) {
            Candle c = candles.get(i);
            System.out.printf(
                    "t=%s | close=%.6f | EMA11=%s | EMA30=%s | EMA110=%s | EMA200=%s | TEMA9=%s%n",
                    Instant.ofEpochMilli(c.getTime()),
                    c.getClose(),
                    fmt(ema11.get(i)), fmt(ema30.get(i)), fmt(ema110.get(i)), fmt(ema200.get(i)), fmt(tema9.get(i))
            );
        }

        int lastIdx = candles.size() - 1;
        log.debug("LAST VALUES: EMA11={} EMA30={} EMA110={} EMA200={} TEMA9={}",
                ema11.get(lastIdx), ema30.get(lastIdx), ema110.get(lastIdx), ema200.get(lastIdx), tema9.get(lastIdx));
    }

    @Transactional(readOnly = true)
    public List<Candle> loadCandles(String symbol, String timeframe) {
        List<CandleEntity> rows = candleRepository.findAllOrdered(symbol, timeframe);
        return rows.stream()
                .map(e -> new Candle(
                        e.getId().getOpenTime().toEpochMilli(),
                        toDouble(e.getOpen()),
                        toDouble(e.getHigh()),
                        toDouble(e.getLow()),
                        toDouble(e.getClose()),
                        toDouble(e.getVolume()),
                        toDouble(e.getQuoteVolume())
                ))
                .collect(Collectors.toList());
    }

    public static List<Double> ema(List<Double> series, int period) {
        int n = series.size();
        List<Double> out = new ArrayList<>(n);
        if (period <= 1 || n == 0) {
            for (Double v : series) out.add(v);
            return out;
        }

        for (int i = 0; i < period - 1 && i < n; i++) out.add(null);

        double alpha = 2.0 / (period + 1.0);

        if (n >= period) {
            double sma = 0.0;
            for (int i = 0; i < period; i++) sma += series.get(i);
            sma /= period;
            double prev = sma;
            out.add(prev);

            for (int i = period; i < n; i++) {
                double curr = alpha * series.get(i) + (1.0 - alpha) * prev;
                out.add(curr);
                prev = curr;
            }
        }

        return out;
    }

    public static List<Double> sma(List<Double> series, int period) {
        List<Double> out = new ArrayList<>(series.size());
        double sum = 0.0;

        for (int i = 0; i < series.size(); i++) {
            Double v = series.get(i);
            if (v == null) {
                out.add(null);
                continue;
            }

            sum += v;
            if (i >= period) {
                Double old = series.get(i - period);
                sum -= (old == null ? 0.0 : old);
            }

            if (i >= period - 1) {
                out.add(sum / period);
            } else {
                out.add(null);
            }
        }

        return out;
    }

    public static List<Double> tema(List<Double> series, int period) {
        List<Double> ema1 = ema(series, period);
        List<Double> ema2 = ema(replaceNullsWithZeros(ema1), period);
        List<Double> ema3 = ema(replaceNullsWithZeros(ema2), period);

        List<Double> out = new ArrayList<>(series.size());
        for (int i = 0; i < series.size(); i++) {
            Double e1 = ema1.get(i), e2 = ema2.get(i), e3 = ema3.get(i);
            if (e1 == null || e2 == null || e3 == null) {
                out.add(null);
            } else {
                out.add(3 * e1 - 3 * e2 + e3);
            }
        }
        return out;
    }

    private static List<Double> replaceNullsWithZeros(List<Double> in) {
        List<Double> out = new ArrayList<>(in.size());
        for (Double v : in) out.add(v == null ? 0.0 : v);
        return out;
    }

    private static double toDouble(Number n) {
        return n == null ? 0.0 : n.doubleValue();
    }

    private static String fmt(Double v) {
        return v == null ? "null" : String.format("%.6f", v);
    }
}
