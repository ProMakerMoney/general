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

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CandleUpdateService {

    private final CandleRepository candleRepository;
    private final BybitClient bybitClient;
    private final CandleMapper candleMapper;

    /**
     * Загружает и сохраняет новые свечи, если они появились.
     * Используется планировщиком каждые 30 минут.
     */
    @Transactional
    public void updateCandles(String symbol, String timeframe, int limit) {
        try {

            Instant last = candleRepository.findMaxOpenTime(symbol, timeframe);
            List<CandleDto> dtos = bybitClient.getCandles(symbol, timeframe, limit);

            List<CandleEntity> toSave = dtos.stream()
                    .map(dto -> candleMapper.toEntity(symbol, timeframe, dto))
                    .filter(entity -> last == null || entity.getId().getOpenTime().isAfter(last))
                    .collect(Collectors.toList());

            if (!toSave.isEmpty()) {
                candleRepository.saveAll(toSave);
                log.info("BYBIT: ✅ Загружено и сохранено {} новых свечей для {} {}", toSave.size(), symbol, timeframe);
            } else {
                log.info("BYBIT: ⏸ Нет новых свечей для {} {}", symbol, timeframe);
            }
        } catch (Exception e) {
            log.error("BYBIT: ❌ Ошибка при обновлении свечей для " + symbol + " " + timeframe, e);
        }
    }
}
