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

@Service
@RequiredArgsConstructor
@Slf4j
public class CandleService {

    private final CandleRepository repo;
    private final BybitClient client;
    private final CandleMapper mapper;

    @Transactional
    public void syncHistory(String symbol, String interval, int limit) {
        Instant last = repo.findMaxOpenTime(symbol, interval);
        List<CandleDto> dtos = client.getCandles(symbol, interval, limit);

        List<CandleEntity> toSave = dtos.stream()
                .map(d -> mapper.toEntity(symbol, interval, d))
                .filter(e -> last == null || e.getId().getOpenTime().isAfter(last))
                .collect(Collectors.toList());

        if (!toSave.isEmpty()) {
            repo.saveAll(toSave);
            log.debug("Saved {} new candles for {}/{}", toSave.size(), symbol, interval);
        } else {
            log.debug("No new candles for {}/{}", symbol, interval);
        }
    }
}

