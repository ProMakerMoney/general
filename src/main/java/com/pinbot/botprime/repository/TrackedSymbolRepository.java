package com.pinbot.botprime.repository;

import com.pinbot.botprime.persistence.TrackedSymbolEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TrackedSymbolRepository extends JpaRepository<TrackedSymbolEntity, Long> {

    Optional<TrackedSymbolEntity> findBySymbolAndTimeframe(String symbol, String timeframe);

    boolean existsBySymbolAndTimeframe(String symbol, String timeframe);

    void deleteBySymbolAndTimeframe(String symbol, String timeframe);
}
