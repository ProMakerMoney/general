package com.pinbot.botprime.controller;

import com.pinbot.botprime.dto.BybitInterval;
import com.pinbot.botprime.dto.TrackedSymbolCreateRequest;
import com.pinbot.botprime.dto.TrackedSymbolResponse;
import com.pinbot.botprime.service.TrackedSymbolService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/tracked-symbols")
public class TrackedSymbolController {

    private final TrackedSymbolService service;

    @GetMapping
    public List<TrackedSymbolResponse> list() {
        return service.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TrackedSymbolResponse add(@Valid @RequestBody TrackedSymbolCreateRequest request) {
        return service.add(request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteById(@PathVariable long id) {
        service.deleteById(id);
    }

    /**
     * DELETE /api/tracked-symbols?symbol=ETHUSDT&timeframe=_30
     */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteBySymbolAndTimeframe(
            @RequestParam String symbol,
            @RequestParam BybitInterval timeframe
    ) {
        service.deleteBySymbolAndTimeframe(symbol, timeframe);
    }
}
