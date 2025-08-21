package com.pinbot.botprime.repository;

import com.pinbot.botprime.persistence.CandleEntity;
import com.pinbot.botprime.persistence.CandlePk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface CandleRepository extends JpaRepository<CandleEntity, CandlePk> {

    @Query("""
           SELECT MAX(c.id.openTime)
             FROM CandleEntity c
            WHERE c.id.symbol = :symbol
              AND c.id.timeframe = :timeframe
           """)
    Instant findMaxOpenTime(@Param("symbol") String symbol,
                            @Param("timeframe") String timeframe);

    @Query("""
           SELECT c
             FROM CandleEntity c
            WHERE c.id.symbol = :symbol
              AND c.id.timeframe = :timeframe
         ORDER BY c.id.openTime ASC
           """)
    List<CandleEntity> findAllOrdered(@Param("symbol") String symbol,
                                      @Param("timeframe") String timeframe);
}


