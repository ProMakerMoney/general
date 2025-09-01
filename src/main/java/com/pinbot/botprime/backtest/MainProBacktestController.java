package com.pinbot.botprime.backtest;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/backtest/mainpro")
public class MainProBacktestController {

    private final MainProBacktesterService service;

    public MainProBacktestController(MainProBacktesterService service) {
        this.service = service;
    }

    @PostMapping(value = "/run", produces = MediaType.TEXT_PLAIN_VALUE)
    public String run() {
        return service.run();
    }
}
