package com.pinbot.botprime.service;

import com.pinbot.botprime.model.Candle;
import com.pinbot.botprime.repository.CandleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CandleService {

    private final CandleRepository repo;

    @Transactional
    public Candle save(Candle candle) {
        return repo.save(candle);
    }

    public List<Candle> findAll() {
        return repo.findAll();
    }
}
