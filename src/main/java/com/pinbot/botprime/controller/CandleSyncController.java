package com.pinbot.botprime.controller;

import com.pinbot.botprime.candles.CandleSyncService;
import com.pinbot.botprime.dto.BybitInterval;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/candles")
public class CandleSyncController {

    private final CandleSyncService service;

    /**
     * Обновить свечи по ВСЕМ tracked-symbol'ам (догрузка + обновление последних свечей за счёт UPSERT).
     *
     * POST /api/candles/refresh/all?limit=1000
     */
    @PostMapping("/refresh/all")
    public CandleSyncService.RefreshSummary refreshAll(@RequestParam(required = false) Integer limit) {
        return service.refreshAll(limit);
    }

    /**
     * Обновить свечи для одной монеты, только если она есть в tracked list.
     *
     * POST /api/candles/refresh?symbol=ETHUSDT&timeframe=_30&limit=1000
     */
    @PostMapping("/refresh")
    public CandleSyncService.RefreshItem refreshOne(
            @RequestParam String symbol,
            @RequestParam BybitInterval timeframe,
            @RequestParam(required = false) Integer limit
    ) {
        return service.refreshOne(symbol, timeframe, limit);
    }

    /**
     * Бэкафилл по ВСЕМ tracked-symbol'ам на N лет назад.
     *
     * POST /api/candles/backfill/all?years=2&batch=1000&sleepMs=300
     */
    @PostMapping("/backfill/all")
    public CandleSyncService.BackfillSummary backfillAll(
            @RequestParam(required = false) Integer years,
            @RequestParam(required = false) Integer batch,
            @RequestParam(required = false) Integer sleepMs
    ) {
        return service.backfillAll(years, batch, sleepMs);
    }

    /**
     * Бэкафилл для одной монеты, только если она есть в tracked list.
     *
     * POST /api/candles/backfill?symbol=ETHUSDT&timeframe=_30&years=2&batch=1000&sleepMs=300
     */
    @PostMapping("/backfill")
    public CandleSyncService.BackfillItem backfillOne(
            @RequestParam String symbol,
            @RequestParam BybitInterval timeframe,
            @RequestParam(required = false) Integer years,
            @RequestParam(required = false) Integer batch,
            @RequestParam(required = false) Integer sleepMs
    ) {
        return service.backfillOne(symbol, timeframe, years, batch, sleepMs);
    }
}
