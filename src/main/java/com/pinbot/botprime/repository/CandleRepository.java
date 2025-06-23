package com.pinbot.botprime.repository;


import com.pinbot.botprime.model.Candle;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface CandleRepository extends JpaRepository<Candle, Long> {
    List<Candle> findBySymbolAndTimeframeAndOpenTimeGreaterThanEqualAndOpenTimeLessThan(
            String symbol, String timeframe, Instant from, Instant to);
}
