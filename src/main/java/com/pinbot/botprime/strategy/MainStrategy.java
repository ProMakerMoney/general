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

public class MainStrategy {

    private static final BigDecimal RISK_USDT = new BigDecimal("100");
    private static final BigDecimal FEE_RATE  = new BigDecimal("0.0011"); // 0.055% * 2 (как раньше)
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
    }

    private static class Position {
        Dir dir;
        BigDecimal entryPrice;
        BigDecimal stopPrice;     // текущий стоп (после TP1 переводится в безубыток)
        BigDecimal initialStop;   // исходный стоп для записи
        BigDecimal qtyFull;       // полный объём
        BigDecimal qtyHalf1;      // первая половина (для расчётов PnL)
        BigDecimal qtyHalf2;      // вторая половина
        Instant entryTime;
        int entryIndex;

        // TP1
        boolean tp1Done = false;
        BigDecimal tp1Price = null;

        // RSI «армирование» только на 2h
        boolean armed75 = false;
        boolean armed35 = false;
    }

    private static class WindowAfterSignal1 { Dir dir; int startedAt; int deadline; }
    private static class WindowAfterSignal2 { int startedAt; int deadline; }

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

            // Активируем отложенный вход (по OPEN i)
            if (pending != null && pending.entryIndex == i) {
                pos = new Position();
                pos.dir         = pending.dir;
                pos.entryPrice  = pending.entryPrice;
                pos.stopPrice   = pending.stopPrice;   // текущий стоп
                pos.initialStop = pending.stopPrice;   // для записи
                pos.qtyFull     = pending.qtyBtc;
                pos.entryTime   = b.openTime();
                pos.entryIndex  = i;

                // делим на половины, если возможно (иначе TP1 не будет)
                if (pos.qtyFull.compareTo(new BigDecimal("0.002")) >= 0) {
                    pos.qtyHalf1 = floorToStep(pos.qtyFull.divide(new BigDecimal("2"), 10, RoundingMode.DOWN), STEP_QTY);
                    pos.qtyHalf2 = pos.qtyFull.subtract(pos.qtyHalf1);
                } else {
                    pos.qtyHalf1 = BigDecimal.ZERO;  // TP1 не будет
                    pos.qtyHalf2 = pos.qtyFull;
                }
                pending = null;
            }

            boolean positionClosedThisBar = false;
            if (pos != null) {
                // 1) SL приоритетнее TP1 (вариант a)
                if (pos.dir == Dir.LONG) {
                    if (b.low().compareTo(pos.stopPrice) <= 0) {
                        // Если TP1 уже был и стоп переведён в безубыток, это ONLY_TP_1
                        String reason = pos.tp1Done ? "ONLY_TP_1" : "STOP_LOSS";
                        MainBacktestTrade t = buildTrade(pos, nextOpenTime(b), isStopExitPrice(pos, b, true), reason);
                        trades.add(t);
                        pos = null; positionClosedThisBar = true;
                    }
                } else { // SHORT
                    if (b.high().compareTo(pos.stopPrice) >= 0) {
                        String reason = pos.tp1Done ? "ONLY_TP_1" : "STOP_LOSS";
                        MainBacktestTrade t = buildTrade(pos, nextOpenTime(b), isStopExitPrice(pos, b, true), reason);
                        trades.add(t);
                        pos = null; positionClosedThisBar = true;
                    }
                }

                // 2) TP1 (если ещё не был и деление объёма возможно)
                if (!positionClosedThisBar && !pos.tp1Done && pos.qtyHalf1.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal R = pos.entryPrice.subtract(pos.initialStop).abs();
                    BigDecimal tp1 = (pos.dir == Dir.LONG)
                            ? pos.entryPrice.add(R.multiply(new BigDecimal("2")))
                            : pos.entryPrice.subtract(R.multiply(new BigDecimal("2")));
                    boolean touchedTp1 = (pos.dir == Dir.LONG)
                            ? b.high().compareTo(tp1) >= 0
                            : b.low().compareTo(tp1)  <= 0;
                    if (touchedTp1) {
                        pos.tp1Done = true;
                        pos.tp1Price = scale2(tp1);
                        // переводим стоп в безубыток на остаток
                        pos.stopPrice = pos.entryPrice;
                    }
                }

                // 3) RSI-выходы только на 2h-закрытии (сравнение i-4 и i)
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
                                MainBacktestTrade t = buildTrade(pos, nextOpenTime(b), b.close(), crossDown ? "RSI_CROSS" : "RSI_75_35");
                                trades.add(t);
                                pos = null; positionClosedThisBar = true;
                            }
                        } else { // SHORT
                            if (!pos.armed35 && r.compareTo(new BigDecimal("35")) <= 0) pos.armed35 = true;
                            boolean crossUp = rPrev.compareTo(sPrev) < 0 && r.compareTo(s) >= 0;
                            boolean armed35Ex = pos.armed35 && r.compareTo(new BigDecimal("35")) > 0;
                            if (crossUp || armed35Ex) {
                                MainBacktestTrade t = buildTrade(pos, nextOpenTime(b), b.close(), crossUp ? "RSI_CROSS" : "RSI_75_35");
                                trades.add(t);
                                pos = null; positionClosedThisBar = true;
                            }
                        }
                    }
                }
            }

            // 4) Сигналы на баре i
            boolean s1Long=false, s1Short=false, s2=false;
            if (prev != null) {
                s1Long  = prev.ema11().compareTo(prev.ema30()) < 0 && b.ema11().compareTo(b.ema30()) >= 0;
                s1Short = prev.ema11().compareTo(prev.ema30()) > 0 && b.ema11().compareTo(b.ema30()) <= 0;
            }
            s2 = b.low().compareTo(b.ema110()) <= 0 && b.ema110().compareTo(b.high()) <= 0;

            // 5) Управление окнами
            if (s1Long) { w1 = new WindowAfterSignal1(); w1.dir=Dir.LONG;  w1.startedAt=i; w1.deadline=i+2; }
            else if (s1Short) { w1 = new WindowAfterSignal1(); w1.dir=Dir.SHORT; w1.startedAt=i; w1.deadline=i+2; }
            if (s2) { w2 = new WindowAfterSignal2(); w2.startedAt=i; w2.deadline=i+5; }

            // 6) Формирование пары
            Dir entryDir = null;
            if (w1 != null && s2 && i <= w1.deadline) entryDir = w1.dir;                 // s1 -> s2
            if (w2 != null && i <= w2.deadline && (s1Long || s1Short)) entryDir = s1Long?Dir.LONG:Dir.SHORT; // s2 -> s1

            // 7) Переворот: если пара противоположна уже открытой позиции
            if (pos != null && entryDir != null && pos.dir != entryDir) {
                // закрываем текущую по CLOSE этого бара (exit_time = next open)
                MainBacktestTrade t = buildTrade(pos, nextOpenTime(b), b.close(), "REVERSAL_CLOSE");
                trades.add(t);
                pos = null; positionClosedThisBar = true;
                // и НИЖЕ запланируем новый вход как обычно
            }

            // 8) Планируем вход на i+1
            if (entryDir != null) {
                int entryIndex = i + 1;
                if (entryIndex < bars.size()) {
                    Bar entryBar = bars.get(entryIndex);
                    int fromIdx = entryIndex - 5;
                    if (fromIdx >= 0) {
                        BigDecimal stop = null;
                        for (int j = fromIdx; j <= entryIndex; j++) {
                            BigDecimal t9 = bars.get(j).tema9();
                            if (t9 == null) { stop = null; break; }
                            if (entryDir == Dir.LONG)
                                stop = (stop==null || t9.compareTo(stop)<0) ? t9 : stop; // min
                            else
                                stop = (stop==null || t9.compareTo(stop)>0) ? t9 : stop; // max
                        }
                        if (stop != null) {
                            BigDecimal entryPrice = entryBar.open();
                            BigDecimal qty = calcQty(entryPrice, stop);
                            if (qty.compareTo(MIN_QTY) >= 0) {
                                pending = new PendingEntry();
                                pending.entryIndex = entryIndex;
                                pending.dir = entryDir;
                                pending.stopPrice = stop;
                                pending.entryPrice = entryPrice;
                                pending.qtyBtc = qty;
                            }
                        }
                    }
                }
                w1 = null; w2 = null;
            }

            // 9) Инвалидация окон
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

    private static MainBacktestTrade buildTrade(Position pos, Instant exitTime, BigDecimal exitPrice, String reason) {
        MainBacktestTrade t = new MainBacktestTrade();
        t.setEntryTime(pos.entryTime);
        t.setSide(pos.dir == Dir.LONG ? "LONG" : "SHORT");
        t.setEntryPrice(scale2(pos.entryPrice));
        t.setStopPrice(scale2(pos.initialStop));
        t.setQtyBtc(pos.qtyFull.setScale(3, RoundingMode.HALF_UP));
        t.setTp1Price(pos.tp1Done ? pos.tp1Price : null);
        t.setExitTime(exitTime);
        t.setExitPrice(scale2(exitPrice));
        t.setTp2Price(scale2(exitPrice));
        t.setReason(reason);
        return t;
    }

    private static BigDecimal isStopExitPrice(Position pos, Bar b, boolean useStop) {
        // Возвращаем цену выхода: для SL = текущий stopPrice (вкл. безубыток), иначе Close бара
        return useStop ? scale2(pos.stopPrice) : scale2(b.close());
    }
}
