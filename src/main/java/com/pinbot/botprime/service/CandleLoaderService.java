package com.pinbot.botprime.service;

import com.pinbot.botprime.client.BybitClient;
import com.pinbot.botprime.dto.CandleDto;
import com.pinbot.botprime.model.Candle;
import com.pinbot.botprime.repository.CandleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class CandleLoaderService {

    private final BybitClient client;
    private final CandleRepository repo;

    @Transactional
    public void loadRecent(String symbol, String tf, int limit) {
        List<CandleDto> dtos = client.getCandles(symbol, tf, limit);
        List<Candle> entities = dtos.stream()
                .map(d -> {
                    Candle c = new Candle();
                    c.setSymbol(symbol);
                    c.setTimeframe(tf);
                    c.setOpenTime(d.getOpenTime());
                    c.setCloseTime(d.getCloseTime());
                    c.setOpen(d.getOpen());
                    c.setHigh(d.getHigh());
                    c.setLow(d.getLow());
                    c.setClose(d.getClose());
                    c.setVolume(d.getVolume());
                    return c;
                }).toList();
        repo.saveAll(entities);
        log.info("Импортировано {} свечей {}@{}", entities.size(), symbol, tf);
    }
}