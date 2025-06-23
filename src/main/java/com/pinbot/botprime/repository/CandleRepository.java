package com.pinbot.botprime.repository;


import com.pinbot.botprime.model.Candle;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CandleRepository extends JpaRepository<Candle, Long> {
}
