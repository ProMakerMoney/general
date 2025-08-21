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

/**
 * Сервис расчёта индикаторов (EMA/TEMA) поверх исторических свечей.
 *
 * Требования:
 * - После старта сервера выполняется расчёт по имеющимся свечам.
 * - Для свечей, где недостаточно данных для периода индикатора, значения не считаются (кладём null).
 * - Работаем с доменной моделью Candle (а не с JPA-сущностью).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndicatorComputeService {

    private final CandleRepository candleRepository;

    /**
     * Основной метод, который можно вызывать из bootstrap-а.
     * Периоды: EMA(11/30/110/200) и TEMA(9).
     */
    @Transactional(readOnly = true)
    public void computeAndStore(String symbol, String timeframe) {
        log.info("INDICATORS: start compute symbol={} tf={}", symbol, timeframe);

        List<Candle> candles = loadCandles(symbol, timeframe);
        if (candles.isEmpty()) {
            log.warn("INDICATORS: no candles found for {} {}", symbol, timeframe);
            return;
        }

        // Берём цены закрытия
        List<Double> closes = candles.stream()
                .map(Candle::getClose)
                .map(Double::valueOf)
                .collect(Collectors.toList());

        // Считаем EMA/TEMA
        List<Double> ema11  = ema(closes, 11);
        List<Double> ema30  = ema(closes, 30);
        List<Double> ema110 = ema(closes, 110);
        List<Double> ema200 = ema(closes, 200);
        List<Double> tema9  = tema(closes, 9);

        // Лог по диапазону данных
        Instant from = Instant.ofEpochMilli(candles.get(0).getTime());
        Instant to   = Instant.ofEpochMilli(candles.get(candles.size() - 1).getTime());
        log.info("INDICATORS: computed for {} {} from {} to {}. total candles={}",
                symbol, timeframe, from, to, candles.size());

        // Вывод последних 10 свечей с индикаторами
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

        // Пример: последние рассчитанные значения в debug
        int lastIdx = candles.size() - 1;
        log.debug("LAST VALUES: EMA11={} EMA30={} EMA110={} EMA200={} TEMA9={}",
                ema11.get(lastIdx), ema30.get(lastIdx), ema110.get(lastIdx), ema200.get(lastIdx), tema9.get(lastIdx));

        // TODO: при необходимости — сохранить индикаторы в БД
        // persistIndicators(symbol, timeframe, candles, ema11, ema30, ema110, ema200, tema9);
    }

    /**
     * Грузим свечи из БД и маппим в доменную модель Candle.
     * Репозиторий должен иметь метод:
     *   List<CandleEntity> findAllOrdered(String symbol, String timeframe);
     */
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

    /* ===================== Math ===================== */

    /**
     * EMA с заполнением null до момента, когда данных достаточно.
     * Формула: EMA_t = α * price_t + (1 - α) * EMA_{t-1}, α = 2 / (period + 1)
     */
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
            out.add(prev); // индекс = period - 1

            for (int i = period; i < n; i++) {
                double curr = alpha * series.get(i) + (1.0 - alpha) * prev;
                out.add(curr);
                prev = curr;
            }
        }

        return out;
    }

    /**
     * TEMA(period) = 3*EMA1 - 3*EMA2 + EMA3,
     * где EMA2 = EMA(EMA1), EMA3 = EMA(EMA2).
     * Возвращаем список, выровненный по длине входной серии, с null там, где данных не хватает.
     */
    public static List<Double> tema(List<Double> series, int period) {
        List<Double> ema1 = ema(series, period);
        List<Double> ema2 = ema(replaceNullsWithZeros(ema1), period);
        List<Double> ema3 = ema(replaceNullsWithZeros(ema2), period);

        int n = series.size();
        List<Double> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Double e1 = ema1.get(i);
            Double e2 = ema2.get(i);
            Double e3 = ema3.get(i);

            if (e1 == null || e2 == null || e3 == null) {
                out.add(null);
            } else {
                out.add(3 * e1 - 3 * e2 + e3);
            }
        }
        return out;
    }

    /* ===================== Helpers ===================== */

    private static double toDouble(Number n) {
        return n == null ? 0.0 : n.doubleValue();
    }

    private static List<Double> replaceNullsWithZeros(List<Double> in) {
        List<Double> out = new ArrayList<>(in.size());
        for (Double v : in) out.add(v == null ? 0.0 : v);
        return out;
    }

    // форматтер для печати чисел/NULL
    private static String fmt(Double v) {
        return v == null ? "null" : String.format("%.6f", v);
    }
}
