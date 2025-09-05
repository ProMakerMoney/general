package com.pinbot.botprime.strategy;

import com.pinbot.botprime.backtest.IndicatorDao.Bar;
import com.pinbot.botprime.trade.BacktestTrade;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;

@Component
public class FirstStrategy implements Strategy {

    // Риск/комиссия/шаги
    private static final BigDecimal RISK_USDT = new BigDecimal("100");
    private static final BigDecimal FEE_RATE  = new BigDecimal("0.0011"); // 0.055% * 2
    private static final BigDecimal MIN_QTY   = new BigDecimal("0.001");
    private static final BigDecimal STEP_QTY  = new BigDecimal("0.001");
    private static final Duration   TF        = Duration.ofMinutes(30);

    enum Dir { LONG, SHORT }

    /** Отложенный вход, который активируется на баре entryIndex по OPEN */
    private static class PendingEntry {
        int entryIndex;
        Dir dir;
        BigDecimal stopPrice;
        BigDecimal entryPrice;
        BigDecimal qtyBtc;
    }

    /** Текущая позиция */
    private static class Position {
        Dir dir;
        BigDecimal entryPrice;
        BigDecimal stopPrice;
        BigDecimal qtyBtc;
        Instant entryTime;
        boolean armed75 = false; // для long: rsi >= 75 был замечен
        boolean armed35 = false; // для short: rsi <= 35 был замечен
    }

    /** Окно «после signal_1» (направленное), ждём signal_2 в этом и 2 следующих барах */
    private static class WindowAfterSignal1 {
        Dir dir;
        int startedAt;
        int deadline; // = startedAt + 2
    }

    /** Окно «после signal_2» (ненаправленное), ждём соответствующий signal_1 в этом и 5 следующих барах */
    private static class WindowAfterSignal2 {
        int startedAt;
        int deadline; // = startedAt + 5
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

            // 1) Активировать отложенный вход на этом баре (по OPEN)
            if (pending != null && pending.entryIndex == i) {
                pos = new Position();
                pos.dir        = pending.dir;
                pos.entryPrice = pending.entryPrice;
                pos.stopPrice  = pending.stopPrice;
                pos.qtyBtc     = pending.qtyBtc;
                pos.entryTime  = b.openTime();
                pending = null;
            }

            // 2) Если позиция открыта — сначала SL (интрабар), затем выходы по RSI (на CLOSE)
            boolean positionClosedThisBar = false;
            if (pos != null) {
                // 2.1) SL: если задело, выходим по цене stop; exit_time = open_time следующего бара
                if (pos.dir == Dir.LONG) {
                    if (b.low().compareTo(pos.stopPrice) <= 0) {
                        BacktestTrade t = toTrade(pos, pos.entryTime, pos.entryPrice, pos.stopPrice,
                                nextOpenTime(b, TF), b, true);
                        trades.add(t);
                        pos = null;
                        positionClosedThisBar = true;
                    }
                } else { // SHORT
                    if (b.high().compareTo(pos.stopPrice) >= 0) {
                        BacktestTrade t = toTrade(pos, pos.entryTime, pos.entryPrice, pos.stopPrice,
                                nextOpenTime(b, TF), b, true);
                        trades.add(t);
                        pos = null;
                        positionClosedThisBar = true;
                    }
                }

                // 2.2) Выходы по RSI — ТОЛЬКО на 2h-баре (сравниваем i-4 и i)
                if (!positionClosedThisBar && i >= 4 && is2hBoundary(b.openTime())) {
                    Bar prev2h = bars.get(i - 4);    // предыдущее 2h закрытие
                    BigDecimal rsiPrev = prev2h.rsi2h();
                    BigDecimal smaPrev = prev2h.smaRsi2h();
                    BigDecimal rsi     = b.rsi2h();
                    BigDecimal sma     = b.smaRsi2h();

                    if (rsiPrev != null && smaPrev != null && rsi != null && sma != null) {
                        if (pos.dir == Dir.LONG) {
                            // армирование 75 учитываем на 2h-баре
                            if (!pos.armed75 && rsi.compareTo(BigDecimal.valueOf(75)) >= 0) pos.armed75 = true;

                            boolean crossDown   = rsiPrev.compareTo(smaPrev) > 0 && rsi.compareTo(sma) <= 0;
                            boolean armed75Exit = pos.armed75 && rsi.compareTo(BigDecimal.valueOf(75)) < 0;

                            if (crossDown || armed75Exit) {
                                BacktestTrade t = toTrade(pos, pos.entryTime, pos.entryPrice, pos.stopPrice,
                                        nextOpenTime(b, TF), b.close());
                                trades.add(t);
                                pos = null;
                                positionClosedThisBar = true;
                            }
                        } else { // SHORT
                            if (!pos.armed35 && rsi.compareTo(BigDecimal.valueOf(35)) <= 0) pos.armed35 = true;

                            boolean crossUp      = rsiPrev.compareTo(smaPrev) < 0 && rsi.compareTo(sma) >= 0;
                            boolean armed35Exit  = pos.armed35 && rsi.compareTo(BigDecimal.valueOf(35)) > 0;

                            if (crossUp || armed35Exit) {
                                BacktestTrade t = toTrade(pos, pos.entryTime, pos.entryPrice, pos.stopPrice,
                                        nextOpenTime(b, TF), b.close());
                                trades.add(t);
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
                s1Long  = prev.ema11().compareTo(prev.ema30()) < 0 && b.ema11().compareTo(b.ema30()) >= 0;
                s1Short = prev.ema11().compareTo(prev.ema30()) > 0 && b.ema11().compareTo(b.ema30()) <= 0;
            }
            s2 = b.low().compareTo(b.ema110()) <= 0 && b.ema110().compareTo(b.high()) <= 0;

            // 4) Управление окнами
            if (s1Long) {
                w1 = new WindowAfterSignal1();
                w1.dir = Dir.LONG;
                w1.startedAt = i;
                w1.deadline = i + 2;   // текущий + 2
            } else if (s1Short) {
                w1 = new WindowAfterSignal1();
                w1.dir = Dir.SHORT;
                w1.startedAt = i;
                w1.deadline = i + 2;
            }
            if (s2) {
                w2 = new WindowAfterSignal2();
                w2.startedAt = i;
                w2.deadline = i + 5;   // текущий + 5
            }

            // 5) Проверка выполнения пар сигналов
            Dir entryDir = null;
            // A) сначала signal_1(dir), потом в окне signal_2
            if (w1 != null && s2 && i <= w1.deadline) {
                entryDir = w1.dir;
            }
            // B) сначала signal_2, потом в окне соответствующий signal_1(dir)
            if (w2 != null && i <= w2.deadline && (s1Long || s1Short)) {
                Dir dirFromS1 = s1Long ? Dir.LONG : Dir.SHORT;
                entryDir = dirFromS1; // если совпали A и B — берём направление из свежего signal_1
            }

            // 6) Сформирована пара → планируем вход на i+1 (и переворот при необходимости)
            if (entryDir != null) {
                int entryIndex = i + 1;
                if (entryIndex < bars.size()) {
                    Bar entryBar = bars.get(entryIndex);

                    // Стоп по TEMA9: окно из 6 значений [entryIndex-5 .. entryIndex]
                    int fromIdx = entryIndex - 5;
                    if (fromIdx >= 0) {
                        BigDecimal stop = null;
                        for (int j = fromIdx; j <= entryIndex; j++) {
                            BigDecimal t = bars.get(j).tema9();
                            if (t == null) { stop = null; break; }
                            if (entryDir == Dir.LONG) {
                                stop = (stop == null || t.compareTo(stop) < 0) ? t : stop; // минимум
                            } else {
                                stop = (stop == null || t.compareTo(stop) > 0) ? t : stop; // максимум
                            }
                        }
                        if (stop != null) {
                            BigDecimal entryPrice = entryBar.open();
                            BigDecimal qty = calcQty(entryPrice, stop);
                            if (qty.compareTo(MIN_QTY) >= 0) {
                                // Переворот: закрыть текущую на CLOSE(i), если ещё не закрыта
                                if (pos != null && !positionClosedThisBar) {
                                    BacktestTrade t = toTrade(pos, pos.entryTime, pos.entryPrice, pos.stopPrice,
                                            nextOpenTime(b, TF), b.close());
                                    trades.add(t);
                                    pos = null;
                                }
                                // Запланировать вход
                                pending = new PendingEntry();
                                pending.entryIndex = entryIndex;
                                pending.dir        = entryDir;
                                pending.stopPrice  = stop;
                                pending.entryPrice = entryPrice;
                                pending.qtyBtc     = qty;
                            }
                        }
                    }
                }
                // Сброс окон
                w1 = null;
                w2 = null;
            }

            // 7) Инвалидация окон
            if (w1 != null && i > w1.deadline) w1 = null;
            if (w2 != null && i > w2.deadline) w2 = null;
        }

        return trades;
    }

    // ===== Helpers =====

    private static BacktestTrade toTrade(
            Position pos,
            Instant entryTime,
            BigDecimal entryPrice,
            BigDecimal stopPrice,
            Instant exitTime,
            Bar barForExit,
            boolean isStop
    ) {
        BigDecimal exitPrice = isStop ? stopPrice : barForExit.close();
        return buildTrade(pos, entryTime, entryPrice, stopPrice, exitTime, exitPrice);
    }

    private static BacktestTrade toTrade(
            Position pos,
            Instant entryTime,
            BigDecimal entryPrice,
            BigDecimal stopPrice,
            Instant exitTime,
            BigDecimal exitPrice
    ) {
        return buildTrade(pos, entryTime, entryPrice, stopPrice, exitTime, exitPrice);
    }

    private static BacktestTrade buildTrade(
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

    // helper: бар является закрытием 2h (UTC: 00,02,04,...)
    private static boolean is2hBoundary(Instant ts) {
        ZonedDateTime z = ts.atZone(ZoneOffset.UTC);
        return z.getMinute() == 0 && (z.getHour() % 2 == 0);
    }

    private static BigDecimal scale2(BigDecimal v) { return v.setScale(2, RoundingMode.HALF_UP); }
    private static BigDecimal scale3(BigDecimal v) { return v.setScale(3, RoundingMode.HALF_UP); }
}
