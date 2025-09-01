package com.pinbot.botprime.backtest;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/backtest/main")
public class MainBacktestController {

    private final MainBacktesterService service;

    public MainBacktestController(MainBacktesterService service) {
        this.service = service;
    }

    @PostMapping(value = "/run", produces = MediaType.TEXT_PLAIN_VALUE)
    public String run() {
        return service.run();
    }
}
