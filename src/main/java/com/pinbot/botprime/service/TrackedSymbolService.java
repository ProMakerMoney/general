package com.pinbot.botprime.service;

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

    @Transactional
    public TrackedSymbolResponse add(TrackedSymbolCreateRequest req) {
        String symbol = req.getSymbol().trim().toUpperCase();
        String name = req.getName().trim();

        // enum уже валидирован @NotNull на DTO
        BybitInterval interval = req.getTimeframe();
        String timeframe = interval.apiValue(); // "30", "D", ...

        if (repository.existsBySymbolAndTimeframe(symbol, timeframe)) {
            throw new IllegalArgumentException("Already exists: " + symbol + " @ " + timeframe);
        }

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
            // если два запроса одновременно — уникальный индекс все равно защитит
            throw new IllegalArgumentException("Already exists: " + symbol + " @ " + timeframe);
        }
    }

    @Transactional
    public void deleteById(long id) {
        if (!repository.existsById(id)) {
            throw new IllegalArgumentException("Not found: id=" + id);
        }
        repository.deleteById(id);
    }

    /**
     * Удаление по symbol + timeframe в "bybit формате" (например timeframe="30" или "D").
     * Если в контроллере ты хочешь принимать enum — сделаем отдельную перегрузку.
     */
    @Transactional
    public void deleteBySymbolAndTimeframe(String symbol, String timeframe) {
        String s = symbol.trim().toUpperCase();
        String tf = timeframe.trim().toUpperCase(); // для D/W/M; цифры не изменятся

        TrackedSymbolEntity existing = repository.findBySymbolAndTimeframe(s, tf)
                .orElseThrow(() -> new IllegalArgumentException("Not found: " + s + " @ " + tf));

        repository.delete(existing);
    }

    @Transactional(readOnly = true)
    public List<TrackedSymbolResponse> list() {
        return repository.findAll().stream().map(this::toResponse).toList();
    }

    private TrackedSymbolResponse toResponse(TrackedSymbolEntity e) {
        // e.getTimeframe() хранит "30"/"D"/...
        BybitInterval interval = BybitInterval.fromApiValue(e.getTimeframe());

        return TrackedSymbolResponse.builder()
                .id(e.getId())
                .symbol(e.getSymbol())
                .name(e.getName())
                .timeframe(interval)
                .build();
    }
}
