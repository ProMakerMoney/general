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

    /**
     * Запускается каждые 30 минут.
     * Обновляем свечи 30m и пересчитываем индикаторы.
     */
    @Scheduled(cron = "${bot.compute.cron.30m}")
    public void computeIndicators30m() {
        log.info("▶️ CRON: computeIndicators30m() — ждём 1.5 сек, чтобы получить закрытую свечу");
        sleepBeforeFetch();

        // Загружаем свежие свечи и считаем индикаторы
        candleUpdateService.updateCandles(symbol, tf30m, limit);
        indicatorComputeService.computeAndLog(symbol, tf30m);
    }

    /**
     * Запускается каждые 2 часа.
     * Для расчета RSI по 2h сначала обновляем свечи 30m, чтобы корректно агрегировать.
     */
    @Scheduled(cron = "${bot.compute.cron.2h}")
    public void computeIndicators2h() {
        log.info("▶️ CRON: computeIndicators2h() — ждём 1.5 сек, чтобы получить закрытую свечу");
        sleepBeforeFetch();

        // Обновляем 30m свечи — они нужны для расчета агрегированного RSI 2h
        candleUpdateService.updateCandles(symbol, tf30m, limit);

        // Считаем индикаторы по 2h таймфрейму
        indicatorComputeService.computeAndLog(symbol, tf2h);
    }

    /**
     * Делаем небольшую задержку, чтобы Bybit успел закрыть последнюю свечу.
     */
    private void sleepBeforeFetch() {
        try {
            Thread.sleep(1500); // 1.5 секунды
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("⚠️ Задержка перед загрузкой свечей была прервана");
        }
    }
}


