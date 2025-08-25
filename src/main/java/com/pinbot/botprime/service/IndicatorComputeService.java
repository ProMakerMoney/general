package com.pinbot.botprime.service;

import com.pinbot.botprime.model.Candle;
import com.pinbot.botprime.persistence.IndicatorValueEntity;
import com.pinbot.botprime.repository.CandleRepository;
import com.pinbot.botprime.repository.IndicatorValueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static com.pinbot.botprime.service.IndicatorUtils.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class IndicatorComputeService {

    private final CandleRepository candleRepository;
    private final IndicatorValueRepository indicatorRepo;

    @Transactional
    public void computeAndStore(String symbol, String timeframe) {
        log.info("INDICATORS: start compute symbol={} tf={}", symbol, timeframe);

        List<Candle> candles = loadCandles(symbol, timeframe);
        if (candles.isEmpty()) {
            log.warn("INDICATORS: no candles found for {} {}", symbol, timeframe);
            return;
        }

        // 1. 30-минутные индикаторы
        List<Double> closes = candles.stream().map(Candle::getClose).toList();
        List<Double> hl2    = candles.stream().map(Candle::getHL2).toList();

        List<Double> ema11  = ema(closes, 11);
        List<Double> ema30  = ema(closes, 30);
        List<Double> ema110 = ema(closes, 110);
        List<Double> ema200 = ema(closes, 200);
        List<Double> tema9  = sma(tema(hl2, 9), 10);

        // 2. 2-часовые индикаторы
        List<Candle> candles2h = aggregateTo2h(candles);
        List<Double> closes2h  = candles2h.stream().map(Candle::getClose).toList();
        List<Double> rsi2h     = rsi(closes2h, 14);
        List<Double> smaRsi2h  = sma(rsi2h, 20);

        // 3. Сопоставление 30-м свечей с 2-часовыми
        Map<Long, Double> rsiMap = new HashMap<>();
        Map<Long, Double> smaMap = new HashMap<>();
        for (int i = 0; i < candles2h.size(); i++) {
            long groupTime = floorTo2h(candles2h.get(i).getTime());
            rsiMap.put(groupTime, safe(rsi2h.get(i)));
            smaMap.put(groupTime, safe(smaRsi2h.get(i)));
        }

        // 4. Сбор IndicatorValueEntity
        List<IndicatorValueEntity> rows = new ArrayList<>();
        for (int i = 0; i < candles.size(); i++) {
            Candle c = candles.get(i);
            long groupTime = floorTo2h(c.getTime());
            double rsiVal = rsiMap.getOrDefault(groupTime, -1.0);
            double smaVal = smaMap.getOrDefault(groupTime, -1.0);

            rows.add(IndicatorValueEntity.builder()
                    .symbol(symbol)
                    .timeframe(timeframe)
                    .open_time(Instant.ofEpochMilli(c.getTime()))
                    // OHLCV — BigDecimal
                    .open(BigDecimal.valueOf(c.getOpen()))
                    .high(BigDecimal.valueOf(c.getHigh()))
                    .low(BigDecimal.valueOf(c.getLow()))
                    .close(BigDecimal.valueOf(c.getClose()))
                    .volume(BigDecimal.valueOf(c.getVolume()))
                    .quoteVolume(BigDecimal.valueOf(c.getQuoteVolume()))
                    // Индикаторы — Double
                    .ema11(safeD(ema11.get(i)))
                    .ema30(safeD(ema30.get(i)))
                    .ema110(safeD(ema110.get(i)))
                    .ema200(safeD(ema200.get(i)))
                    .tema9(safeD(tema9.get(i)))
                    .rsi2h(safeD(rsiVal))
                    .smaRsi2h(safeD(smaVal))
                    .build());
        }

        // 5. Upsert
        List<String> symbols      = rows.stream().map(IndicatorValueEntity::getSymbol).toList();
        List<String> timeframes   = rows.stream().map(IndicatorValueEntity::getTimeframe).toList();
        List<Instant> openTimes   = rows.stream().map(IndicatorValueEntity::getOpen_time).toList();
        List<BigDecimal> opens    = rows.stream().map(IndicatorValueEntity::getOpen).toList();
        List<BigDecimal> highs    = rows.stream().map(IndicatorValueEntity::getHigh).toList();
        List<BigDecimal> lows     = rows.stream().map(IndicatorValueEntity::getLow).toList();
        List<BigDecimal> closeValues = rows.stream().map(IndicatorValueEntity::getClose).toList();
        List<BigDecimal> volumes  = rows.stream().map(IndicatorValueEntity::getVolume).toList();
        List<BigDecimal> quoteVol = rows.stream().map(IndicatorValueEntity::getQuoteVolume).toList();
        List<Double> ema11s       = rows.stream().map(IndicatorValueEntity::getEma11).toList();
        List<Double> ema30s       = rows.stream().map(IndicatorValueEntity::getEma30).toList();
        List<Double> ema110s      = rows.stream().map(IndicatorValueEntity::getEma110).toList();
        List<Double> ema200s      = rows.stream().map(IndicatorValueEntity::getEma200).toList();
        List<Double> tema9s       = rows.stream().map(IndicatorValueEntity::getTema9).toList();
        List<Double> rsi2hs       = rows.stream().map(IndicatorValueEntity::getRsi2h).toList();
        List<Double> smaRsi2hs    = rows.stream().map(IndicatorValueEntity::getSmaRsi2h).toList();

        indicatorRepo.upsertBatchArrays(rows);

        log.info("INDICATORS: upserted {} rows for {} {}", rows.size(), symbol, timeframe);
    }

    private static Double safeD(Double v) {
        return v == null ? -1d : v;
    }


    // для совместимости
    public void computeAndLog(String symbol, String timeframe) {
        computeAndStore(symbol, timeframe);
    }

    /* ---------- helpers ---------- */
    private List<Candle> loadCandles(String symbol, String timeframe) {
        return candleRepository.findAllOrdered(symbol, timeframe).stream()
                .map(e -> new Candle(
                        e.getId().getOpenTime().toEpochMilli(),
                        e.getOpen().doubleValue(),
                        e.getHigh().doubleValue(),
                        e.getLow().doubleValue(),
                        e.getClose().doubleValue(),
                        e.getVolume().doubleValue(),
                        e.getQuoteVolume().doubleValue()))
                .collect(Collectors.toList());
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
            if (group.size() < 4) continue;
            group.sort(Comparator.comparingLong(Candle::getTime));

            double open  = group.get(0).getOpen();
            double close = group.get(group.size() - 1).getClose();
            double high  = group.stream().mapToDouble(Candle::getHigh).max().orElse(0);
            double low   = group.stream().mapToDouble(Candle::getLow).min().orElse(0);
            double vol   = group.stream().mapToDouble(Candle::getVolume).sum();
            double quote = group.stream().mapToDouble(Candle::getQuoteVolume).sum();

            result.add(new Candle(entry.getKey(), open, high, low, close, vol, quote));
        }
        return result;
    }

    private long floorTo2h(long timestamp) {
        return timestamp - (timestamp % (2 * 60 * 60 * 1000L));
    }

    private static double safe(Double v) {
        return v == null ? -1 : v;
    }
}