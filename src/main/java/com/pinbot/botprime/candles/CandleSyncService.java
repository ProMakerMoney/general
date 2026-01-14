package com.pinbot.botprime.candles;

import com.pinbot.botprime.client.BybitClient;
import com.pinbot.botprime.dto.BybitInterval;
import com.pinbot.botprime.dto.CandleDto;
import com.pinbot.botprime.persistence.TrackedSymbolEntity;
import com.pinbot.botprime.repository.TrackedSymbolRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class CandleSyncService {

    private static final int DEFAULT_LIMIT = 1000;

    private final TrackedSymbolRepository trackedRepo;
    private final DynamicCandleTableManager tableManager;
    private final DynamicCandleStorage storage;
    private final BybitClient bybit;

    @Transactional
    public RefreshSummary refreshAll(Integer limit) {
        int l = (limit == null || limit <= 0) ? DEFAULT_LIMIT : limit;
        List<TrackedSymbolEntity> list = trackedRepo.findAll();

        RefreshSummary summary = new RefreshSummary();
        summary.total = list.size();

        for (TrackedSymbolEntity ts : list) {
            BybitInterval interval = BybitInterval.fromApiValue(ts.getTimeframe());
            try {
                int upserted = refreshOneInternal(ts.getSymbol(), interval, l);
                summary.success++;
                summary.details.add(RefreshItem.success(ts.getSymbol(), interval, upserted));
            } catch (Exception e) {
                summary.failed++;
                summary.details.add(RefreshItem.fail(ts.getSymbol(), interval, e.getMessage()));
                log.error("REFRESH failed for {} {}: {}", ts.getSymbol(), ts.getTimeframe(), e.getMessage(), e);
            }
        }
        return summary;
    }

    @Transactional
    public RefreshItem refreshOne(String symbol, BybitInterval interval, Integer limit) {
        int l = (limit == null || limit <= 0) ? DEFAULT_LIMIT : limit;
        ensureTracked(symbol, interval);
        int upserted = refreshOneInternal(symbol, interval, l);
        return RefreshItem.success(symbol, interval, upserted);
    }

    private int refreshOneInternal(String symbol, BybitInterval interval, int limit) {
        // Ensure table exists (in case DB was reset/edited manually)
        tableManager.createTable(symbol, interval);

        // Use existing BybitClient helper (it skips the newest unclosed candle)
        List<CandleDto> candles = bybit.getCandles(symbol, interval.apiValue(), limit);
        String table = CandleTableName.of(symbol, interval);
        return storage.upsertBatch(table, candles);
    }

    @Transactional
    public BackfillSummary backfillAll(Integer years, Integer batch, Integer sleepMs) {
        int y = (years == null || years <= 0) ? 2 : years;
        int b = (batch == null || batch <= 0) ? DEFAULT_LIMIT : batch;
        int s = (sleepMs == null || sleepMs < 0) ? 300 : sleepMs;

        List<TrackedSymbolEntity> list = trackedRepo.findAll();
        BackfillSummary summary = new BackfillSummary();
        summary.total = list.size();

        for (TrackedSymbolEntity ts : list) {
            BybitInterval interval = BybitInterval.fromApiValue(ts.getTimeframe());
            try {
                long loaded = backfillOneInternal(ts.getSymbol(), interval, y, b, s);
                summary.success++;
                summary.details.add(BackfillItem.success(ts.getSymbol(), interval, loaded));
            } catch (Exception e) {
                summary.failed++;
                summary.details.add(BackfillItem.fail(ts.getSymbol(), interval, e.getMessage()));
                log.error("BACKFILL failed for {} {}: {}", ts.getSymbol(), ts.getTimeframe(), e.getMessage(), e);
            }
        }
        return summary;
    }

    @Transactional
    public BackfillItem backfillOne(String symbol, BybitInterval interval, Integer years, Integer batch, Integer sleepMs) {
        int y = (years == null || years <= 0) ? 2 : years;
        int b = (batch == null || batch <= 0) ? DEFAULT_LIMIT : batch;
        int s = (sleepMs == null || sleepMs < 0) ? 300 : sleepMs;

        ensureTracked(symbol, interval);
        long loaded = backfillOneInternal(symbol, interval, y, b, s);
        return BackfillItem.success(symbol, interval, loaded);
    }

    private long backfillOneInternal(String symbol, BybitInterval interval, int years, int batch, int sleepMs) {
        tableManager.createTable(symbol, interval);
        String table = CandleTableName.of(symbol, interval);

        // cutoff in ms epoch
        long cutoff = ZonedDateTime.now(ZoneOffset.UTC)
                .minusYears(years)
                .toInstant()
                .toEpochMilli();

        long totalLoaded = 0;
        while (true) {
            Instant oldest = storage.findMinOpenTime(table);
            long endExclusive = (oldest == null)
                    ? Instant.now().toEpochMilli()
                    : oldest.toEpochMilli();

            if (endExclusive <= cutoff) {
                break;
            }

            long intervalMs = intervalMillisApprox(interval);
            long startInclusive = endExclusive - (long) batch * intervalMs;
            if (startInclusive < cutoff) {
                startInclusive = cutoff;
            }

            Map<String, String> params = Map.of(
                    "category", "linear",
                    "symbol", symbol,
                    "interval", interval.apiValue(),
                    "limit", String.valueOf(batch),
                    "start", String.valueOf(startInclusive),
                    "end", String.valueOf(endExclusive - 1)
            );

            Map<String, Object> raw = bybit.publicGet("/v5/market/kline", params);
            @SuppressWarnings("unchecked")
            List<List<String>> rows = (List<List<String>>) ((Map<?, ?>) raw.get("result")).get("list");

            if (rows == null || rows.isEmpty()) {
                break;
            }

            List<CandleDto> candles = new ArrayList<>(rows.size());
            for (List<String> r : rows) {
                candles.add(new CandleDto(
                        Long.parseLong(r.get(0)),
                        new BigDecimal(r.get(1)).doubleValue(),
                        new BigDecimal(r.get(2)).doubleValue(),
                        new BigDecimal(r.get(3)).doubleValue(),
                        new BigDecimal(r.get(4)).doubleValue(),
                        new BigDecimal(r.get(5)).doubleValue(),
                        new BigDecimal(r.get(6)).doubleValue()
                ));
            }

            int upserted = storage.upsertBatch(table, candles);
            totalLoaded += upserted;

            // Stop if reached cutoff
            Instant newOldest = storage.findMinOpenTime(table);
            if (newOldest != null && newOldest.toEpochMilli() <= cutoff) {
                break;
            }

            if (sleepMs > 0) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        return totalLoaded;
    }

    private void ensureTracked(String symbol, BybitInterval interval) {
        String s = symbol.trim().toUpperCase();
        String tf = interval.apiValue();
        trackedRepo.findBySymbolAndTimeframe(s, tf)
                .orElseThrow(() -> new IllegalArgumentException("Symbol not tracked: " + s + " @ " + tf));
    }

    private long intervalMillisApprox(BybitInterval interval) {
        String v = interval.apiValue();
        if (v.chars().allMatch(Character::isDigit)) {
            long minutes = Long.parseLong(v);
            return minutes * 60_000L;
        }
        return switch (interval) {
            case D -> 86_400_000L;
            case W -> 7 * 86_400_000L;
            case M -> 30 * 86_400_000L;
            default -> 60_000L;
        };
    }

    // --- DTOs for controller responses ---

    public static class RefreshSummary {
        public int total;
        public int success;
        public int failed;
        public List<RefreshItem> details = new ArrayList<>();
    }

    public static class RefreshItem {
        public String symbol;
        public BybitInterval timeframe;
        public String table;
        public int upserted;
        public String error;

        public static RefreshItem success(String symbol, BybitInterval tf, int upserted) {
            RefreshItem i = new RefreshItem();
            i.symbol = symbol;
            i.timeframe = tf;
            i.table = CandleTableName.of(symbol, tf);
            i.upserted = upserted;
            return i;
        }

        public static RefreshItem fail(String symbol, BybitInterval tf, String error) {
            RefreshItem i = new RefreshItem();
            i.symbol = symbol;
            i.timeframe = tf;
            i.table = CandleTableName.of(symbol, tf);
            i.error = error;
            return i;
        }
    }

    public static class BackfillSummary {
        public int total;
        public int success;
        public int failed;
        public List<BackfillItem> details = new ArrayList<>();
    }

    public static class BackfillItem {
        public String symbol;
        public BybitInterval timeframe;
        public String table;
        public long loaded;
        public String error;

        public static BackfillItem success(String symbol, BybitInterval tf, long loaded) {
            BackfillItem i = new BackfillItem();
            i.symbol = symbol;
            i.timeframe = tf;
            i.table = CandleTableName.of(symbol, tf);
            i.loaded = loaded;
            return i;
        }

        public static BackfillItem fail(String symbol, BybitInterval tf, String error) {
            BackfillItem i = new BackfillItem();
            i.symbol = symbol;
            i.timeframe = tf;
            i.table = CandleTableName.of(symbol, tf);
            i.error = error;
            return i;
        }
    }
}
