package com.pinbot.botprime.service;

import com.pinbot.botprime.client.BybitClient;
import com.pinbot.botprime.dto.CandleDto;
import com.pinbot.botprime.mapper.CandleMapper;
import com.pinbot.botprime.persistence.CandleEntity;
import com.pinbot.botprime.repository.CandleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CandleUpdateService {

    private final CandleRepository candleRepository;
    private final BybitClient bybitClient;
    private final CandleMapper candleMapper;

    /**
     * Догружает исторические свечи за последний год пачками по 1000,
     * делая паузу 500 мс между запросами.
     */
    @Transactional
    public void backfillYear(String symbol, String timeframe) {
        final int BATCH = 1000;
        final long TF_MILLIS = 30 * 60 * 1000L; // 30-минутный интервал
        final long YEAR_AGO = Instant.now().minus(365, ChronoUnit.DAYS).toEpochMilli();

        while (true) {
            // самая старая свеча в БД
            Instant oldestInDb = candleRepository.findMinOpenTime(symbol, timeframe);
            long endExclusive = oldestInDb == null
                    ? Instant.now().toEpochMilli()
                    : oldestInDb.toEpochMilli();

            long startInclusive = endExclusive - BATCH * TF_MILLIS;
            if (startInclusive < YEAR_AGO) startInclusive = YEAR_AGO;

            log.info("Backfill {} {} batch from {} to {}",
                    symbol, timeframe,
                    Instant.ofEpochMilli(startInclusive),
                    Instant.ofEpochMilli(endExclusive));

            Map<String, String> params = Map.of(
                    "category", "linear",
                    "symbol", symbol,
                    "interval", timeframe,
                    "limit", String.valueOf(BATCH),
                    "start", String.valueOf(startInclusive),
                    "end", String.valueOf(endExclusive - 1) // inclusive
            );

            Map<String, Object> raw = bybitClient.publicGet("/v5/market/kline", params);
            List<List<String>> rows = (List<List<String>>) ((Map<?, ?>) raw.get("result")).get("list");
            if (rows == null || rows.isEmpty()) {
                log.info("No more data, backfill finished");
                break;
            }

            List<CandleDto> dtos = rows.stream()
                    .map(r -> new CandleDto(
                            Long.parseLong(r.get(0)),
                            new BigDecimal(r.get(1)).doubleValue(),
                            new BigDecimal(r.get(2)).doubleValue(),
                            new BigDecimal(r.get(3)).doubleValue(),
                            new BigDecimal(r.get(4)).doubleValue(),
                            new BigDecimal(r.get(5)).doubleValue(),
                            new BigDecimal(r.get(6)).doubleValue()
                    ))
                    .toList();

            List<CandleEntity> toSave = dtos.stream()
                    .map(d -> candleMapper.toEntity(symbol, timeframe, d))
                    .collect(Collectors.toList());

            candleRepository.saveAll(toSave);
            log.info("Saved {} candles", toSave.size());

            if (startInclusive <= YEAR_AGO) {
                log.info("Reached one year ago, backfill complete");
                break;
            }

            // пауза 500 мс, чтобы не превысить лимиты Bybit
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Backfill sleep interrupted");
                break;
            }
        }
    }

    /**
     * Обычное обновление последних свечей (остаётся прежним).
     */
    @Transactional
    public void updateCandles(String symbol, String timeframe, int limit) {
        try {
            Instant last = candleRepository.findMaxOpenTime(symbol, timeframe);
            List<CandleDto> dtos = bybitClient.getCandles(symbol, timeframe, limit);

            List<CandleEntity> toSave = dtos.stream()
                    .map(dto -> candleMapper.toEntity(symbol, timeframe, dto))
                    .filter(e -> last == null || e.getId().getOpenTime().isAfter(last))
                    .collect(Collectors.toList());

            if (!toSave.isEmpty()) {
                candleRepository.saveAll(toSave);
                log.info("BYBIT: ✅ Загружено и сохранено {} новых свечей для {} {}",
                        toSave.size(), symbol, timeframe);
            } else {
                log.info("BYBIT: ⏸ Нет новых свечей для {} {}", symbol, timeframe);
            }
        } catch (Exception e) {
            log.error("BYBIT: ❌ Ошибка при обновлении свечей для {} {}", symbol, timeframe, e);
        }
    }
}
