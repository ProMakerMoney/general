package com.pinbot.botprime.controller;

import com.pinbot.botprime.model.Trade;
import com.pinbot.botprime.service.TradeService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/trades")
public class TradeController {

    private final TradeService tradeService;

    public TradeController(TradeService tradeService) {
        this.tradeService = tradeService;
    }

    @PostMapping
    public Trade create(@RequestBody Trade trade) {
        return tradeService.save(trade);
    }

    @GetMapping
    public List<Trade> all() {
        return tradeService.findAll();
    }
}
