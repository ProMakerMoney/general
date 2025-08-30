package com.pinbot.botprime.strategy;

import com.pinbot.botprime.backtest.IndicatorDao.Bar;
import com.pinbot.botprime.trade.BacktestTrade;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class FirstStrategy implements Strategy {
    private static final BigDecimal RISK_USDT = new BigDecimal("100");
    private static final BigDecimal FEE_RATE = new BigDecimal("0.0011"); // 0.11% (вход+выход)
    private static final BigDecimal MIN_QTY = new BigDecimal("0.001");
    private static final BigDecimal STEP_QTY = new BigDecimal("0.001");
    private static final Duration TF = Duration.ofMinutes(30);

    enum Dir { LONG, SHORT }

    private static class PendingEntry {
        int entryIndex; // индекс бара, на котором будет вход по OPEN
        Dir dir;
        BigDecimal stopPrice; // фиксируется при планировании входа
        BigDecimal entryPrice; // open[entryIndex]
        BigDecimal qtyBtc;
    }

    private static class Position {
        Dir dir;
        BigDecimal entryPrice;
        BigDecimal stopPrice;
        BigDecimal qtyBtc;
        Instant entryTime;
        boolean armed75 = false; // для LONG
        boolean armed35 = false; // для SHORT
    }

    private static class WindowAfterSignal1 {
        Dir dir;
        int deadline; // i + 2
        int startedAt; // индекс бара signal_1
    }

    private static class WindowAfterSignal2 {
        int deadline; // i + 5
        int startedAt; // индекс бара signal_2
    }

    @Override
    public List<BacktestTrade> backtest(List<Bar> bars) {
        List<BacktestTrade> trades = new ArrayList<>();
        if (bars == null || bars.size() < 10) return trades;

        Position pos = null;
        PendingEntry pending = null;
        WindowAfterSignal1 w1 = null; // активное окно после signal_1 (направленное)
        WindowAfterSignal2 w2 = null; // активное окно после signal_2 (без направления)

        for (int i = 0; i < bars.size(); i++) {
            Bar b = bars.get(i);
            Bar prev = i > 0 ? bars.get(i - 1) : null;

            // 1) Активируем отложенный вход на этом баре (OPEN)
            if (pending != null && pending.entryIndex == i) {
                pos = new Position();
                pos.dir = pending.dir;
                pos.entryPrice = pending.entryPrice;
                pos.stopPrice = pending.stopPrice;
                pos.qtyBtc = pending.qtyBtc;
                pos.entryTime = b.openTime();
                // сброс pending
                pending = null;
            }

            // 2) Если позиция открыта — проверки выхода
            boolean positionClosedThisBar = false;
            if (pos != null) {
                // 2.1) Проверка SL (интрабар):
                if (pos.dir == Dir.LONG) {
                    if (b.low().compareTo(pos.stopPrice) <= 0) {
                        // SL сработал на этом баре по цене стопа
                        BacktestTrade t = toTrade(pos, pos.entryTime, pos.entryPrice, pos.stopPrice, b.openTime(), b, true);
                        trades.add(t);
                        pos = null;
                        positionClosedThisBar = true;
                    }
                } else { // SHORT
                    if (b.high().compareTo(pos.stopPrice) >= 0) {
                        BacktestTrade t = toTrade(pos, pos.entryTime, pos.entryPrice, pos.stopPrice, b.openTime(), b, true);
                        trades.add(t);
                        pos = null;
                        positionClosedThisBar = true;
                    }
                }

                // 2.2) Если SL не сработал, проверяем выходы по RSI на CLOSE этого бара
                if (!positionClosedThisBar && prev != null) {
                    BigDecimal rsiPrev = prev.rsi2h();
                    BigDecimal smaPrev = prev.smaRsi2h();
                    BigDecimal rsi = b.rsi2h();
                    BigDecimal sma = b.smaRsi2h();
                    if (rsiPrev != null && smaPrev != null && rsi != null && sma != null) {
                        if (pos.dir == Dir.LONG) {
                            // армирование 75
                            if (!pos.armed75 && rsi.compareTo(BigDecimal.valueOf(75)) >= 0) {
                                pos.armed75 = true;
                            }
                            boolean crossDown = rsiPrev.compareTo(smaPrev) > 0 && rsi.compareTo(sma) <= 0;
                            boolean armed75Exit = pos.armed75 && rsi.compareTo(BigDecimal.valueOf(75)) < 0;
                            if (crossDown || armed75Exit) {
                                // выходим по CLOSE этого бара
                                BacktestTrade t = toTrade(pos, pos.entryTime, pos.entryPrice, pos.stopPrice, nextOpenTime(b, TF), b.close());
                                trades.add(t);
                                pos = null;
                                positionClosedThisBar = true;
                            }
                        } else { // SHORT
                            if (!pos.armed35 && rsi.compareTo(BigDecimal.valueOf(35)) <= 0) {
                                pos.armed35 = true;
                            }
                            boolean crossUp = rsiPrev.compareTo(smaPrev) < 0 && rsi.compareTo(sma) >= 0;
                            boolean armed35Exit = pos.armed35 && rsi.compareTo(BigDecimal.valueOf(35)) > 0;
                            if (crossUp || armed35Exit) {
                                BacktestTrade t = toTrade(pos, pos.entryTime, pos.entryPrice, pos.stopPrice, nextOpenTime(b, TF), b.close());
                                trades.add(t);
                                pos = null;
                                positionClosedThisBar = true;
                            }
                        }
                    }
                }
            }

            // 3) Детект сигналов на баре i
            boolean s1Long = false;
            boolean s1Short = false;
            boolean s2 = false;
            if (prev != null) {
                s1Long = prev.ema11().compareTo(prev.ema30()) < 0 && b.ema11().compareTo(b.ema30()) >= 0;
                s1Short = prev.ema11().compareTo(prev.ema30()) > 0 && b.ema11().compareTo(b.ema30()) <= 0;
            }
            s2 = b.low().compareTo(b.ema110()) <= 0 && b.ema110().compareTo(b.high()) <= 0;

            // 4) Окна ожидания (создание/переключение/пролонгация)
            if (s1Long) {
                w1 = new WindowAfterSignal1();
                w1.dir = Dir.LONG;
                w1.startedAt = i;
                w1.deadline = i + 2; // включая текущий и 2 следующих
            } else if (s1Short) {
                w1 = new WindowAfterSignal1();
                w1.dir = Dir.SHORT;
                w1.startedAt = i;
                w1.deadline = i + 2;
            }
            if (s2) {
                w2 = new WindowAfterSignal2();
                w2.startedAt = i;
                w2.deadline = i + 5; // включая текущий и 5 следующих
            }

            // 5) Проверяем выполнение пар сигналов на текущем баре i
            Dir entryDir = null;

            // Кейс A: сначала был signal_1_(dir), теперь в окне пришёл signal_2
            if (w1 != null && s2 && i <= w1.deadline) {
                entryDir = w1.dir;
            }

            // Кейс B: сначала был signal_2, теперь в окне пришёл signal_1_(dir)
            if (w2 != null && i <= w2.deadline && (s1Long || s1Short)) {
                Dir dirFromS1 = s1Long ? Dir.LONG : Dir.SHORT;
                // Если одновременно сработали A и B, используем направление по свежему signal_1
                entryDir = dirFromS1;
            }

            // 6) Если сформировалась пара — планируем вход на i+1 и обрабатываем переворот
            if (entryDir != null) {
                // Рассчитать индекс и цену входа
                int entryIndex = i + 1;
                if (entryIndex < bars.size()) {
                    Bar entryBar = bars.get(entryIndex);
                    // Стоп берётся по теме 5 баров до момента входа → индекс stopIdx = entryIndex - 5
                    int stopIdx = entryIndex - 5;
                    if (stopIdx >= 0) {
                        BigDecimal stop = bars.get(stopIdx).tema9();
                        BigDecimal entryPrice = entryBar.open();
                        BigDecimal qty = calcQty(entryPrice, stop);
                        if (qty.compareTo(MIN_QTY) >= 0) {
                            // Переворот: если позиция открыта — закрыть её на CLOSE(i)
                            if (pos != null && !positionClosedThisBar) {
                                BacktestTrade t = toTrade(pos, pos.entryTime, pos.entryPrice, pos.stopPrice, nextOpenTime(b, TF), b.close());
                                trades.add(t);
                                pos = null;
                            }
                            // Запланировать вход
                            pending = new PendingEntry();
                            pending.entryIndex = entryIndex;
                            pending.dir = entryDir;
                            pending.stopPrice = stop;
                            pending.entryPrice = entryPrice;
                            pending.qtyBtc = qty;
                        }
                    }
                }
                // После формирования пары окна сбрасываем
                w1 = null;
                w2 = null;
            }

            // 7) Инвалидация окон по дедлайнам
            if (w1 != null && i > w1.deadline) w1 = null;
            if (w2 != null && i > w2.deadline) w2 = null;
        }

        return trades;
    }

    private static BacktestTrade toTrade(Position pos, Instant entryTime, BigDecimal entryPrice, BigDecimal stopPrice,
                                         Instant exitTime, Bar barForExit, boolean isStop) {
        BigDecimal exitPrice = isStop ? stopPrice : barForExit.close();
        return buildTrade(pos, entryTime, entryPrice, stopPrice, exitTime, exitPrice);
    }

    private static BacktestTrade toTrade(Position pos, Instant entryTime, BigDecimal entryPrice, BigDecimal stopPrice,
                                         Instant exitTime, BigDecimal exitPrice) {
        return buildTrade(pos, entryTime, entryPrice, stopPrice, exitTime, exitPrice);
    }

    private static BacktestTrade buildTrade(Position pos, Instant entryTime, BigDecimal entryPrice, BigDecimal stopPrice,
                                            Instant exitTime, BigDecimal exitPrice) {
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
        // В идеале это open_time следующего бара. Если его нет, добавим 30 минут.
        return b.openTime().plus(tf);
    }

    private static BigDecimal calcQty(BigDecimal entry, BigDecimal stop) {
        BigDecimal delta = entry.subtract(stop).abs();
        BigDecimal denom = delta.add(entry.multiply(FEE_RATE));
        if (denom.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
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
    private static BigDecimal scale3(BigDecimal v) { return v.setScale(3, RoundingMode.HALF_UP); }
}
