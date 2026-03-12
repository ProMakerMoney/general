package com.pinbot.botprime.strategy;

import com.pinbot.botprime.backtest.IndicatorDao.Bar;
import com.pinbot.botprime.trade.BacktestTrade;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
public class FirstStrategy implements Strategy {

    private static final BigDecimal FEE_RATE = new BigDecimal("0.00110"); // 0.11% round-trip
    private static final BigDecimal MIN_QTY = new BigDecimal("0.001");
    private static final BigDecimal STEP_QTY = new BigDecimal("0.001");
    private static final Duration TF = Duration.ofMinutes(30);

    private static final BigDecimal EQUIV_REL = new BigDecimal("0.0003");
    private static final BigDecimal FIXED_RISK_USD = new BigDecimal("100.00");

    private static final BigDecimal RSI_HIGH = new BigDecimal("75");
    private static final BigDecimal RSI_LOW = new BigDecimal("25");

    enum Dir { LONG, SHORT }

    private static class PendingEntry {
        int entryIndex;
        Dir dir;
        BigDecimal stopPrice;
        BigDecimal plannedEntryPrice;
    }

    private static class Position {
        Dir dir;
        BigDecimal entryPrice;
        BigDecimal stopPrice;
        BigDecimal qtyBtc;
        Instant entryTime;
        boolean armed75 = false;
        boolean armed25 = false;
    }

    private static class WindowAfterSignal1 {
        Dir dir;
        int startedAt;
        int deadline;
    }

    private static class WindowAfterSignal2 {
        int startedAt;
        int deadline;
    }

    @Override
    public List<BacktestTrade> backtest(List<Bar> bars) {
        List<BacktestTrade> trades = new ArrayList<>();
        if (bars == null || bars.size() < 10) return trades;

        Position pos = null;
        PendingEntry pending = null;
        WindowAfterSignal1 w1 = null;
        WindowAfterSignal2 w2 = null;

        for (int i = 0; i < bars.size(); i++) {
            Bar b = bars.get(i);
            Bar prev = (i > 0) ? bars.get(i - 1) : null;

            // 1) Активируем pending-вход на open текущего бара
            if (pending != null && pending.entryIndex == i) {
                BigDecimal entryPrice = b.open();
                BigDecimal stopPrice = pending.stopPrice;
                BigDecimal qty = calcQty(entryPrice, stopPrice, FIXED_RISK_USD);

                if (qty.compareTo(MIN_QTY) >= 0) {
                    Position np = new Position();
                    np.dir = pending.dir;
                    np.entryPrice = entryPrice;
                    np.stopPrice = stopPrice;
                    np.qtyBtc = qty;
                    np.entryTime = b.openTime();
                    pos = np;
                }
                pending = null;
            }

            boolean positionClosedThisBar = false;

            // 2) Управление открытой позицией
            if (pos != null) {

                // 2.1) Стоп внутри текущего бара
                if (pos.dir == Dir.LONG) {
                    if (b.low().compareTo(pos.stopPrice) <= 0) {
                        trades.add(toTrade(pos, pos.entryTime, pos.entryPrice, pos.stopPrice,
                                nextOpenTime(b, TF), pos.stopPrice));
                        pos = null;
                        positionClosedThisBar = true;
                    }
                } else {
                    if (b.high().compareTo(pos.stopPrice) >= 0) {
                        trades.add(toTrade(pos, pos.entryTime, pos.entryPrice, pos.stopPrice,
                                nextOpenTime(b, TF), pos.stopPrice));
                        pos = null;
                        positionClosedThisBar = true;
                    }
                }

                // 2.2) RSI-выход только после закрытия 2h бара, исполнение на следующем open
                if (!positionClosedThisBar && i >= 4 && is2hBoundary(b.openTime())) {
                    Bar prev2h = bars.get(i - 4);

                    BigDecimal rsiPrev = prev2h.rsi2h();
                    BigDecimal smaPrev = prev2h.smaRsi2h();
                    BigDecimal rsi = b.rsi2h();
                    BigDecimal sma = b.smaRsi2h();

                    if (rsiPrev != null && smaPrev != null && rsi != null && sma != null) {
                        boolean shouldExit = false;

                        if (pos.dir == Dir.LONG) {
                            if (!pos.armed75 && rsi.compareTo(RSI_HIGH) >= 0) pos.armed75 = true;

                            boolean crossDown = rsiPrev.compareTo(smaPrev) > 0 && rsi.compareTo(sma) <= 0;
                            boolean armed75Exit = pos.armed75 && rsi.compareTo(RSI_HIGH) < 0;

                            shouldExit = crossDown || armed75Exit;
                        } else {
                            if (!pos.armed25 && rsi.compareTo(RSI_LOW) <= 0) pos.armed25 = true;

                            boolean crossUp = rsiPrev.compareTo(smaPrev) < 0 && rsi.compareTo(sma) >= 0;
                            boolean armed25Exit = pos.armed25 && rsi.compareTo(RSI_LOW) > 0;

                            shouldExit = crossUp || armed25Exit;
                        }

                        if (shouldExit) {
                            int exitIndex = i + 1;
                            if (exitIndex < bars.size()) {
                                BigDecimal exitPrice = bars.get(exitIndex).open();
                                Instant exitTime = bars.get(exitIndex).openTime();

                                trades.add(toTrade(pos, pos.entryTime, pos.entryPrice, pos.stopPrice,
                                        exitTime, exitPrice));
                                pos = null;
                                positionClosedThisBar = true;
                            }
                        }
                    }
                }
            }

            // 3) Сигналы на баре i
            boolean s1Long = false, s1Short = false, s2 = false;

            if (prev != null) {
                s1Long = prev.ema11().compareTo(prev.ema30()) < 0 && b.ema11().compareTo(b.ema30()) >= 0;
                s1Short = prev.ema11().compareTo(prev.ema30()) > 0 && b.ema11().compareTo(b.ema30()) <= 0;
            }

            s2 = b.low().compareTo(b.ema110()) <= 0 && b.ema110().compareTo(b.high()) <= 0;

            // 4) Окна
            if (s1Long) {
                w1 = new WindowAfterSignal1();
                w1.dir = Dir.LONG;
                w1.startedAt = i;
                w1.deadline = i + 2;
            } else if (s1Short) {
                w1 = new WindowAfterSignal1();
                w1.dir = Dir.SHORT;
                w1.startedAt = i;
                w1.deadline = i + 2;
            }

            if (s2) {
                w2 = new WindowAfterSignal2();
                w2.startedAt = i;
                w2.deadline = i + 5;
            }

            // 5) Совпадение сигналов
            Dir entryDir = null;

            if (w1 != null && s2 && i <= w1.deadline) {
                entryDir = w1.dir;
            }

            if (w2 != null && i <= w2.deadline && (s1Long || s1Short)) {
                entryDir = s1Long ? Dir.LONG : Dir.SHORT;
            }

            // 6) Планируем вход на следующий бар
            if (entryDir != null) {
                int entryIndex = i + 1;

                if (entryIndex < bars.size()) {
                    BigDecimal stop = computeStopForEntry(bars, entryIndex, entryDir);

                    if (stop != null) {
                        // Переворот: решение на close(i), исполнение на open(i+1)
                        if (pos != null && !positionClosedThisBar) {
                            int exitIndex = i + 1;
                            if (exitIndex < bars.size()) {
                                BigDecimal exitPrice = bars.get(exitIndex).open();
                                Instant exitTime = bars.get(exitIndex).openTime();

                                trades.add(toTrade(pos, pos.entryTime, pos.entryPrice, pos.stopPrice,
                                        exitTime, exitPrice));
                                pos = null;
                            }
                        }

                        PendingEntry pe = new PendingEntry();
                        pe.entryIndex = entryIndex;
                        pe.dir = entryDir;
                        pe.stopPrice = stop;
                        pe.plannedEntryPrice = bars.get(entryIndex).open();
                        pending = pe;
                    }
                }

                w1 = null;
                w2 = null;
            }

            // 7) Инвалидация окон
            if (w1 != null && i > w1.deadline) w1 = null;
            if (w2 != null && i > w2.deadline) w2 = null;
        }

        return trades;
    }

    // ===== Stop helpers =====

    private BigDecimal computeStopForEntry(List<Bar> bars, int entryIndex, Dir dir) {
        // Только прошлые данные, без бара входа
        int from = Math.max(0, entryIndex - 5);
        int to = entryIndex - 1;

        if (to < from) return null;

        Integer impIdx = findStrongestImpulseInWindow(bars, from, to);
        if (impIdx == null) {
            return stopByTema9Window(bars, entryIndex, dir);
        }

        Bar imp = bars.get(impIdx);

        BigDecimal open = imp.open();
        BigDecimal close = imp.close();
        BigDecimal bodyHigh = open.max(close);
        BigDecimal bodyLow = open.min(close);
        BigDecimal body = bodyHigh.subtract(bodyLow);

        if (body.signum() <= 0) {
            return stopByTema9Window(bars, entryIndex, dir);
        }

        BigDecimal half = body.divide(new BigDecimal("2"), 10, RoundingMode.HALF_UP);
        BigDecimal mid = bodyLow.add(half);

        Range zone1 = (dir == Dir.LONG)
                ? new Range(bodyLow, mid)
                : new Range(mid, bodyHigh);

        Range zone2 = (dir == Dir.LONG)
                ? new Range(bodyLow.subtract(half), bodyLow)
                : new Range(bodyHigh, bodyHigh.add(half));

        List<BigDecimal> candidates = new ArrayList<>();

        addIfInZone(candidates, imp.ema110(), zone1);
        addIfInZone(candidates, imp.ema200(), zone1);

        for (Integer crossIdx : findCrossIdxInWindow(bars, from, to)) {
            BigDecimal lvl = avg(bars.get(crossIdx).ema11(), bars.get(crossIdx).ema30());
            addIfInZone(candidates, lvl, zone1);
        }

        if (candidates.isEmpty()) {
            addIfInZone(candidates, imp.ema110(), zone2);
            addIfInZone(candidates, imp.ema200(), zone2);

            for (Integer crossIdx : findCrossIdxInWindow(bars, from, to)) {
                BigDecimal lvl = avg(bars.get(crossIdx).ema11(), bars.get(crossIdx).ema30());
                addIfInZone(candidates, lvl, zone2);
            }
        }

        if (candidates.isEmpty()) {
            return stopByTema9Window(bars, entryIndex, dir);
        }

        candidates.sort(Comparator.naturalOrder());
        BigDecimal min = candidates.get(0);
        BigDecimal max = candidates.get(candidates.size() - 1);

        if (areEquivalent(min, max)) {
            return (dir == Dir.LONG) ? min : max;
        }

        return (dir == Dir.LONG) ? min : max;
    }

    private BigDecimal stopByTema9Window(List<Bar> bars, int entryIndex, Dir dir) {
        // Только прошлые данные, без бара входа
        int from = Math.max(0, entryIndex - 5);
        int to = entryIndex - 1;

        if (to < from) return null;

        BigDecimal best = null;
        for (int j = from; j <= to; j++) {
            BigDecimal t9 = bars.get(j).tema9();
            if (t9 == null) continue;

            if (best == null) {
                best = t9;
            } else {
                best = (dir == Dir.LONG) ? best.min(t9) : best.max(t9);
            }
        }
        return best;
    }

    private static class Range {
        final BigDecimal lo;
        final BigDecimal hi;

        Range(BigDecimal lo, BigDecimal hi) {
            this.lo = lo.min(hi);
            this.hi = lo.max(hi);
        }

        boolean containsInclusive(BigDecimal x) {
            if (x == null) return false;
            return x.compareTo(lo) >= 0 && x.compareTo(hi) <= 0;
        }
    }

    private static void addIfInZone(List<BigDecimal> dst, BigDecimal level, Range zone) {
        if (level != null && zone.containsInclusive(level)) {
            dst.add(level);
        }
    }

    private static BigDecimal avg(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) return null;
        return a.add(b).divide(new BigDecimal("2"), 10, RoundingMode.HALF_UP);
    }

    private Integer findStrongestImpulseInWindow(List<Bar> bars, int from, int to) {
        Integer bestIdx = null;
        BigDecimal bestScore = null;

        for (int i = Math.max(0, from); i <= Math.min(to, bars.size() - 1); i++) {
            Bar b = bars.get(i);

            boolean imp;
            try {
                imp = b.isImpulse();
            } catch (Throwable ignore) {
                imp = false;
            }
            if (!imp) continue;

            BigDecimal open = b.open();
            BigDecimal close = b.close();
            if (open == null || open.signum() == 0 || close == null) continue;

            BigDecimal score = close.subtract(open).abs()
                    .divide(open.abs(), 10, RoundingMode.HALF_UP);

            if (bestIdx == null || score.compareTo(bestScore) > 0
                    || (score.compareTo(bestScore) == 0 && i > bestIdx)) {
                bestIdx = i;
                bestScore = score;
            }
        }
        return bestIdx;
    }

    private List<Integer> findCrossIdxInWindow(List<Bar> bars, int from, int to) {
        List<Integer> res = new ArrayList<>();
        int lo = Math.max(1, from);
        int hi = Math.min(to, bars.size() - 1);

        for (int i = lo; i <= hi; i++) {
            Bar prev = bars.get(i - 1);
            Bar cur = bars.get(i);

            if (prev.ema11() == null || prev.ema30() == null || cur.ema11() == null || cur.ema30() == null) {
                continue;
            }

            boolean up = prev.ema11().compareTo(prev.ema30()) < 0 && cur.ema11().compareTo(cur.ema30()) >= 0;
            boolean down = prev.ema11().compareTo(prev.ema30()) > 0 && cur.ema11().compareTo(cur.ema30()) <= 0;

            if (up || down) res.add(i);
        }

        return res;
    }

    private boolean areEquivalent(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) return false;

        BigDecimal diff = a.subtract(b).abs();
        BigDecimal mean = a.add(b).divide(new BigDecimal("2"), 10, RoundingMode.HALF_UP);

        if (mean.signum() == 0) return diff.signum() == 0;

        BigDecimal rel = diff.divide(mean, 10, RoundingMode.HALF_UP);
        return rel.compareTo(EQUIV_REL) <= 0;
    }

    private static BacktestTrade toTrade(
            Position pos,
            Instant entryTime,
            BigDecimal entryPrice,
            BigDecimal stopPrice,
            Instant exitTime,
            BigDecimal exitPrice
    ) {
        BacktestTrade t = new BacktestTrade();
        t.setEntryTime(entryTime);
        t.setSide(pos.dir == Dir.LONG ? "LONG" : "SHORT");
        t.setEntryPrice(scale2(entryPrice));
        t.setStopPrice(scale2(stopPrice));
        t.setQtyBtc(scale3(pos.qtyBtc));
        t.setExitTime(exitTime);
        t.setExitPrice(scale2(exitPrice));
        return t;
    }

    private static Instant nextOpenTime(Bar b, Duration tf) {
        return b.openTime().plus(tf);
    }

    private static BigDecimal calcQty(BigDecimal entry, BigDecimal stop, BigDecimal riskUsdt) {
        if (riskUsdt == null || riskUsdt.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;

        BigDecimal delta = entry.subtract(stop).abs();
        BigDecimal denom = delta.add(entry.multiply(FEE_RATE));
        if (denom.signum() == 0) return BigDecimal.ZERO;

        BigDecimal raw = riskUsdt.divide(denom, 10, RoundingMode.HALF_UP);
        BigDecimal floored = floorToStep(raw, STEP_QTY);

        if (floored.compareTo(MIN_QTY) < 0) return MIN_QTY;
        return floored;
    }

    private static BigDecimal floorToStep(BigDecimal value, BigDecimal step) {
        if (value.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;
        BigDecimal steps = value.divide(step, 0, RoundingMode.DOWN);
        return steps.multiply(step);
    }

    private static boolean is2hBoundary(Instant ts) {
        ZonedDateTime z = ts.atZone(ZoneOffset.UTC);
        return z.getMinute() == 0 && (z.getHour() % 2 == 0);
    }

    private static BigDecimal scale2(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal scale3(BigDecimal v) {
        return v.setScale(3, RoundingMode.HALF_UP);
    }
}