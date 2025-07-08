package com.pinbot.botprime.scheduler;

import com.pinbot.botprime.service.CandleService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;


import java.util.Arrays;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class CandleScheduler {

    private final CandleService candleService;

    @Value("${bot.symbols}")
    private String symbolsConfig;

    @Value("${bot.timeframes}")
    private String intervalsConfig;

    @Value("${bot.load-limit}")
    private int loadLimit;

    @PostConstruct
    public void initialLoad() {
        List<String> symbols   = Arrays.asList(symbolsConfig.split(","));
        List<String> intervals = Arrays.asList(intervalsConfig.split(","));

        log.info(">>> Initial load: fetching last {} candles for {}×{}",
                loadLimit, symbols.size(), intervals.size());

        symbols.forEach(s -> intervals.forEach(tf ->
                candleService.syncHistory(s, tf, loadLimit)
        ));
    }

    @Scheduled(cron = "${bot.load-cron}", zone = "${bot.timezone}")
    public void loadLatest() {
        List<String> symbols   = Arrays.asList(symbolsConfig.split(","));
        List<String> intervals = Arrays.asList(intervalsConfig.split(","));

        symbols.forEach(s -> intervals.forEach(tf ->
                candleService.syncHistory(s, tf, 1)
        ));
    }
}

