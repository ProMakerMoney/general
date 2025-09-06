package com.pinbot.botprime.strategy;

import com.pinbot.botprime.backtest.IndicatorDao.Bar;
import com.pinbot.botprime.trade.MainBacktestTrade;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * MainStrategy:
 *  - Входы/окна как раньше.
 *  - TP1 = 2R (50% объёма), затем стоп остатка = BE, TP2 = финальный выход.
 *  - RSI-выходы только на 2h (сравнение i-4 и i).
 *
 *  - Стоп (НОВАЯ ЛОГИКА — как в FirstStrategy):
 *      * Окно импульсов: [entryIndex-5 .. entryIndex] (6 баров, включая бар входа).
 *      * Если в окне нет импульса → стоп по TEMA9: LONG = min(TEMA9), SHORT = max(TEMA9) в этом окне.
 *      * Если в окне есть импульс — берём самую импульсную свечу (по |close-open|/open; при равенстве — более свежую),
 *        и ищем кандидаты-уровни:
 *          - EMA110 и EMA200 на баре импульса,
 *          - уровни кросса EMA11/EMA30 в окне (уровень = (ema11+ema30)/2 на баре кросса).
 *        Для LONG — сначала в нижней половине тела импульса, для SHORT — в верхней половине;
 *        если нет попаданий — расширяем зону ещё на пол-тела наружу (LONG — вниз; SHORT — вверх).
 *        Если кандидатов нет вовсе → fallback на TEMA9 (как выше).
 *        Если кандидаты есть:
 *          - Если разница между min и max кандидатов < 0.03% (к среднему) — LONG берём min, SHORT — max.
 *          - Иначе LONG — min, SHORT — max.
 *        Источник стопа пишем в stop_source: TEMA9 | EMA110 | EMA200 | CROSS.
 *
 *  В trade пишем: stop_source и impulse (true/false, был ли импульс в окне).
 */
public class MainStrategy {

    // Риск/комиссия/шаги
    private static final BigDecimal RISK_USDT = new BigDecimal("100");
    private static final BigDecimal FEE_RATE  = new BigDecimal("0.0011"); // 0.055% * 2
    private static final BigDecimal MIN_QTY   = new BigDecimal("0.001");
    private static final BigDecimal STEP_QTY  = new BigDecimal("0.001");
    private static final Duration   TF        = Duration.ofMinutes(30);

    // Порог эквивалентности уровней (0.03%)
    private static final BigDecimal EQUIV_REL = new BigDecimal("0.0003");

    enum Dir { LONG, SHORT }

    private static class PendingEntry {
        int entryIndex;
        Dir dir;
        BigDecimal stopPrice;
        BigDecimal entryPrice;
        BigDecimal qtyBtc;
        String stopSource;   // TEMA9 | CROSS | EMA110 | EMA200
        boolean impulse;     // в окне был импульс
    }

    private static class Position {
        Dir dir;
        BigDecimal entryPrice;
        BigDecimal stopPrice;     // текущий стоп (после TP1 = BE)
        BigDecimal initialStop;   // исходный стоп для отчётности/TP1
        BigDecimal qtyFull;
        BigDecimal qtyHalf1;
        BigDecimal qtyHalf2;
        Instant entryTime;
        int entryIndex;

        // TP1
        boolean tp1Done = false;
        BigDecimal tp1Price = null;

        // RSI-армирование на 2h
        boolean armed75 = false;
        boolean armed35 = false;

        // debug
        String stopSource;
        boolean impulse;
    }

    private static class WindowAfterSignal1 { Dir dir; int startedAt; int deadline; int s1Index; }
    private static class WindowAfterSignal2 { int startedAt; int deadline; }

    /** Результат расчёта стопа */
    private static class StopCalcResult {
        final BigDecimal stop;
        final String source;   // TEMA9 | EMA110 | EMA200 | CROSS
        final boolean impulse; // был ли импульс в окне
        StopCalcResult(BigDecimal stop, String source, boolean impulse) {
            this.stop = stop; this.source = source; this.impulse = impulse;
        }
    }

    public List<MainBacktestTrade> backtest(List<Bar> bars) {
        List<MainBacktestTrade> trades = new ArrayList<>();
        if (bars == null || bars.size() < 10) return trades;

        Position pos = null;
        PendingEntry pending = null;
        WindowAfterSignal1 w1 = null;
        WindowAfterSignal2 w2 = null;

        for (int i = 0; i < bars.size(); i++) {
            Bar b = bars.get(i);
            Bar prev = (i > 0) ? bars.get(i - 1) : null;

            // 1) Активировать отложенный вход (по OPEN текущего бара)
            if (pending != null && pending.entryIndex == i) {
                pos = new Position();
                pos.dir         = pending.dir;
                pos.entryPrice  = pending.entryPrice;
                pos.stopPrice   = pending.stopPrice;
                pos.initialStop = pending.stopPrice;
                pos.qtyFull     = pending.qtyBtc;
                pos.entryTime   = b.openTime();
                pos.entryIndex  = i;
                pos.stopSource  = pending.stopSource;
                pos.impulse     = pending.impulse;

                if (pos.qtyFull.compareTo(new BigDecimal("0.002")) >= 0) {
                    pos.qtyHalf1 = floorToStep(pos.qtyFull.divide(new BigDecimal("2"), 10, RoundingMode.DOWN), STEP_QTY);
                    pos.qtyHalf2 = pos.qtyFull.subtract(pos.qtyHalf1);
                } else {
                    pos.qtyHalf1 = BigDecimal.ZERO; // TP1 не делаем
                    pos.qtyHalf2 = pos.qtyFull;
                }
                pending = null;
            }

            // 2) Управление позицией: SL → TP1 → RSI (2h)
            boolean positionClosedThisBar = false;
            if (pos != null) {
                // 2.1) SL первичен
                if (pos.dir == Dir.LONG) {
                    if (b.low().compareTo(pos.stopPrice) <= 0) {
                        String reason = pos.tp1Done ? "ONLY_TP_1" : "STOP_LOSS";
                        trades.add(buildTrade(pos, nextOpenTime(b), scale2(pos.stopPrice), reason));
                        pos = null; positionClosedThisBar = true;
                    }
                } else {
                    if (b.high().compareTo(pos.stopPrice) >= 0) {
                        String reason = pos.tp1Done ? "ONLY_TP_1" : "STOP_LOSS";
                        trades.add(buildTrade(pos, nextOpenTime(b), scale2(pos.stopPrice), reason));
                        pos = null; positionClosedThisBar = true;
                    }
                }

                // 2.2) TP1 = 2R (для половины), затем стоп остатка = BE
                if (!positionClosedThisBar && !pos.tp1Done && pos.qtyHalf1.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal R = pos.entryPrice.subtract(pos.initialStop).abs();
                    BigDecimal tp1 = (pos.dir == Dir.LONG)
                            ? pos.entryPrice.add(R.multiply(new BigDecimal("2")))
                            : pos.entryPrice.subtract(R.multiply(new BigDecimal("2")));
                    boolean touchedTp1 = (pos.dir == Dir.LONG)
                            ? b.high().compareTo(tp1) >= 0
                            : b.low().compareTo(tp1)  <= 0;
                    if (touchedTp1) {
                        pos.tp1Done  = true;
                        pos.tp1Price = scale2(tp1);
                        pos.stopPrice = pos.entryPrice; // BE на остаток
                    }
                }

                // 2.3) RSI-выход только на 2h (сравнение i-4 и i)
                if (!positionClosedThisBar && i >= 4 && is2hBoundary(b.openTime())) {
                    Bar prev2h = bars.get(i - 4);
                    BigDecimal rPrev = prev2h.rsi2h();
                    BigDecimal sPrev = prev2h.smaRsi2h();
                    BigDecimal r     = b.rsi2h();
                    BigDecimal s     = b.smaRsi2h();
                    if (rPrev != null && sPrev != null && r != null && s != null) {
                        if (pos.dir == Dir.LONG) {
                            if (!pos.armed75 && r.compareTo(new BigDecimal("75")) >= 0) pos.armed75 = true;
                            boolean crossDown = rPrev.compareTo(sPrev) > 0 && r.compareTo(s) <= 0;
                            boolean armed75Ex = pos.armed75 && r.compareTo(new BigDecimal("75")) < 0;
                            if (crossDown || armed75Ex) {
                                trades.add(buildTrade(pos, nextOpenTime(b), scale2(b.close()), crossDown ? "RSI_CROSS" : "RSI_75_35"));
                                pos = null; positionClosedThisBar = true;
                            }
                        } else {
                            if (!pos.armed35 && r.compareTo(new BigDecimal("35")) <= 0) pos.armed35 = true;
                            boolean crossUp   = rPrev.compareTo(sPrev) < 0 && r.compareTo(s) >= 0;
                            boolean armed35Ex = pos.armed35 && r.compareTo(new BigDecimal("35")) > 0;
                            if (crossUp || armed35Ex) {
                                trades.add(buildTrade(pos, nextOpenTime(b), scale2(b.close()), crossUp ? "RSI_CROSS" : "RSI_75_35"));
                                pos = null; positionClosedThisBar = true;
                            }
                        }
                    }
                }
            }

            // 3) Сигналы (30m)
            boolean s1Long=false, s1Short=false, s2=false;
            if (prev != null) {
                s1Long  = prev.ema11().compareTo(prev.ema30()) < 0 && b.ema11().compareTo(b.ema30()) >= 0;
                s1Short = prev.ema11().compareTo(prev.ema30()) > 0 && b.ema11().compareTo(b.ema30()) <= 0;
            }
            s2 = b.low().compareTo(b.ema110()) <= 0 && b.ema110().compareTo(b.high()) <= 0;

            // 4) Обновляем окна
            WindowAfterSignal1 w1New = null;
            if (s1Long)  { w1New = new WindowAfterSignal1(); w1New.dir=Dir.LONG;  w1New.startedAt=i; w1New.deadline=i+2; w1New.s1Index=i; }
            if (s1Short) { w1New = new WindowAfterSignal1(); w1New.dir=Dir.SHORT; w1New.startedAt=i; w1New.deadline=i+2; w1New.s1Index=i; }
            if (w1New != null) w1 = w1New;

            WindowAfterSignal2 w2New = null;
            if (s2) { w2New = new WindowAfterSignal2(); w2New.startedAt=i; w2New.deadline=i+5; }
            if (w2New != null) w2 = w2New;

            // 5) Пара сформирована?
            Dir entryDir = null;
            if (w1 != null && s2 && i <= w1.deadline) { entryDir = w1.dir; }
            if (w2 != null && i <= w2.deadline && (s1Long || s1Short)) {
                entryDir = s1Long ? Dir.LONG : Dir.SHORT;
            }

            // 6) Переворот
            if (pos != null && entryDir != null && pos.dir != entryDir) {
                trades.add(buildTrade(pos, nextOpenTime(b), scale2(b.close()), "REVERSAL_CLOSE"));
                pos = null; positionClosedThisBar = true;
            }

            // 7) Планируем вход на i+1
            if (entryDir != null) {
                int entryIndex = i + 1;
                if (entryIndex < bars.size()) {
                    Bar entryBar = bars.get(entryIndex);

                    // === НОВЫЙ расчёт стопа (как в FirstStrategy) ===
                    StopCalcResult sc = computeStopForEntry(bars, entryIndex, entryDir);

                    if (sc != null && sc.stop != null) {
                        BigDecimal entryPrice = entryBar.open();
                        BigDecimal qty = calcQty(entryPrice, sc.stop);
                        if (qty.compareTo(MIN_QTY) >= 0) {
                            pending = new PendingEntry();
                            pending.entryIndex = entryIndex;
                            pending.dir        = entryDir;
                            pending.stopPrice  = scale2(sc.stop);
                            pending.entryPrice = scale2(entryPrice);
                            pending.qtyBtc     = qty;
                            pending.stopSource = sc.source != null ? sc.source : "TEMA9";
                            pending.impulse    = sc.impulse;
                        }
                    }
                }
                // сброс окон
                w1 = null; w2 = null;
            }

            // 8) Истечение окон
            if (w1 != null && i > w1.deadline) w1 = null;
            if (w2 != null && i > w2.deadline) w2 = null;
        }

        return trades;
    }

    // ====== НОВОЕ: ЛОГИКА СТОПА (как в FirstStrategy) ======

    private StopCalcResult computeStopForEntry(List<Bar> bars, int entryIndex, Dir dir) {
        // Окно импульсов: [entryIndex-5 .. entryIndex]
        int from = Math.max(0, entryIndex - 5);
        int to   = entryIndex;

        Integer impIdx = findStrongestImpulseInWindow(bars, from, to);
        boolean impulse = (impIdx != null);

        // Fallback: если нет импульса — TEMA9 (min/max) в окне
        if (impIdx == null) {
            BigDecimal stop = stopByTema9Window(bars, entryIndex, dir);
            return new StopCalcResult(stop, "TEMA9", false);
        }

        Bar imp = bars.get(impIdx);

        // Тело импульсной свечи
        BigDecimal open  = imp.open();
        BigDecimal close = imp.close();
        BigDecimal bodyHigh = open.max(close);
        BigDecimal bodyLow  = open.min(close);
        BigDecimal body = bodyHigh.subtract(bodyLow);

        if (body.signum() <= 0) {
            BigDecimal stop = stopByTema9Window(bars, entryIndex, dir);
            return new StopCalcResult(stop, "TEMA9", true);
        }

        BigDecimal half = body.divide(new BigDecimal("2"), 10, RoundingMode.HALF_UP);
        BigDecimal mid  = bodyLow.add(half);

        // Зоны
        Range zone1 = (dir == Dir.LONG) ? new Range(bodyLow, mid) : new Range(mid, bodyHigh);
        Range zone2 = (dir == Dir.LONG) ? new Range(bodyLow.subtract(half), bodyLow)
                : new Range(bodyHigh, bodyHigh.add(half));

        // Кандидаты с источниками
        List<Cand> cands = new ArrayList<>();

        addIfInZone(cands, new Cand(imp.ema110(), "EMA110"), zone1);
        addIfInZone(cands, new Cand(imp.ema200(), "EMA200"), zone1);
        for (Integer crossIdx : findCrossIdxInWindow(bars, from, to)) {
            BigDecimal lvl = avg(bars.get(crossIdx).ema11(), bars.get(crossIdx).ema30());
            addIfInZone(cands, new Cand(lvl, "CROSS"), zone1);
        }

        if (cands.isEmpty()) {
            addIfInZone(cands, new Cand(imp.ema110(), "EMA110"), zone2);
            addIfInZone(cands, new Cand(imp.ema200(), "EMA200"), zone2);
            for (Integer crossIdx : findCrossIdxInWindow(bars, from, to)) {
                BigDecimal lvl = avg(bars.get(crossIdx).ema11(), bars.get(crossIdx).ema30());
                addIfInZone(cands, new Cand(lvl, "CROSS"), zone2);
            }
        }

        if (cands.isEmpty()) {
            BigDecimal stop = stopByTema9Window(bars, entryIndex, dir);
            return new StopCalcResult(stop, "TEMA9", true);
        }

        // Правило выбора
        cands.sort(Comparator.comparing(c -> c.level));
        BigDecimal min = cands.get(0).level;
        BigDecimal max = cands.get(cands.size() - 1).level;

        BigDecimal chosenLevel = areEquivalent(min, max)
                ? (dir == Dir.LONG ? min : max)
                : (dir == Dir.LONG ? min : max);

        // Источник выбранного уровня
        String source = "TEMA9";
        for (Cand c : cands) {
            if (c.level.compareTo(chosenLevel) == 0) {
                source = c.source;
                break;
            }
        }

        return new StopCalcResult(chosenLevel, source, true);
    }

    private static class Cand {
        final BigDecimal level; final String source;
        Cand(BigDecimal level, String source) { this.level = level; this.source = source; }
    }

    /** Общее правило: LONG — min(TEMA9), SHORT — max(TEMA9) в окне [entryIndex-5 .. entryIndex]. */
    private BigDecimal stopByTema9Window(List<Bar> bars, int entryIndex, Dir dir) {
        int from = Math.max(0, entryIndex - 5);
        int to   = entryIndex;

        BigDecimal best = null;
        for (int j = from; j <= to; j++) {
            BigDecimal t9 = bars.get(j).tema9();
            if (t9 == null) continue;
            if (best == null) best = t9;
            else best = (dir == Dir.LONG) ? best.min(t9) : best.max(t9);
        }
        return best;
    }

    private static class Range {
        final BigDecimal lo, hi;
        Range(BigDecimal a, BigDecimal b) { this.lo = a.min(b); this.hi = a.max(b); }
        boolean containsInclusive(BigDecimal x) {
            return x != null && x.compareTo(lo) >= 0 && x.compareTo(hi) <= 0;
        }
    }

    private static void addIfInZone(List<Cand> dst, Cand cand, Range zone) {
        if (cand.level != null && zone.containsInclusive(cand.level)) dst.add(cand);
    }

    private static BigDecimal avg(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) return null;
        return a.add(b).divide(new BigDecimal("2"), 10, RoundingMode.HALF_UP);
    }

    /** Самая импульсная свеча в окне [from..to] по |close-open|/open; при равенстве — более свежая. */
    private Integer findStrongestImpulseInWindow(List<Bar> bars, int from, int to) {
        Integer bestIdx = null;
        BigDecimal bestScore = null;

        for (int i = Math.max(0, from); i <= Math.min(to, bars.size()-1); i++) {
            Bar b = bars.get(i);
            boolean imp = false;
            try { imp = b.isImpulse(); } catch (Throwable ignore) { /* если поля нет */ }
            if (!imp) continue;

            BigDecimal open = b.open();
            BigDecimal close = b.close();
            if (open == null || open.signum() == 0 || close == null) continue;

            BigDecimal score = close.subtract(open).abs()
                    .divide(open.abs(), 10, RoundingMode.HALF_UP);
            if (bestIdx == null || score.compareTo(bestScore) > 0
                    || (score.compareTo(bestScore) == 0 && i > bestIdx)) {
                bestIdx = i; bestScore = score;
            }
        }
        return bestIdx;
    }

    /** Индексы баров, где есть кросс EMA11/EMA30 в окне [from..to] (включительно). */
    private List<Integer> findCrossIdxInWindow(List<Bar> bars, int from, int to) {
        List<Integer> res = new ArrayList<>();
        int lo = Math.max(1, from); // нужен prev
        int hi = Math.min(to, bars.size()-1);
        for (int i = lo; i <= hi; i++) {
            Bar prev = bars.get(i - 1);
            Bar cur  = bars.get(i);
            if (prev.ema11() == null || prev.ema30() == null || cur.ema11() == null || cur.ema30() == null) continue;

            boolean up   = prev.ema11().compareTo(prev.ema30()) < 0 && cur.ema11().compareTo(cur.ema30()) >= 0;
            boolean down = prev.ema11().compareTo(prev.ema30()) > 0 && cur.ema11().compareTo(cur.ema30()) <= 0;
            if (up || down) res.add(i);
        }
        return res;
    }

    /** Эквивалентность значений: |a-b| / ((a+b)/2) <= 0.0003 (0.03%) */
    private boolean areEquivalent(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) return false;
        BigDecimal diff = a.subtract(b).abs();
        BigDecimal mean = a.add(b).divide(new BigDecimal("2"), 10, RoundingMode.HALF_UP);
        if (mean.signum() == 0) return diff.signum() == 0;
        BigDecimal rel = diff.divide(mean, 10, RoundingMode.HALF_UP);
        return rel.compareTo(EQUIV_REL) <= 0;
    }

    // ===== Прочие хелперы (без изменений) =====

    private static Instant nextOpenTime(Bar b) { return b.openTime().plus(TF); }

    private static boolean is2hBoundary(Instant ts) {
        ZonedDateTime z = ts.atZone(ZoneOffset.UTC);
        return z.getMinute() == 0 && (z.getHour() % 2 == 0);
    }

    private static BigDecimal calcQty(BigDecimal entry, BigDecimal stop) {
        BigDecimal delta = entry.subtract(stop).abs();
        BigDecimal denom = delta.add(entry.multiply(FEE_RATE));
        if (denom.signum() == 0) return BigDecimal.ZERO;
        BigDecimal raw = RISK_USDT.divide(denom, 10, RoundingMode.HALF_UP);
        BigDecimal floored = floorToStep(raw, STEP_QTY);
        if (floored.compareTo(MIN_QTY) < 0) return MIN_QTY;
        return floored;
    }

    private static BigDecimal floorToStep(BigDecimal value, BigDecimal step) {
        if (value.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;
        BigDecimal steps = value.divide(step, 0, RoundingMode.DOWN);
        return steps.multiply(step);
    }

    private static BigDecimal scale2(BigDecimal v) { return v.setScale(2, RoundingMode.HALF_UP); }

    private static MainBacktestTrade buildTrade(Position pos, Instant exitTime, BigDecimal exitPrice, String reason) {
        MainBacktestTrade t = new MainBacktestTrade();
        t.setEntryTime(pos.entryTime);
        t.setSide(pos.dir == Dir.LONG ? "LONG" : "SHORT");
        t.setEntryPrice(scale2(pos.entryPrice));
        t.setStopPrice(scale2(pos.initialStop)); // фиксируем исходный (выбранный) стоп
        t.setQtyBtc(pos.qtyFull.setScale(3, RoundingMode.HALF_UP));
        t.setTp1Price(pos.tp1Done ? pos.tp1Price : null);
        t.setExitTime(exitTime);
        t.setExitPrice(scale2(exitPrice));
        t.setTp2Price(scale2(exitPrice));
        t.setReason(reason);
        t.setStopSource(pos.stopSource);
        t.setImpulse(pos.impulse);
        return t;
    }
}
