package com.pinbot.botprime.service;

import com.pinbot.botprime.client.BybitClient;
import com.pinbot.botprime.mapper.CandleMapper;
import com.pinbot.botprime.model.Candle;
import com.pinbot.botprime.repository.CandleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class CandleService {

    private final BybitClient      client;
    private final CandleRepository repo;

    /**
     * Загружаем последние 1000 свечей и сохраняем в БД.
     */
    @SuppressWarnings("unchecked")          // безопасные касты к Map / List
    public List<Candle> loadHistory(String symbol, String interval) {

        Map<String, String> params = Map.of(
                "category", "linear",
                "symbol",   symbol,
                "interval", interval,
                "limit",    "1000"
        );

        // ответ Bybit → Map<String, Object>
        Map<String, Object> json = client.publicGet("/v5/market/kline", params);

        /* result.list : [[openTime,open,high,low,close,volume,turnover], ...] */
        Map<String, Object> result = (Map<String, Object>) json.get("result");
        List<List<String>>  list   = (List<List<String>>) result.get("list");

        if (list == null || list.isEmpty()) {
            log.warn("Bybit вернул пустой список свечей для {}", symbol);
            return Collections.emptyList();
        }

        /* разворачиваем, убираем текущую незакрытую свечу */
        Collections.reverse(list);
        list.remove(list.size() - 1);

        List<Candle> candles = list.stream()
                .map(raw -> CandleMapper.map(raw, symbol, interval))
                .toList();

        log.info("Сохраняем {} исторических свечей {}-{}", candles.size(), symbol, interval);
        return repo.saveAll(candles);
    }
}
