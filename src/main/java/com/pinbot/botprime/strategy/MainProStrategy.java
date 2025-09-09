package com.pinbot.botprime.strategy;

import com.pinbot.botprime.backtest.IndicatorDao.Bar;

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
 * MainPRO (на основе FirstStrategy + Hedge) c новой логикой стопов:
 * - Стоп MAIN рассчитывается по правилам из FirstStrategy:
 *   * окно [entryIndex-5 .. entryIndex], импульсная логика + fallback на TEMA9 окна.
 * - Остальная логика (пары сигналов, входы, приоритеты, hedge) без изменений.
 */
public class MainProStrategy {

    // Риск/комиссия/шаг
    private static final BigDecimal RISK_USDT = new BigDecimal("100");
    private static final BigDecimal FEE_RATE  = new BigDecimal("0.0004"); // 0.055% * 2
    private static final BigDecimal MIN_QTY   = new BigDecimal("0.001");
    private static final BigDecimal STEP_QTY  = new BigDecimal("0.001");
    private static final Duration   TF        = Duration.ofMinutes(30);

    // Порог эквивалентности кандидатов (0.03%)
    private static final BigDecimal EQUIV_REL = new BigDecimal("0.0003");

    public enum Dir  { LONG, SHORT }
    public enum Role { MAIN, HEDGE }

    /** Плоская строка для сохранения сделок сервисом */
    public static final class TradeRow {
        public final Long pairId;
        public final String role;     // MAIN | HEDGE
        public final String side;     // LONG | SHORT
        public final Instant entryTime;
        public final BigDecimal entryPrice;
        public final BigDecimal stopPrice;   // MAIN: вычисленный стоп; HEDGE: его SL(1R)
        public final BigDecimal qtyBtc;
        public final Instant exitTime;
        public final BigDecimal exitPrice;
        public final String reason;   // STOP_LOSS | RSI_CROSS | RSI_75_35 | REVERSAL_CLOSE | HEDGE_TP_AT_MAIN_SL | HEDGE_SL_1R | PAIR_CLOSE_WITH_MAIN

        public TradeRow(Long pairId, String role, String side,
                        Instant entryTime, BigDecimal entryPrice, BigDecimal stopPrice, BigDecimal qtyBtc,
                        Instant exitTime, BigDecimal exitPrice, String reason) {
            this.pairId = pairId;
            this.role = role;
            this.side = side;
            this.entryTime = entryTime;
            this.entryPrice = entryPrice;
            this.stopPrice = stopPrice;
            this.qtyBtc = qtyBtc;
            this.exitTime = exitTime;
            this.exitPrice = exitPrice;
            this.reason = reason;
        }
    }

    /** Внутренние структуры */
    private static class PendingPair {
        int entryIndex;
        Dir mainDir;
        BigDecimal entryPrice;
        BigDecimal mainStop;  // вычисленный стоп
        BigDecimal qtyBtc;
        Long pairId;
    }
    private static class OpenMain {
        Dir dir;
        int entryIndex;
        Instant entryTime;
        BigDecimal entryPrice;
        BigDecimal stopPrice; // фиксированный исходный стоп
        BigDecimal qtyBtc;
        boolean armed75=false, armed35=false;
    }
    private static class OpenHedge {
        Dir dir;
        int entryIndex;
        Instant entryTime;
        BigDecimal entryPrice;
        BigDecimal tpPrice;   // = stop MAIN
        BigDecimal slPrice;   // 1R
        BigDecimal qtyBtc;
    }
    private static class WindowAfterSignal1 { Dir dir; int s1Index; int deadline; WindowAfterSignal1(Dir d,int s1,int dl){dir=d;s1Index=s1;deadline=dl;} }
    private static class WindowAfterSignal2 { int startedAt; int deadline; WindowAfterSignal2(int s,int d){startedAt=s;deadline=d;} }

    /** Главный прогон */
    public List<TradeRow> backtest(List<Bar> bars) {
        List<TradeRow> out = new ArrayList<>();
        if (bars == null || bars.size() < 10) return out;

        PendingPair pending = null;
        OpenMain main = null;
        OpenHedge hedge = null;

        WindowAfterSignal1 w1 = null;
        WindowAfterSignal2 w2 = null;

        long nextPairId = 1L;

        for (int i = 0; i < bars.size(); i++) {
            Bar b = bars.get(i);
            Bar prev = (i > 0) ? bars.get(i-1) : null;

            // ===== 1) Активировать отложенный вход =====
            if (pending != null && pending.entryIndex == i) {
                // Открываем MAIN
                main = new OpenMain();
                main.dir        = pending.mainDir;
                main.entryIndex = i;
                main.entryTime  = b.openTime();
                main.entryPrice = scale2(pending.entryPrice);
                main.stopPrice  = scale2(pending.mainStop);
                main.qtyBtc     = pending.qtyBtc;

                // Открываем HEDGE
                hedge = new OpenHedge();
                hedge.dir        = (main.dir == Dir.LONG) ? Dir.SHORT : Dir.LONG;
                hedge.entryIndex = i;
                hedge.entryTime  = b.openTime();
                hedge.entryPrice = main.entryPrice;
                hedge.qtyBtc     = main.qtyBtc;

                // Рассчёт R и уровней для хеджа
                BigDecimal R = main.entryPrice.subtract(main.stopPrice).abs();
                if (main.dir == Dir.LONG) {
                    hedge.tpPrice = main.stopPrice;                    // ниже входа
                    hedge.slPrice = scale2(main.entryPrice.add(R));    // выше входа
                } else {
                    hedge.tpPrice = main.stopPrice;                    // выше входа
                    hedge.slPrice = scale2(main.entryPrice.subtract(R)); // ниже входа
                }

                pending = null;
            }

            // ===== 2) Управление открытой парой (если есть) =====
            boolean pairOpen = (main != null);
            if (pairOpen) {
                // Приоритет: MAIN SL → HEDGE TP → HEDGE SL → REVERSAL → RSI(2h)

                // 2.1 MAIN SL?
                boolean mainClosedThisBar = false;
                if (main.dir == Dir.LONG) {
                    if (b.low().compareTo(main.stopPrice) <= 0) {
                        out.add(buildRow(nextPairId, Role.MAIN, main.dir, main.entryTime, main.entryPrice, main.stopPrice,
                                main.qtyBtc, nextOpenTime(b), main.stopPrice, "STOP_LOSS"));
                        if (hedge != null) {
                            out.add(buildRow(nextPairId, Role.HEDGE, hedge.dir, hedge.entryTime, hedge.entryPrice, hedge.slPrice,
                                    hedge.qtyBtc, nextOpenTime(b), main.stopPrice, "HEDGE_TP_AT_MAIN_SL"));
                        }
                        main = null; hedge = null; mainClosedThisBar = true;
                    }
                } else { // SHORT
                    if (b.high().compareTo(main.stopPrice) >= 0) {
                        out.add(buildRow(nextPairId, Role.MAIN, main.dir, main.entryTime, main.entryPrice, main.stopPrice,
                                main.qtyBtc, nextOpenTime(b), main.stopPrice, "STOP_LOSS"));
                        if (hedge != null) {
                            out.add(buildRow(nextPairId, Role.HEDGE, hedge.dir, hedge.entryTime, hedge.entryPrice, hedge.slPrice,
                                    hedge.qtyBtc, nextOpenTime(b), main.stopPrice, "HEDGE_TP_AT_MAIN_SL"));
                        }
                        main = null; hedge = null; mainClosedThisBar = true;
                    }
                }

                // 2.2 HEDGE TP?
                if (!mainClosedThisBar && hedge != null) {
                    boolean hedgeTp = (hedge.dir == Dir.LONG)
                            ? (b.high().compareTo(hedge.tpPrice) >= 0)
                            : (b.low().compareTo(hedge.tpPrice)  <= 0);
                    if (hedgeTp) {
                        out.add(buildRow(nextPairId, Role.HEDGE, hedge.dir, hedge.entryTime, hedge.entryPrice, hedge.slPrice,
                                hedge.qtyBtc, nextOpenTime(b), hedge.tpPrice, "HEDGE_TP_AT_MAIN_SL"));
                        hedge = null;
                    }
                }

                // 2.3 HEDGE SL?
                if (!mainClosedThisBar && hedge != null) {
                    boolean hedgeSl = (hedge.dir == Dir.LONG)
                            ? (b.low().compareTo(hedge.slPrice) <= 0)
                            : (b.high().compareTo(hedge.slPrice) >= 0);
                    if (hedgeSl) {
                        out.add(buildRow(nextPairId, Role.HEDGE, hedge.dir, hedge.entryTime, hedge.entryPrice, hedge.slPrice,
                                hedge.qtyBtc, nextOpenTime(b), hedge.slPrice, "HEDGE_SL_1R"));
                        hedge = null;
                    }
                }

                // 2.4 REVERSAL?
                if (main != null) {
                    Dir revDir = detectPairDirectionOnBar(bars, i, prev);
                    if (revDir != null && revDir != main.dir) {
                        Instant xt = nextOpenTime(b);
                        BigDecimal xp = scale2(b.close());
                        out.add(buildRow(nextPairId, Role.MAIN, main.dir, main.entryTime, main.entryPrice, main.stopPrice,
                                main.qtyBtc, xt, xp, "REVERSAL_CLOSE"));
                        if (hedge != null) {
                            out.add(buildRow(nextPairId, Role.HEDGE, hedge.dir, hedge.entryTime, hedge.entryPrice, hedge.slPrice,
                                    hedge.qtyBtc, xt, xp, "PAIR_CLOSE_WITH_MAIN"));
                        }
                        main = null; hedge = null;

                        // Планируем новый вход на i+1 под revDir c НОВОЙ логикой стопа
                        int entryIndex = i + 1;
                        if (entryIndex < bars.size()) {
                            BigDecimal stop = computeStopForEntry(bars, entryIndex, revDir);
                            if (stop != null) {
                                BigDecimal entryPrice = bars.get(entryIndex).open();
                                BigDecimal qty = calcQty(entryPrice, stop);
                                if (qty.compareTo(MIN_QTY) >= 0) {
                                    pending = new PendingPair();
                                    pending.entryIndex = entryIndex;
                                    pending.mainDir    = revDir;
                                    pending.entryPrice = entryPrice;
                                    pending.mainStop   = stop;
                                    pending.qtyBtc     = qty;
                                    pending.pairId     = nextPairId++;
                                }
                            }
                        }
                        continue; // к RSI не идём — пара уже закрыта
                    }
                }

                // 2.5 RSI только на 2h
                if (main != null && i >= 4 && is2hBoundary(b.openTime())) {
                    Bar prev2h = bars.get(i - 4);
                    BigDecimal rPrev = prev2h.rsi2h();
                    BigDecimal sPrev = prev2h.smaRsi2h();
                    BigDecimal r     = b.rsi2h();
                    BigDecimal s     = b.smaRsi2h();
                    if (rPrev != null && sPrev != null && r != null && s != null) {
                        boolean doExit = false; String reason = "RSI_CROSS";
                        if (main.dir == Dir.LONG) {
                            if (r.compareTo(new BigDecimal("75")) >= 0) main.armed75 = true;
                            boolean crossDown = rPrev.compareTo(sPrev) > 0 && r.compareTo(s) <= 0;
                            boolean armed75Ex = main.armed75 && r.compareTo(new BigDecimal("75")) < 0;
                            doExit = crossDown || armed75Ex;
                            reason = crossDown ? "RSI_CROSS" : "RSI_75_35";
                        } else {
                            if (r.compareTo(new BigDecimal("35")) <= 0) main.armed35 = true;
                            boolean crossUp   = rPrev.compareTo(sPrev) < 0 && r.compareTo(s) >= 0;
                            boolean armed35Ex = main.armed35 && r.compareTo(new BigDecimal("35")) > 0;
                            doExit = crossUp || armed35Ex;
                            reason = crossUp ? "RSI_CROSS" : "RSI_75_35";
                        }
                        if (doExit) {
                            Instant xt = nextOpenTime(b);
                            BigDecimal xp = scale2(b.close());
                            out.add(buildRow(nextPairId, Role.MAIN, main.dir, main.entryTime, main.entryPrice, main.stopPrice,
                                    main.qtyBtc, xt, xp, reason));
                            if (hedge != null) {
                                out.add(buildRow(nextPairId, Role.HEDGE, hedge.dir, hedge.entryTime, hedge.entryPrice, hedge.slPrice,
                                        hedge.qtyBtc, xt, xp, "PAIR_CLOSE_WITH_MAIN"));
                            }
                            main = null; hedge = null;
                        }
                    }
                }
            }

            // ===== 3) Детекция сигналов на баре i =====
            boolean s1Long=false, s1Short=false, s2=false;
            if (prev != null) {
                s1Long  = prev.ema11().compareTo(prev.ema30()) < 0 && b.ema11().compareTo(b.ema30()) >= 0;
                s1Short = prev.ema11().compareTo(prev.ema30()) > 0 && b.ema11().compareTo(b.ema30()) <= 0;
            }
            s2 = b.low().compareTo(b.ema110()) <= 0 && b.ema110().compareTo(b.high()) <= 0;

            // Обновление окон
            if (s1Long)  w1 = new WindowAfterSignal1(Dir.LONG,  i, i+2);
            if (s1Short) w1 = new WindowAfterSignal1(Dir.SHORT, i, i+2);
            if (s2)      w2 = new WindowAfterSignal2(i, i+5);

            // Если нет открытой пары и нет pending — ищем новую пару сигналов
            if (main == null && pending == null) {
                Dir entryDir = null;
                if (w1 != null && s2 && i <= w1.deadline) {
                    entryDir = w1.dir; // s1 -> s2
                } else if (w2 != null && i <= w2.deadline && (s1Long || s1Short)) {
                    entryDir = s1Long ? Dir.LONG : Dir.SHORT; // s2 -> s1
                }

                if (entryDir != null) {
                    int entryIndex = i + 1;
                    if (entryIndex < bars.size()) {
                        BigDecimal stop = computeStopForEntry(bars, entryIndex, entryDir);
                        if (stop != null) {
                            BigDecimal entryPrice = bars.get(entryIndex).open();
                            BigDecimal qty = calcQty(entryPrice, stop);
                            if (qty.compareTo(MIN_QTY) >= 0) {
                                pending = new PendingPair();
                                pending.entryIndex = entryIndex;
                                pending.mainDir    = entryDir;
                                pending.entryPrice = entryPrice;
                                pending.mainStop   = stop;  // новый расчёт
                                pending.qtyBtc     = qty;
                                pending.pairId     = nextPairId++;
                            }
                        }
                    }
                    // сбрасываем окна после постановки pending
                    w1 = null; w2 = null;
                }
            }

            // Истечение окон
            if (w1 != null && i > w1.deadline) w1 = null;
            if (w2 != null && i > w2.deadline) w2 = null;
        }

        return out;
    }

    // ==== helpers (общие) ====
    private static TradeRow buildRow(long pairId, Role role, Dir dir,
                                     Instant entryTime, BigDecimal entryPrice, BigDecimal stopPrice, BigDecimal qty,
                                     Instant exitTime, BigDecimal exitPrice, String reason) {
        return new TradeRow(
                pairId,
                role.name(),
                dir.name(),
                entryTime,
                scale2(entryPrice),
                scale2(stopPrice),
                qty.setScale(3, RoundingMode.HALF_UP),
                exitTime,
                scale2(exitPrice),
                reason
        );
    }

    private static BigDecimal calcQty(BigDecimal entry, BigDecimal stop) {
        BigDecimal delta = entry.subtract(stop).abs();
        if (delta.compareTo(new BigDecimal("0.01")) < 0) return BigDecimal.ZERO; // защита
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

    private static Instant nextOpenTime(Bar b) { return b.openTime().plus(TF); }

    private static boolean is2hBoundary(Instant ts) {
        ZonedDateTime z = ts.atZone(ZoneOffset.UTC);
        return z.getMinute() == 0 && (z.getHour() % 2 == 0);
    }

    /**
     * Определяем, сформировалась ли в баре i пара сигналов противоположного направления.
     * Возвращает Dir новой пары или null.
     */
    private static Dir detectPairDirectionOnBar(List<Bar> bars, int i, Bar prev) {
        if (i <= 0) return null;
        Bar b = bars.get(i);

        boolean s1Long  = prev.ema11().compareTo(prev.ema30()) < 0 && b.ema11().compareTo(b.ema30()) >= 0;
        boolean s1Short = prev.ema11().compareTo(prev.ema30()) > 0 && b.ema11().compareTo(b.ema30()) <= 0;
        boolean s2      = b.low().compareTo(b.ema110()) <= 0 && b.ema110().compareTo(b.high()) <= 0;

        if (s1Long && s2)  return Dir.LONG;
        if (s1Short && s2) return Dir.SHORT;
        return null;
    }

    // ===== НОВОЕ: логика стопа как в FirstStrategy =====

    /** Расчёт стопа по окну [entryIndex-5 .. entryIndex] с импульсной логикой и fallback на TEMA9 окна. */
    private BigDecimal computeStopForEntry(List<Bar> bars, int entryIndex, Dir dir) {
        int from = Math.max(0, entryIndex - 5);
        int to   = entryIndex;

        Integer impIdx = findStrongestImpulseInWindow(bars, from, to);
        if (impIdx == null) {
            return stopByTema9Window(bars, entryIndex, dir);
        }

        Bar imp = bars.get(impIdx);
        BigDecimal open  = imp.open();
        BigDecimal close = imp.close();
        BigDecimal bodyHigh = open.max(close);
        BigDecimal bodyLow  = open.min(close);
        BigDecimal body = bodyHigh.subtract(bodyLow);
        if (body.signum() <= 0) {
            return stopByTema9Window(bars, entryIndex, dir);
        }

        BigDecimal half = body.divide(new BigDecimal("2"), 10, RoundingMode.HALF_UP);
        BigDecimal mid  = bodyLow.add(half);

        Range zone1 = (dir == Dir.LONG) ? new Range(bodyLow, mid) : new Range(mid, bodyHigh);
        Range zone2 = (dir == Dir.LONG) ? new Range(bodyLow.subtract(half), bodyLow)
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

    /** LONG — min(TEMA9), SHORT — max(TEMA9) в окне [entryIndex-5 .. entryIndex]. */
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
        boolean containsInclusive(BigDecimal x) { return x != null && x.compareTo(lo) >= 0 && x.compareTo(hi) <= 0; }
    }

    private static void addIfInZone(List<BigDecimal> dst, BigDecimal level, Range zone) {
        if (level != null && zone.containsInclusive(level)) dst.add(level);
    }

    private static BigDecimal avg(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) return null;
        return a.add(b).divide(new BigDecimal("2"), 10, RoundingMode.HALF_UP);
    }

    /** Самая импульсная свеча: |close-open|/open; при равенстве — более свежая. */
    private Integer findStrongestImpulseInWindow(List<Bar> bars, int from, int to) {
        Integer bestIdx = null; BigDecimal bestScore = null;
        for (int i = Math.max(0, from); i <= Math.min(to, bars.size()-1); i++) {
            Bar b = bars.get(i);
            boolean imp = false;
            try { imp = b.isImpulse(); } catch (Throwable ignore) {}
            if (!imp) continue;

            BigDecimal open = b.open(), close = b.close();
            if (open == null || open.signum() == 0 || close == null) continue;

            BigDecimal score = close.subtract(open).abs().divide(open.abs(), 10, RoundingMode.HALF_UP);
            if (bestIdx == null || score.compareTo(bestScore) > 0
                    || (score.compareTo(bestScore) == 0 && i > bestIdx)) {
                bestIdx = i; bestScore = score;
            }
        }
        return bestIdx;
    }

    /** Индексы кросса EMA11/EMA30 в окне [from..to]. */
    private List<Integer> findCrossIdxInWindow(List<Bar> bars, int from, int to) {
        List<Integer> res = new ArrayList<>();
        int lo = Math.max(1, from);
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

    /** Эквивалентность кандидатов: |a-b| / ((a+b)/2) <= 0.0003 (0.03%). */
    private boolean areEquivalent(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) return false;
        BigDecimal diff = a.subtract(b).abs();
        BigDecimal mean = a.add(b).divide(new BigDecimal("2"), 10, RoundingMode.HALF_UP);
        if (mean.signum() == 0) return diff.signum() == 0;
        BigDecimal rel = diff.divide(mean, 10, RoundingMode.HALF_UP);
        return rel.compareTo(EQUIV_REL) <= 0;
    }
}
