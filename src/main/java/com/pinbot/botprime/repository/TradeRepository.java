package com.pinbot.botprime.repository;

import com.pinbot.botprime.model.Trade;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TradeRepository extends JpaRepository<Trade, Long> {
}
