package com.pinbot.botprime.strategy;

import com.pinbot.botprime.backtest.IndicatorDao.Bar;
import com.pinbot.botprime.trade.BacktestTrade;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
public class FirstStrategy implements Strategy {

    // Комиссия/шаги/ТФ
    private static final BigDecimal FEE_RATE  = new BigDecimal("0.0011"); // 0.055% * 2 (раунд-трип)
    private static final BigDecimal MIN_QTY   = new BigDecimal("0.001");
    private static final BigDecimal STEP_QTY  = new BigDecimal("0.001");
    private static final Duration   TF        = Duration.ofMinutes(30);

    // Порог «эквивалентности» уровней (0.03%)
    private static final BigDecimal EQUIV_REL = new BigDecimal("0.0003"); // 0.03% как доля

    // Day-1 депозит и риск на сделку
    private static final BigDecimal INITIAL_DEPOSIT = new BigDecimal("20000.00");
    private static final BigDecimal RISK_PCT        = new BigDecimal("0.02"); // 2%

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

        // ===== динамический депозит =====
        BigDecimal deposit = INITIAL_DEPOSIT;

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
                // 2.1) SL
                if (pos.dir == Dir.LONG) {
                    if (b.low().compareTo(pos.stopPrice) <= 0) {
                        BigDecimal exitPrice = scale2(pos.stopPrice);
                        trades.add(toTrade(pos, pos.entryTime, pos.entryPrice, pos.stopPrice,
                                nextOpenTime(b, TF), b, true));
                        // обновляем депозит (net, с комиссиями по модели sizing)
                        deposit = deposit.add(pnlNet(pos.dir, pos.entryPrice, exitPrice, pos.qtyBtc));
                        pos = null;
                        positionClosedThisBar = true;
                    }
                } else { // SHORT
                    if (b.high().compareTo(pos.stopPrice) >= 0) {
                        BigDecimal exitPrice = scale2(pos.stopPrice);
                        trades.add(toTrade(pos, pos.entryTime, pos.entryPrice, pos.stopPrice,
                                nextOpenTime(b, TF), b, true));
                        deposit = deposit.add(pnlNet(pos.dir, pos.entryPrice, exitPrice, pos.qtyBtc));
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
                            if (!pos.armed75 && rsi.compareTo(BigDecimal.valueOf(75)) >= 0) pos.armed75 = true;

                            boolean crossDown   = rsiPrev.compareTo(smaPrev) > 0 && rsi.compareTo(sma) <= 0;
                            boolean armed75Exit = pos.armed75 && rsi.compareTo(BigDecimal.valueOf(75)) < 0;

                            if (crossDown || armed75Exit) {
                                BigDecimal exitPrice = scale2(b.close());
                                trades.add(toTrade(pos, pos.entryTime, pos.entryPrice, pos.stopPrice,
                                        nextOpenTime(b, TF), b.close()));
                                deposit = deposit.add(pnlNet(pos.dir, pos.entryPrice, exitPrice, pos.qtyBtc));
                                pos = null;
                                positionClosedThisBar = true;
                            }
                        } else { // SHORT
                            if (!pos.armed35 && rsi.compareTo(BigDecimal.valueOf(35)) <= 0) pos.armed35 = true;

                            boolean crossUp      = rsiPrev.compareTo(smaPrev) < 0 && rsi.compareTo(sma) >= 0;
                            boolean armed35Exit  = pos.armed35 && rsi.compareTo(BigDecimal.valueOf(35)) > 0;

                            if (crossUp || armed35Exit) {
                                BigDecimal exitPrice = scale2(b.close());
                                trades.add(toTrade(pos, pos.entryTime, pos.entryPrice, pos.stopPrice,
                                        nextOpenTime(b, TF), b.close()));
                                deposit = deposit.add(pnlNet(pos.dir, pos.entryPrice, exitPrice, pos.qtyBtc));
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

            // 6) Сформирована пара → планируем вход на i+1
            if (entryDir != null) {
                int entryIndex = i + 1;
                if (entryIndex < bars.size()) {
                    Bar entryBar = bars.get(entryIndex);

                    // === стоп по вашим правилам (импульс + fallback) ===
                    BigDecimal stop = computeStopForEntry(bars, entryIndex, entryDir);

                    if (stop != null) {
                        BigDecimal entryPrice = entryBar.open();

                        // === динамический риск от текущего депозита ===
                        if (deposit.compareTo(BigDecimal.ZERO) > 0) {
                            BigDecimal riskUsdt = deposit.multiply(RISK_PCT); // 2% от текущего депо
                            BigDecimal qty = calcQty(entryPrice, stop, riskUsdt);

                            if (qty.compareTo(MIN_QTY) >= 0) {
                                // Переворот: закрыть текущую на CLOSE(i), если ещё не закрыта
                                if (pos != null && !positionClosedThisBar) {
                                    BigDecimal exitPrice = scale2(b.close());
                                    trades.add(toTrade(pos, pos.entryTime, pos.entryPrice, pos.stopPrice,
                                            nextOpenTime(b, TF), b.close()));
                                    deposit = deposit.add(pnlNet(pos.dir, pos.entryPrice, exitPrice, pos.qtyBtc));
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

    // ===== Helpers: расчёт стопа по новым правилам =====

    private BigDecimal computeStopForEntry(List<Bar> bars, int entryIndex, Dir dir) {
        // Окно импульсов: [entryIndex-5 .. entryIndex]
        int from = Math.max(0, entryIndex - 5);
        int to   = entryIndex;

        Integer impIdx = findStrongestImpulseInWindow(bars, from, to);
        if (impIdx == null) {
            // Fallback: общее правило — LONG: min(TEMA9), SHORT: max(TEMA9) в окне 6 баров
            return stopByTema9Window(bars, entryIndex, dir);
        }

        Bar imp = bars.get(impIdx);

        BigDecimal open  = imp.open();
        BigDecimal close = imp.close();
        // тело (без фитилей)
        BigDecimal bodyHigh = open.max(close);
        BigDecimal bodyLow  = open.min(close);
        BigDecimal body = bodyHigh.subtract(bodyLow); // |close-open|

        if (body.signum() <= 0) {
            // Дегенератная свеча → фолбэк на TEMA9-окно
            return stopByTema9Window(bars, entryIndex, dir);
        }

        BigDecimal half = body.divide(new BigDecimal("2"), 10, RoundingMode.HALF_UP);
        BigDecimal mid  = bodyLow.add(half);

        // Зона 1: половина тела по направлению
        Range zone1 = (dir == Dir.LONG)
                ? new Range(bodyLow, mid)     // нижняя половина тела
                : new Range(mid, bodyHigh);   // верхняя половина тела

        // Зона 2: расширение на полтела за пределы тела
        Range zone2 = (dir == Dir.LONG)
                ? new Range(bodyLow.subtract(half), bodyLow)   // вниз
                : new Range(bodyHigh, bodyHigh.add(half));     // вверх

        // Кандидаты: EMA110/EMA200 (на баре импульса) + точки кросса EMA11/EMA30 в окне [from..to]
        List<BigDecimal> candidates = new ArrayList<>();

        addIfInZone(candidates, imp.ema110(), zone1);
        addIfInZone(candidates, imp.ema200(), zone1);
        for (Integer crossIdx : findCrossIdxInWindow(bars, from, to)) {
            BigDecimal lvl = avg(bars.get(crossIdx).ema11(), bars.get(crossIdx).ema30()); // уровень кросса
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
            // Всё ещё ничего — фолбэк на TEMA9-окно
            return stopByTema9Window(bars, entryIndex, dir);
        }

        // Общее правило 0.03%: если весь диапазон кандидатов «очень близок», LONG->min, SHORT->max
        candidates.sort(Comparator.naturalOrder());
        BigDecimal min = candidates.get(0);
        BigDecimal max = candidates.get(candidates.size() - 1);
        if (areEquivalent(min, max)) {
            return (dir == Dir.LONG) ? min : max;
        }

        // Обычное правило выбора
        return (dir == Dir.LONG) ? min : max;
    }

    /** Общее правило: LONG — минимум TEMA9, SHORT — максимум TEMA9 в окне [entryIndex-5 .. entryIndex]. */
    private BigDecimal stopByTema9Window(List<Bar> bars, int entryIndex, Dir dir) {
        int from = Math.max(0, entryIndex - 5);
        int to   = entryIndex;

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
        return best; // может быть null, если в окне все TEMA9 = null
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

    /** Находим «самую импульсную» свечу в окне [from..to] по |close-open|/open; при равенстве берём самую свежую. */
    private Integer findStrongestImpulseInWindow(List<Bar> bars, int from, int to) {
        Integer bestIdx = null;
        BigDecimal bestScore = null;

        for (int i = Math.max(0, from); i <= Math.min(to, bars.size()-1); i++) {
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
                    .divide(open.abs(), 10, RoundingMode.HALF_UP); // доля тела
            if (bestIdx == null || score.compareTo(bestScore) > 0
                    || (score.compareTo(bestScore) == 0 && i > bestIdx)) { // tie -> более свежая
                bestIdx = i;
                bestScore = score;
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

    /** Эквивалентность значений по относительной разнице к среднему: |a-b| / ((a+b)/2) <= 0.0003 (0.03%) */
    private boolean areEquivalent(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) return false;
        BigDecimal diff = a.subtract(b).abs();
        BigDecimal mean = a.add(b).divide(new BigDecimal("2"), 10, RoundingMode.HALF_UP);
        if (mean.signum() == 0) return diff.signum() == 0;
        BigDecimal rel = diff.divide(mean, 10, RoundingMode.HALF_UP);
        return rel.compareTo(EQUIV_REL) <= 0;
    }

    // ===== Остальные хелперы =====

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

    /** sizing с учётом заданного риска в USD */
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

    /** net-PnL по той же модели комиссий, что в calcQty (комиссия считается как entry*qty*FEE_RATE) */
    private static BigDecimal pnlNet(Dir dir, BigDecimal entry, BigDecimal exit, BigDecimal qty) {
        BigDecimal signGross = (dir == Dir.LONG)
                ? exit.subtract(entry)
                : entry.subtract(exit);
        BigDecimal gross = signGross.multiply(qty);
        BigDecimal fees  = entry.multiply(qty).multiply(FEE_RATE); // раунд-трип как в sizing
        return scale2(gross.subtract(fees));
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
