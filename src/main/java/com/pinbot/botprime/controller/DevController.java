package com.pinbot.botprime.controller;

import com.pinbot.botprime.service.CandleUpdateService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/dev")
@RequiredArgsConstructor
public class DevController {

    private final CandleUpdateService candleUpdateService;

    /**
     * GET http://localhost:8080/dev/backfill
     * Запустится один раз и вернёт "OK".
     */
    @GetMapping("/backfill")
    public String backfill() {
        // можно вызвать в отдельном потоке, чтобы не блокировать HTTP
        new Thread(() -> candleUpdateService.backfillYear("BTCUSDT", "30")).start();
        return "Backfill started in background, check logs";
    }
}