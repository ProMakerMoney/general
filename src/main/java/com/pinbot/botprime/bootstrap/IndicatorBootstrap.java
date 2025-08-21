package com.pinbot.botprime.bootstrap;

import com.pinbot.botprime.service.IndicatorComputeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class IndicatorBootstrap {

    private final IndicatorComputeService service;

    @Value("${indicator.bootstrap.enabled:true}")
    private boolean enabled;

    @Value("${indicator.bootstrap.symbols:BTCUSDT}")
    private String symbolsCsv;

    @Value("${indicator.bootstrap.timeframes:30}")
    private String tfsCsv;

    @Bean
    public ApplicationRunner indicatorRunner() {
        return args -> {
            if (!enabled) {
                log.info("INDICATORS: bootstrap disabled");
                return;
            }
            List<String> symbols = Arrays.stream(symbolsCsv.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList();

            List<String> tfs = Arrays.stream(tfsCsv.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList();

            for (String s : symbols) {
                for (String tf : tfs) {
                    try {
                        service.computeAndStore(s, tf);
                    } catch (Exception e) {
                        log.error("INDICATORS: compute failed for {} {}: {}", s, tf, e.getMessage(), e);
                    }
                }
            }
        };
    }
}
