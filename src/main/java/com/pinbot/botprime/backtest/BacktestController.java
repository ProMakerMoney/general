package com.pinbot.botprime.backtest;


import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/backtest")
public class BacktestController {


    private final BacktesterService svc;


    public BacktestController(BacktesterService svc) { this.svc = svc; }


    @PostMapping("/run")
    public ResponseEntity<String> run() {
        String msg = svc.run();
        return ResponseEntity.ok(msg);
    }
}