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
import java.util.List;

/**
 * MainStrategy (упрощённая):
 *  - Входы/окна как в первой стратегии.
 *  - TP1 = 2R (50% объёма), затем стоп остатка = entry (BE), TP2 = финальный выход.
 *  - RSI-выходы только на 2h (сравнение i-4 и i).
 *  - Стоп:
 *      * По умолчанию TEMA9 окно [entry-5..entry] (LONG=min, SHORT=max).
 *      * Если бар-пара импульсный (range_i >= 2 * avgRange5 на i-1..i-5):
 *          - якорь = CROSS на свече signal_1: crossPrice = (ema11[s1] + ema30[s1]) / 2
 *          - если EMA110 (на баре входа) или EMA200 (если есть) находятся «очень близко» к crossPrice,
 *            то для LONG берём МИНИМУМ из {crossPrice, близкие EMA}, для SHORT — МАКСИМУМ.
 *          - если crossPrice не на правильной стороне (для LONG ниже входа, для SHORT выше) — возвращаемся к TEMA9.
 *      * «очень близко» = |EMA - crossPrice| <= nearEps, где nearEps = min(0.30 * avgRange5, 0.003 * entryPrice).
 *
 *  В trade пишем: stop_source (TEMA9 | CROSS | EMA110 | EMA200) и impulse (true/false).
 */
public class MainStrategy {

    // Риск/комиссия/шаги – как оговаривали
    private static final BigDecimal RISK_USDT = new BigDecimal("100");
    private static final BigDecimal FEE_RATE  = new BigDecimal("0.0011"); // 0.055% * 2
    private static final BigDecimal MIN_QTY   = new BigDecimal("0.001");
    private static final BigDecimal STEP_QTY  = new BigDecimal("0.001");
    private static final Duration   TF        = Duration.ofMinutes(30);

    enum Dir { LONG, SHORT }

    private static class PendingEntry {
        int entryIndex;
        Dir dir;
        BigDecimal stopPrice;
        BigDecimal entryPrice;
        BigDecimal qtyBtc;
        String stopSource;   // TEMA9 | CROSS | EMA110 | EMA200
        boolean impulse;     // флаг импульса на баре пары
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

    /** Окно после signal_1: храним индекс бара s1 для crossPrice */
    private static class WindowAfterSignal1 {
        Dir dir; int startedAt; int deadline; int s1Index;
    }
    private static class WindowAfterSignal2 { int startedAt; int deadline; }

    /** Главный метод прогона */
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
            Integer s1IndexForPair = null;

            if (w1 != null && s2 && i <= w1.deadline) { // s1 -> s2
                entryDir = w1.dir; s1IndexForPair = w1.s1Index;
            }
            if (w2 != null && i <= w2.deadline && (s1Long || s1Short)) { // s2 -> s1
                entryDir = s1Long ? Dir.LONG : Dir.SHORT; s1IndexForPair = i;
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

                    // 7.1) Базовый стоп по TEMA9 (окно из 6 баров)
                    int fromIdx = entryIndex - 5;
                    if (fromIdx >= 0) {
                        BigDecimal stopTema = null;
                        for (int j = fromIdx; j <= entryIndex; j++) {
                            BigDecimal t9 = bars.get(j).tema9();
                            if (t9 == null) { stopTema = null; break; }
                            if (entryDir == Dir.LONG)
                                stopTema = (stopTema==null || t9.compareTo(stopTema)<0) ? t9 : stopTema; // min
                            else
                                stopTema = (stopTema==null || t9.compareTo(stopTema)>0) ? t9 : stopTema; // max
                        }
                        if (stopTema != null) {
                            // 7.2) Импульс на баре пары (i): range_i >= 2 * avgRange5 (i-1..i-5)
                            BigDecimal avgRange5 = avgRange5(bars, i);
                            boolean impulse = false;
                            if (avgRange5 != null) {
                                BigDecimal rangeI = bars.get(i).high().subtract(bars.get(i).low()).abs();
                                impulse = rangeI.compareTo(avgRange5.multiply(new BigDecimal("2"))) >= 0;
                            }

                            // 7.3) Выбор стопа
                            BigDecimal entryPrice = entryBar.open();
                            String stopSource = "TEMA9";
                            BigDecimal stopFinal = stopTema;

                            if (impulse) {
                                // crossPrice = (ema11 + ema30) / 2 на баре s1
                                BigDecimal crossPrice = null;
                                if (s1IndexForPair != null && s1IndexForPair >= 0 && s1IndexForPair < bars.size()) {
                                    BigDecimal e11 = bars.get(s1IndexForPair).ema11();
                                    BigDecimal e30 = bars.get(s1IndexForPair).ema30();
                                    if (e11 != null && e30 != null) {
                                        crossPrice = e11.add(e30).divide(new BigDecimal("2"), 10, RoundingMode.HALF_UP);
                                    }
                                }
                                // cross должен быть на правильной стороне
                                boolean crossOnSide = false;
                                if (crossPrice != null) {
                                    crossOnSide = (entryDir == Dir.LONG)
                                            ? crossPrice.compareTo(entryPrice) < 0
                                            : crossPrice.compareTo(entryPrice) > 0;
                                }

                                if (crossOnSide) {
                                    // радиус близости
                                    BigDecimal nearEps = null;
                                    if (avgRange5 != null) {
                                        BigDecimal byRange = avgRange5.multiply(new BigDecimal("0.30"));
                                        BigDecimal byPct   = entryPrice.multiply(new BigDecimal("0.003")); // 0.3%
                                        nearEps = byRange.min(byPct);
                                    }

                                    // кандидаты: CROSS + близкие EMA110/EMA200
                                    BigDecimal candCross = crossPrice;
                                    BigDecimal cand110 = entryBar.ema110();
                                    BigDecimal cand200 = safeEma200(entryBar); // через отражение, если есть метод

                                    // фильтр «близости» и правильной стороны
                                    List<BigDecimal> cands = new ArrayList<>();
                                    cands.add(candCross);
                                    if (nearEps != null) {
                                        if (cand110 != null && isOnSide(cand110, entryPrice, entryDir)
                                                && abs(cand110.subtract(candCross)).compareTo(nearEps) <= 0) {
                                            cands.add(cand110);
                                        }
                                        if (cand200 != null && isOnSide(cand200, entryPrice, entryDir)
                                                && abs(cand200.subtract(candCross)).compareTo(nearEps) <= 0) {
                                            cands.add(cand200);
                                        }
                                    }

                                    if (!cands.isEmpty()) {
                                        if (entryDir == Dir.LONG) {
                                            // берём МИНИМУМ из близких к cross
                                            BigDecimal chosen = cands.get(0);
                                            for (BigDecimal v : cands) if (v.compareTo(chosen) < 0) chosen = v;
                                            stopFinal = chosen;
                                        } else {
                                            // SHORT — МАКСИМУМ
                                            BigDecimal chosen = cands.get(0);
                                            for (BigDecimal v : cands) if (v.compareTo(chosen) > 0) chosen = v;
                                            stopFinal = chosen;
                                        }
                                        stopSource = resolveSource(stopFinal, candCross, cand110, cand200);
                                    } else {
                                        stopFinal = candCross;
                                        stopSource = "CROSS";
                                    }
                                } // иначе остаётся TEMA9
                            }

                            // 7.4) Расчёт объёма и постановка PendingEntry
                            if (stopFinal != null) {
                                BigDecimal qty = calcQty(entryPrice, stopFinal);
                                if (qty.compareTo(MIN_QTY) >= 0) {
                                    pending = new PendingEntry();
                                    pending.entryIndex = entryIndex;
                                    pending.dir        = entryDir;
                                    pending.stopPrice  = scale2(stopFinal);
                                    pending.entryPrice = scale2(entryPrice);
                                    pending.qtyBtc     = qty;
                                    pending.stopSource = stopSource;
                                    pending.impulse    = impulse;
                                }
                            }
                        }
                    }
                }
                // сбрасываем окна
                w1 = null; w2 = null;
            }

            // 8) Истечение окон
            if (w1 != null && i > w1.deadline) w1 = null;
            if (w2 != null && i > w2.deadline) w2 = null;
        }

        return trades;
    }

    // ===== Helpers =====

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
    private static BigDecimal abs(BigDecimal v) { return v.signum() >= 0 ? v : v.negate(); }

    private static boolean isOnSide(BigDecimal level, BigDecimal entry, Dir dir) {
        return (dir == Dir.LONG) ? level.compareTo(entry) < 0 : level.compareTo(entry) > 0;
    }

    /** Средний диапазон 5 предыдущих баров для индекса i; если недостаточно данных — null */
    private static BigDecimal avgRange5(List<Bar> bars, int i) {
        if (i < 5) return null;
        BigDecimal sum = BigDecimal.ZERO;
        for (int k = i - 5; k <= i - 1; k++) {
            BigDecimal range = bars.get(k).high().subtract(bars.get(k).low()).abs();
            sum = sum.add(range);
        }
        return sum.divide(new BigDecimal("5"), 10, RoundingMode.HALF_UP);
    }

    /** Попытка получить ema200() у Bar через reflection (если нет — вернём null) */
    private static BigDecimal safeEma200(Bar bar) {
        try {
            var m = bar.getClass().getMethod("ema200");
            Object v = m.invoke(bar);
            return (v instanceof BigDecimal) ? (BigDecimal) v : null;
        } catch (Exception ignore) {
            return null;
        }
    }

    /** Определяем, от какого источника выбран финальный стоп */
    private static String resolveSource(BigDecimal chosen, BigDecimal cross, BigDecimal ema110, BigDecimal ema200) {
        if (cross != null && chosen.compareTo(cross) == 0)   return "CROSS";
        if (ema110 != null && chosen.compareTo(ema110) == 0) return "EMA110";
        if (ema200 != null && chosen.compareTo(ema200) == 0) return "EMA200";
        return "TEMA9";
    }

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

