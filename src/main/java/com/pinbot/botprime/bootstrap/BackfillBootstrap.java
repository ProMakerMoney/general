package com.pinbot.botprime.bootstrap;

import com.pinbot.botprime.service.CandleUpdateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Выполняется один раз сразу после поднятия контекста.
 * Можно включать/выключать через property:
 * bot.backfill.on-startup=true
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class BackfillBootstrap {

    private final CandleUpdateService candleUpdateService;

    @Bean
    ApplicationRunner backfillRunner() {
        return args -> {
            String symbol   = "BTCUSDT";
            String timeframe = "30";
            log.info(">>> Auto-backfill on startup: {} {}", symbol, timeframe);
            candleUpdateService.backfillYear(symbol, timeframe);
            log.info(">>> Auto-backfill finished");
        };
    }
}