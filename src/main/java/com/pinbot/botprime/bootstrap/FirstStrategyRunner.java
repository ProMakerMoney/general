package com.pinbot.botprime.bootstrap;

import com.pinbot.botprime.backtest.IndicatorDao;
import com.pinbot.botprime.strategy.FirstStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
@Configuration
@RequiredArgsConstructor
@Slf4j
public class FirstStrategyRunner {
    private final IndicatorDao dao;
    private final FirstStrategy strategy;

    @Bean
    ApplicationRunner fillCrossAndImpulse() {
        return args -> {
            var bars = dao.fetchAllBarsAsc();
            int updated = strategy.backtest(bars).size();
            log.info("FirstStrategyRunner finished: {} bars processed", bars.size());
        };
    }
}