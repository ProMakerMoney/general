package com.pinbot.botprime.service;

import com.pinbot.botprime.model.Candle;
import com.pinbot.botprime.persistence.CandleEntity;
import com.pinbot.botprime.repository.CandleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class IndicatorComputeService {

    private final CandleRepository candleRepository;

    @Transactional(readOnly = true)
    public void computeAndStore(String symbol, String timeframe) {
        log.info("INDICATORS: start compute symbol={} tf={}", symbol, timeframe);

        List<Candle> candles30 = loadCandles(symbol, timeframe);
        if (candles30.isEmpty()) {
            log.warn("INDICATORS: no candles found for {} {}", symbol, timeframe);
            return;
        }

        // ==== EMA и TEMA на 30m ====
        List<Double> closes30 = candles30.stream().map(Candle::getClose).collect(Collectors.toList());
        List<Double> hl2s = candles30.stream().map(c -> (c.getHigh() + c.getLow()) / 2.0).collect(Collectors.toList());

        List<Double> ema11  = ema(closes30, 11);
        List<Double> ema30  = ema(closes30, 30);
        List<Double> ema110 = ema(closes30, 110);
        List<Double> ema200 = ema(closes30, 200);
        List<Double> tema9  = sma(tema(hl2s, 9), 10);  // Pine-style

        int last = candles30.size();
        int fromIdx = Math.max(0, last - 10);
        System.out.println("\n=== [30m] Последние 10 значений индикаторов ===");
        for (int i = fromIdx; i < last; i++) {
            Candle c = candles30.get(i);
            System.out.printf(
                    "t=%s | close=%.2f | EMA11=%.6f | EMA30=%.6f | EMA110=%.6f | EMA200=%.6f | TEMA9=%.6f%n",
                    Instant.ofEpochMilli(c.getTime()),
                    c.getClose(),
                    ema11.get(i), ema30.get(i), ema110.get(i), ema200.get(i), tema9.get(i)
            );
        }

        // ==== RSI на 2h (из 30m свечей) ====
        List<Candle> candles2h = aggregateTo2h(candles30);
        List<Double> closes2h = candles2h.stream().map(Candle::getClose).collect(Collectors.toList());

        List<Double> rsi = rsi(closes2h, 14);
        List<Double> rsiSma = sma(rsi, 20);

        System.out.println("\n=== [2h] Промежуточный вывод RSI + SMA ===");
        for (int i = 0; i < candles2h.size(); i++) {
            Candle c = candles2h.get(i);
            System.out.printf(
                    "t=%s | close=%.2f | RSI=%.6f | SMA(RSI)=%.6f%n",
                    Instant.ofEpochMilli(c.getTime()),
                    c.getClose(),
                    safe(rsi.get(i)),
                    safe(rsiSma.get(i))
            );
        }

        log.debug("DONE: indicators computed");
    }

    private List<Candle> aggregateTo2h(List<Candle> candles30) {
        Map<Long, List<Candle>> grouped = new TreeMap<>();

        for (Candle c : candles30) {
            long groupTime = floorTo2h(c.getTime());
            grouped.computeIfAbsent(groupTime, k -> new ArrayList<>()).add(c);
        }

        List<Candle> result = new ArrayList<>();
        for (Map.Entry<Long, List<Candle>> entry : grouped.entrySet()) {
            List<Candle> group = entry.getValue();
            if (group.size() < 4) continue; // пропускаем неполные свечи

            group.sort(Comparator.comparingLong(Candle::getTime));

            double open = group.get(0).getOpen();
            double close = group.get(group.size() - 1).getClose();
            double high = group.stream().mapToDouble(Candle::getHigh).max().orElse(0.0);
            double low = group.stream().mapToDouble(Candle::getLow).min().orElse(0.0);
            double volume = group.stream().mapToDouble(Candle::getVolume).sum();
            double quote = group.stream().mapToDouble(Candle::getQuoteVolume).sum();

            result.add(new Candle(entry.getKey(), open, high, low, close, volume, quote));
        }

        return result;
    }

    private long floorTo2h(long timestamp) {
        // Округление вниз до ближайшего времени кратного 2 часам (в миллисекундах)
        return timestamp - (timestamp % (2 * 60 * 60 * 1000L));
    }

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

    public static List<Double> rsi(List<Double> series, int period) {
        List<Double> out = new ArrayList<>();
        Double prevClose = null;
        double avgGain = 0.0, avgLoss = 0.0;

        for (int i = 0; i < series.size(); i++) {
            Double close = series.get(i);
            if (close == null) {
                out.add(null);
                continue;
            }

            if (prevClose == null) {
                prevClose = close;
                out.add(null);
                continue;
            }

            double change = close - prevClose;
            double gain = Math.max(0, change);
            double loss = Math.max(0, -change);

            if (i <= period) {
                avgGain += gain;
                avgLoss += loss;
                out.add(null);
            } else if (i == period + 1) {
                avgGain /= period;
                avgLoss /= period;
                double rs = avgLoss == 0 ? 100 : avgGain / avgLoss;
                out.add(100 - (100 / (1 + rs)));
            } else {
                avgGain = (avgGain * (period - 1) + gain) / period;
                avgLoss = (avgLoss * (period - 1) + loss) / period;
                double rs = avgLoss == 0 ? 100 : avgGain / avgLoss;
                out.add(100 - (100 / (1 + rs)));
            }

            prevClose = close;
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

    private static double safe(Double v) {
        return v == null ? 0.0 : v;
    }
}



