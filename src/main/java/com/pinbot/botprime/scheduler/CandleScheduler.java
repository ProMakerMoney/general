package com.pinbot.botprime.scheduler;

import com.pinbot.botprime.service.CandleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class CandleScheduler {

    private final CandleService candleService;

    /* список монет лучше вынести в конфиг */
    private static final String[] SYMBOLS = {"BTCUSDT", "ETHUSDT"};
    private static final String   INTERVAL = "30"; // 30-минутки

    /** разовая инициализация сразу после старта */
    @Scheduled(initialDelay = 5_000, fixedDelay = Long.MAX_VALUE)
    public void initialLoad() {
        for (String symbol : SYMBOLS) {
            candleService.loadHistory(symbol, INTERVAL);
        }
        log.info("Исторические свечи загружены ✅");
    }

    /** каждые 30 минут подтягиваем последнюю свечу */
    @Scheduled(cron = "0 */30 * * * *")
    public void appendLastCandle() {
        for (String symbol : SYMBOLS) {
            candleService.loadHistory(symbol, INTERVAL); // пока перезагружаем 1000 — просто
        }
    }
}
