package com.pinbot.botprime.service;

import com.pinbot.botprime.candles.DynamicCandleTableManager;
import com.pinbot.botprime.dto.BybitInterval;
import com.pinbot.botprime.dto.TrackedSymbolCreateRequest;
import com.pinbot.botprime.dto.TrackedSymbolResponse;
import com.pinbot.botprime.persistence.TrackedSymbolEntity;
import com.pinbot.botprime.repository.TrackedSymbolRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TrackedSymbolService {

    private final TrackedSymbolRepository repository;
    private final DynamicCandleTableManager tableManager;

    @Transactional
    public TrackedSymbolResponse add(TrackedSymbolCreateRequest req) {
        String symbol = req.getSymbol().trim().toUpperCase();
        String name = req.getName().trim();
        BybitInterval interval = req.getTimeframe();
        String timeframe = interval.apiValue();

        if (repository.existsBySymbolAndTimeframe(symbol, timeframe)) {
            throw new IllegalArgumentException("Already exists: " + symbol + " @ " + timeframe);
        }

        // Create candles table first, so we never end up with a tracked record without a table.
        tableManager.createTable(symbol, interval);

        TrackedSymbolEntity entity = TrackedSymbolEntity.builder()
                .symbol(symbol)
                .name(name)
                .timeframe(timeframe)
                .createdAt(OffsetDateTime.now())
                .build();

        try {
            TrackedSymbolEntity saved = repository.save(entity);
            return toResponse(saved);
        } catch (DataIntegrityViolationException e) {
            // If a race happened, drop the table we created (best effort), then bubble the error.
            try {
                tableManager.dropTable(symbol, interval);
            } catch (Exception ignored) {
                // best-effort cleanup
            }
            throw new IllegalArgumentException("Already exists: " + symbol + " @ " + timeframe);
        }
    }

    @Transactional
    public void deleteById(long id) {
        TrackedSymbolEntity existing = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Not found: id=" + id));

        BybitInterval interval = BybitInterval.fromApiValue(existing.getTimeframe());
        repository.deleteById(id);
        tableManager.dropTable(existing.getSymbol(), interval);
    }

    @Transactional
    public void deleteBySymbolAndTimeframe(String symbol, BybitInterval interval) {
        String s = symbol.trim().toUpperCase();
        String tf = interval.apiValue();

        TrackedSymbolEntity existing = repository.findBySymbolAndTimeframe(s, tf)
                .orElseThrow(() -> new IllegalArgumentException("Not found: " + s + " @ " + tf));

        repository.delete(existing);
        tableManager.dropTable(s, interval);
    }

    @Transactional(readOnly = true)
    public List<TrackedSymbolResponse> list() {
        return repository.findAll().stream().map(this::toResponse).toList();
    }

    private TrackedSymbolResponse toResponse(TrackedSymbolEntity e) {
        return TrackedSymbolResponse.builder()
                .id(e.getId())
                .symbol(e.getSymbol())
                .name(e.getName())
                .timeframe(BybitInterval.fromApiValue(e.getTimeframe()))
                .build();
    }
}
