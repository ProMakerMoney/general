package com.pinbot.botprime.scheduler;

import com.pinbot.botprime.service.CandleUpdateService;
import com.pinbot.botprime.service.IndicatorComputeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
@Slf4j
public class IndicatorScheduler {

    private final CandleUpdateService candleUpdateService;
    private final IndicatorComputeService indicatorComputeService;

    @Value("${bot.compute.symbol:BTCUSDT}")
    private String symbol;

    @Value("${bot.compute.timeframe.30m:30}")
    private String tf30m;

    @Value("${bot.compute.timeframe.2h:2h}")
    private String tf2h;

    @Value("${bot.load-limit:1000}")
    private int limit;

    @Scheduled(cron = "${bot.compute.cron.30m}")
    public void computeIndicators30m() {
        log.info("▶️ CRON: computeIndicators30m()");
        candleUpdateService.updateCandles(symbol, tf30m, limit);
        indicatorComputeService.computeAndLog(symbol, tf30m);
    }

    @Scheduled(cron = "${bot.compute.cron.2h}")
    public void computeIndicators2h() {
        log.info("▶️ CRON: computeIndicators2h()");
        candleUpdateService.updateCandles(symbol, tf30m, limit); // 30m нужны для агрегации в 2h
        indicatorComputeService.computeAndLog(symbol, tf2h);
    }
}

