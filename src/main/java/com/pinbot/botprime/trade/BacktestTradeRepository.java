package com.pinbot.botprime.trade;


import org.springframework.data.jpa.repository.JpaRepository;


public interface BacktestTradeRepository extends JpaRepository<BacktestTrade, Long> {

}