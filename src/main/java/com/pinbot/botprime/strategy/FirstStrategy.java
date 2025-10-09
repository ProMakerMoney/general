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

    /* ===================== ПАРАМЕТРЫ ===================== */

    // Комиссии (за одну сторону)
    private static final BigDecimal FEE_IN   = new BigDecimal("0.00055");   // вход (мейкер)
    private static final BigDecimal FEE_TP   = new BigDecimal("0.00055");   // тейк (мейкер)
    private static final BigDecimal FEE_STOP = new BigDecimal("0.00055");  // стоп (тейкер)

    // Депозит/риск
    private static final BigDecimal INITIAL_DEPOSIT   = new BigDecimal("20000.00");
    private static final BigDecimal RISK_PCT          = new BigDecimal("0.02");    // 2% на месяц
    private static final BigDecimal RISK_PCT_REDUCED  = RISK_PCT.divide(new BigDecimal("2"), 10, RoundingMode.HALF_UP);
    private static final BigDecimal MONTHLY_DD_LIMIT  = new BigDecimal("0.05");    // 5% просадки месяца → режем риск

    // Таймфрейм/шаги
    private static final Duration   TF         = Duration.ofMinutes(30);
    private static final BigDecimal MIN_QTY    = new BigDecimal("0.001");
    private static final BigDecimal STEP_QTY   = new BigDecimal("0.001");
    private static final BigDecimal PRICE_STEP = new BigDecimal("0.01");

    // Один тейк: TP_R * R
    private static final BigDecimal TP_R       = new BigDecimal("2.0");
    // BE+fees «вооружить» после 1R
    private static final BigDecimal BE_ARM_R   = new BigDecimal("1.0");

    // Фильтр по относительной дистанции стопа
    private static final BigDecimal STOP_PCT_LOW = new BigDecimal("0.006"); // 0.6% → пропуск
    private static final BigDecimal STOP_PCT_MID = new BigDecimal("0.010"); // 1.0% → 0.5x

    // Режимный фильтр: наклон ema30 (минимальный)
    private static final BigDecimal EMA30_SLOPE_MIN = new BigDecimal("0.0005"); // 0.05%/бар

    // RSI
    private static final BigDecimal RSI_HIGH = new BigDecimal("75");
    private static final BigDecimal RSI_LOW  = new BigDecimal("25");

    // Эквивалентность уровней (0.03%)
    private static final BigDecimal EQUIV_REL = new BigDecimal("0.0003");

    // Анти-серия
    private static final int  MAX_LOSS_STREAK = 5;   // после 5 стопов подряд
    private static final int  COOLDOWN_BARS   = 10;  // пропустить 10 баров

    private static final boolean DEBUG = false;

    /* Приоритет внутри бара (если коснулось и TP, и SL) */
    private enum BarPriority { SL_FIRST, TP_FIRST, WORST_CASE, BEST_CASE }
    private static final BarPriority BAR_PRIORITY = BarPriority.WORST_CASE;

    /* ===================== ТИПЫ/СОСТОЯНИЯ ===================== */

    private enum Dir { LONG, SHORT }

    private static class PendingEntry {
        int entryIndex;
        Dir dir;
        BigDecimal stopPrice;
        BigDecimal plannedEntryPrice;
    }

    private static class Position {
        Dir dir;
        BigDecimal entryPrice;
        BigDecimal stopPrice;     // текущий стоп
        BigDecimal initialStop;   // стоп на входе (для R)
        BigDecimal qtyBtc;
        Instant entryTime;

        boolean beArmed = false;
        boolean armed75 = false;  // LONG: rsi>=75 замечен
        boolean armed25 = false;  // SHORT: rsi<=25 замечен
    }

    private static class WindowAfterSignal1 { Dir dir; int startedAt; int deadline; }
    private static class WindowAfterSignal2 { int startedAt; int deadline; }

    /* ===================== ОСНОВНОЙ БЭКТЕСТ ===================== */

    @Override
    public List<BacktestTrade> backtest(List<Bar> bars) {
        List<BacktestTrade> trades = new ArrayList<>();
        if (bars == null || bars.size() < 10) return trades;

        BigDecimal deposit = INITIAL_DEPOSIT;

        YearMonth currentMonth = YearMonth.from(bars.get(0).openTime().atZone(ZoneOffset.UTC));
        BigDecimal monthlyRiskPct = RISK_PCT;
        BigDecimal monthlyRiskUsd = deposit.max(BigDecimal.ZERO).multiply(monthlyRiskPct).setScale(2, RoundingMode.HALF_UP);

        BigDecimal monthPnl  = BigDecimal.ZERO;
        BigDecimal monthPeak = deposit; // для контроля месячной просадки

        int lossStreak = 0;
        int cooldownLeft = 0;

        Position pos = null;
        PendingEntry pending = null;
        WindowAfterSignal1 w1 = null;
        WindowAfterSignal2 w2 = null;

        for (int i = 0; i < bars.size(); i++) {
            Bar b = bars.get(i);
            Bar prev = (i > 0) ? bars.get(i - 1) : null;

            /* 0) Ролловер месяца */
            YearMonth barMonth = YearMonth.from(b.openTime().atZone(ZoneOffset.UTC));
            if (!barMonth.equals(currentMonth)) {
                // закрыли месяц → если месяц не в минусе — возвращаем обычный риск
                if (monthPnl.signum() >= 0) monthlyRiskPct = RISK_PCT;

                monthlyRiskUsd = deposit.max(BigDecimal.ZERO).multiply(monthlyRiskPct).setScale(2, RoundingMode.HALF_UP);
                currentMonth = barMonth;
                monthPnl = BigDecimal.ZERO;
                monthPeak = deposit;
            }

            /* 1) Активировать pending */
            if (pending != null && pending.entryIndex == i) {
                if (cooldownLeft > 0) { // пропускаем из-за анти-серии
                    cooldownLeft--;
                    pending = null;
                } else {
                    BigDecimal entryPrice = b.open();
                    BigDecimal stopRaw    = pending.stopPrice;
                    Dir dir               = pending.dir;

                    // Режимный фильтр: наклон ema30 в сторону входа
                    boolean regimeOk = (prev == null) || ema30SlopeOk(prev, b, dir);
                    if (regimeOk) {
                        // Фильтр по stop%
                        BigDecimal volFactor = volumeFactor(entryPrice, stopRaw);
                        if (volFactor.signum() > 0) {
                            BigDecimal qty = calcQtyWorstCase(entryPrice, stopRaw, monthlyRiskUsd).multiply(volFactor);
                            qty = floorToStep(qty, STEP_QTY);
                            if (qty.compareTo(MIN_QTY) >= 0) {
                                Position np = new Position();
                                np.dir         = dir;
                                np.entryPrice  = entryPrice;
                                np.initialStop = safeStop(stopRaw, dir);
                                np.stopPrice   = np.initialStop;
                                np.qtyBtc      = qty;
                                np.entryTime   = b.openTime();
                                pos = np;
                            } else if (DEBUG) {
                                System.out.println("qty < MIN_QTY");
                            }
                        } else if (DEBUG) {
                            System.out.println("filtered by stop%");
                        }
                    } else if (DEBUG) {
                        System.out.println("regime filter rejected");
                    }
                    pending = null;
                }
            }

            /* 2) Управление позицией */
            if (pos != null) {
                // 2.0) BE+fees после BE_ARM_R*R
                BigDecimal R = pos.entryPrice.subtract(pos.initialStop).abs();
                if (!pos.beArmed && R.signum() > 0) {
                    BigDecimal armLvl = (pos.dir == Dir.LONG)
                            ? pos.entryPrice.add(R.multiply(BE_ARM_R))
                            : pos.entryPrice.subtract(R.multiply(BE_ARM_R));
                    boolean touched = (pos.dir == Dir.LONG) ? b.high().compareTo(armLvl) >= 0
                            : b.low().compareTo(armLvl)  <= 0;
                    if (touched) {
                        BigDecimal be = beWithFees(pos.entryPrice, pos.dir);
                        BigDecimal beSafe = safeStop(be, pos.dir);
                        pos.stopPrice = (pos.dir == Dir.LONG) ? pos.stopPrice.max(beSafe) : pos.stopPrice.min(beSafe);
                        pos.beArmed = true;
                    }
                }

                // 2.1) TP по 2R
                BigDecimal tpPrice = null;
                if (R.signum() > 0) {
                    tpPrice = (pos.dir == Dir.LONG)
                            ? pos.entryPrice.add(R.multiply(TP_R))
                            : pos.entryPrice.subtract(R.multiply(TP_R));
                }

                // 2.2) SL/TP внутри бара
                if (tpPrice != null) {
                    boolean hitSL = (pos.dir == Dir.LONG) ? b.low().compareTo(pos.stopPrice) <= 0
                            : b.high().compareTo(pos.stopPrice) >= 0;
                    boolean hitTP = (pos.dir == Dir.LONG) ? b.high().compareTo(tpPrice) >= 0
                            : b.low().compareTo(tpPrice)  <= 0;

                    if (hitSL && hitTP) {
                        if (BAR_PRIORITY == BarPriority.SL_FIRST || BAR_PRIORITY == BarPriority.WORST_CASE) {
                            BigDecimal net = addTradeAndNet(trades, b, pos, true);
                            lossStreak = (net.signum() < 0) ? lossStreak + 1 : 0;
                            if (lossStreak >= MAX_LOSS_STREAK) { cooldownLeft = COOLDOWN_BARS; lossStreak = 0; }
                            deposit = deposit.add(net); monthPnl = monthPnl.add(net); pos = null;
                        } else {
                            BigDecimal net = addTradeAndNet(trades, b, pos, false);
                            lossStreak = (net.signum() < 0) ? lossStreak + 1 : 0;
                            if (lossStreak >= MAX_LOSS_STREAK) { cooldownLeft = COOLDOWN_BARS; lossStreak = 0; }
                            deposit = deposit.add(net); monthPnl = monthPnl.add(net); pos = null;
                        }
                    } else if (hitSL) {
                        BigDecimal net = addTradeAndNet(trades, b, pos, true);
                        lossStreak = (net.signum() < 0) ? lossStreak + 1 : 0;
                        if (lossStreak >= MAX_LOSS_STREAK) { cooldownLeft = COOLDOWN_BARS; lossStreak = 0; }
                        deposit = deposit.add(net); monthPnl = monthPnl.add(net); pos = null;
                    } else if (hitTP) {
                        BigDecimal net = addTradeAndNet(trades, b, pos, false);
                        lossStreak = (net.signum() < 0) ? lossStreak + 1 : 0;
                        if (lossStreak >= MAX_LOSS_STREAK) { cooldownLeft = COOLDOWN_BARS; lossStreak = 0; }
                        deposit = deposit.add(net); monthPnl = monthPnl.add(net); pos = null;
                    }
                } else {
                    boolean hitSL = (pos.dir == Dir.LONG) ? b.low().compareTo(pos.stopPrice) <= 0
                            : b.high().compareTo(pos.stopPrice) >= 0;
                    if (hitSL) {
                        BigDecimal net = addTradeAndNet(trades, b, pos, true);
                        lossStreak = (net.signum() < 0) ? lossStreak + 1 : 0;
                        if (lossStreak >= MAX_LOSS_STREAK) { cooldownLeft = COOLDOWN_BARS; lossStreak = 0; }
                        deposit = deposit.add(net); monthPnl = monthPnl.add(net); pos = null;
                    }
                }

                // 2.3) RSI-выходы на 2h (если ещё в позиции)
                if (pos != null && i >= 4 && is2hBoundary(b.openTime())) {
                    Bar p2h = bars.get(i - 4);
                    BigDecimal r0 = p2h.rsi2h(), s0 = p2h.smaRsi2h();
                    BigDecimal r1 = b.rsi2h(),    s1 = b.smaRsi2h();
                    if (r0 != null && s0 != null && r1 != null && s1 != null) {
                        if (pos.dir == Dir.LONG) {
                            if (!pos.armed75 && r1.compareTo(RSI_HIGH) >= 0) pos.armed75 = true;
                            boolean cross = r0.compareTo(s0) > 0 && r1.compareTo(s1) <= 0;
                            boolean fall  = pos.armed75 && r1.compareTo(RSI_HIGH) < 0;
                            if (cross || fall) {
                                BigDecimal net = addTradeAtCloseAndNet(trades, b, pos);
                                lossStreak = (net.signum() < 0) ? lossStreak + 1 : 0;
                                if (lossStreak >= MAX_LOSS_STREAK) { cooldownLeft = COOLDOWN_BARS; lossStreak = 0; }
                                deposit = deposit.add(net); monthPnl = monthPnl.add(net); pos = null;
                            }
                        } else {
                            if (!pos.armed25 && r1.compareTo(RSI_LOW) <= 0) pos.armed25 = true;
                            boolean cross = r0.compareTo(s0) < 0 && r1.compareTo(s1) >= 0;
                            boolean rise  = pos.armed25 && r1.compareTo(RSI_LOW) > 0;
                            if (cross || rise) {
                                BigDecimal net = addTradeAtCloseAndNet(trades, b, pos);
                                lossStreak = (net.signum() < 0) ? lossStreak + 1 : 0;
                                if (lossStreak >= MAX_LOSS_STREAK) { cooldownLeft = COOLDOWN_BARS; lossStreak = 0; }
                                deposit = deposit.add(net); monthPnl = monthPnl.add(net); pos = null;
                            }
                        }
                    }
                }

                // 2.4) Адаптивный риск при месячной просадке
                monthPeak = monthPeak.max(deposit);
                if (monthPeak.signum() > 0) {
                    BigDecimal monthDD = monthPeak.subtract(deposit).divide(monthPeak, 10, RoundingMode.HALF_UP);
                    if (monthDD.compareTo(MONTHLY_DD_LIMIT) >= 0) {
                        monthlyRiskPct = RISK_PCT_REDUCED;
                    }
                }
                monthlyRiskUsd = deposit.max(BigDecimal.ZERO).multiply(monthlyRiskPct).setScale(2, RoundingMode.HALF_UP);
            }

            /* 3) Сигналы 30m */
            boolean s1Long = false, s1Short = false, s2 = false;
            if (prev != null) {
                s1Long  = prev.ema11().compareTo(prev.ema30()) < 0 && b.ema11().compareTo(b.ema30()) >= 0;
                s1Short = prev.ema11().compareTo(prev.ema30()) > 0 && b.ema11().compareTo(b.ema30()) <= 0;
            }
            s2 = b.low().compareTo(b.ema110()) <= 0 && b.ema110().compareTo(b.high()) <= 0;

            /* 4) Окна */
            if (s1Long)  { w1 = new WindowAfterSignal1(); w1.dir=Dir.LONG;  w1.startedAt=i; w1.deadline=i+2; }
            if (s1Short) { w1 = new WindowAfterSignal1(); w1.dir=Dir.SHORT; w1.startedAt=i; w1.deadline=i+2; }
            if (s2)      { w2 = new WindowAfterSignal2(); w2.startedAt=i;   w2.deadline=i+5; }

            /* 5) Пара сигналов → план входа */
            Dir entryDir = null;
            if (w1 != null && s2 && i <= w1.deadline) entryDir = w1.dir;
            if (w2 != null && i <= w2.deadline && (s1Long || s1Short)) entryDir = s1Long ? Dir.LONG : Dir.SHORT;

            if (entryDir != null) {
                int entryIndex = i + 1;
                if (entryIndex < bars.size()) {
                    BigDecimal stop = computeStopForEntry(bars, entryIndex, entryDir);
                    if (stop != null) {
                        if (pos != null) {
                            BigDecimal net = addTradeNowAtCloseAndNet(trades, b, pos);
                            lossStreak = (net.signum() < 0) ? lossStreak + 1 : 0;
                            if (lossStreak >= MAX_LOSS_STREAK) { cooldownLeft = COOLDOWN_BARS; lossStreak = 0; }
                            deposit = deposit.add(net); monthPnl = monthPnl.add(net); pos = null;
                        }
                        PendingEntry pe = new PendingEntry();
                        pe.entryIndex = entryIndex;
                        pe.dir        = entryDir;
                        pe.stopPrice  = stop;
                        pe.plannedEntryPrice = bars.get(entryIndex).open();
                        pending = pe;
                    }
                }
                w1 = null; w2 = null;
            }

            /* 6) Истечение окон */
            if (w1 != null && i > w1.deadline) w1 = null;
            if (w2 != null && i > w2.deadline) w2 = null;
        }

        return trades;
    }

    /* ===================== ЗАКРЫТИЯ/PNL ===================== */

    /** SL/TP → трейд по next OPEN, вернуть net PnL. */
    private BigDecimal addTradeAndNet(List<BacktestTrade> trades, Bar b, Position pos, boolean byStop) {
        BigDecimal exitPrice = byStop ? pos.stopPrice : tpPrice(pos);
        BacktestTrade t = buildTrade(pos, pos.entryTime, pos.entryPrice, pos.initialStop,
                nextOpenTime(b, TF), scale2(exitPrice));
        trades.add(t);
        return computeNetPnl(pos.dir, pos.entryPrice, exitPrice, pos.qtyBtc, byStop);
    }

    /** Выход по CLOSE (RSI/реверс), вернуть net PnL. */
    private BigDecimal addTradeAtCloseAndNet(List<BacktestTrade> trades, Bar b, Position pos) {
        BigDecimal exitPrice = b.close();
        BacktestTrade t = buildTrade(pos, pos.entryTime, pos.entryPrice, pos.initialStop,
                nextOpenTime(b, TF), scale2(exitPrice));
        trades.add(t);
        // считаем как «тейк» по комиссии
        return computeNetPnl(pos.dir, pos.entryPrice, exitPrice, pos.qtyBtc, false);
    }

    /** Немедленный выход по CLOSE (для разворота). */
    private BigDecimal addTradeNowAtCloseAndNet(List<BacktestTrade> trades, Bar b, Position pos) {
        return addTradeAtCloseAndNet(trades, b, pos);
    }

    private BigDecimal computeNetPnl(Dir dir, BigDecimal entry, BigDecimal exit, BigDecimal qty, boolean byStop) {
        BigDecimal gross = (dir == Dir.LONG)
                ? exit.subtract(entry).multiply(qty)
                : entry.subtract(exit).multiply(qty);
        BigDecimal feeIn  = entry.multiply(qty).multiply(FEE_IN);
        BigDecimal feeOut = (byStop ? exit.multiply(qty).multiply(FEE_STOP)
                : exit.multiply(qty).multiply(FEE_TP));
        return scale2(gross.subtract(feeIn).subtract(feeOut));
    }

    private BigDecimal tpPrice(Position p) {
        BigDecimal R = p.entryPrice.subtract(p.initialStop).abs();
        if (R.signum() == 0) return p.entryPrice;
        return (p.dir == Dir.LONG) ? p.entryPrice.add(R.multiply(TP_R))
                : p.entryPrice.subtract(R.multiply(TP_R));
    }

    /* ===================== СТОП/УРОВНИ ===================== */

    private BigDecimal computeStopForEntry(List<Bar> bars, int entryIndex, Dir dir) {
        int from = Math.max(0, entryIndex - 5);
        int to   = entryIndex;

        Integer impIdx = findStrongestImpulseInWindow(bars, from, to);
        if (impIdx == null) return stopByTema9Window(bars, entryIndex, dir);

        Bar imp = bars.get(impIdx);
        BigDecimal open  = imp.open();
        BigDecimal close = imp.close();
        BigDecimal bodyHigh = open.max(close);
        BigDecimal bodyLow  = open.min(close);
        BigDecimal body = bodyHigh.subtract(bodyLow);
        if (body.signum() <= 0) return stopByTema9Window(bars, entryIndex, dir);

        BigDecimal half = body.divide(new BigDecimal("2"), 10, RoundingMode.HALF_UP);
        BigDecimal mid  = bodyLow.add(half);

        Range zone1 = (dir == Dir.LONG) ? new Range(bodyLow, mid) : new Range(mid, bodyHigh);
        Range zone2 = (dir == Dir.LONG) ? new Range(bodyLow.subtract(half), bodyLow)
                : new Range(bodyHigh, bodyHigh.add(half));

        List<BigDecimal> cands = new ArrayList<>();
        addIfInZone(cands, imp.ema110(), zone1);
        addIfInZone(cands, imp.ema200(), zone1);
        for (Integer crossIdx : findCrossIdxInWindow(bars, from, to)) {
            BigDecimal lvl = avg(bars.get(crossIdx).ema11(), bars.get(crossIdx).ema30());
            addIfInZone(cands, lvl, zone1);
        }
        if (cands.isEmpty()) {
            addIfInZone(cands, imp.ema110(), zone2);
            addIfInZone(cands, imp.ema200(), zone2);
            for (Integer crossIdx : findCrossIdxInWindow(bars, from, to)) {
                BigDecimal lvl = avg(bars.get(crossIdx).ema11(), bars.get(crossIdx).ema30());
                addIfInZone(cands, lvl, zone2);
            }
        }
        if (cands.isEmpty()) return stopByTema9Window(bars, entryIndex, dir);

        cands.sort(Comparator.naturalOrder());
        BigDecimal min = cands.get(0);
        BigDecimal max = cands.get(cands.size() - 1);
        BigDecimal chosen = areEquivalent(min, max) ? ((dir == Dir.LONG) ? min : max)
                : ((dir == Dir.LONG) ? min : max);
        return safeStop(chosen, dir);
    }

    private BigDecimal stopByTema9Window(List<Bar> bars, int entryIndex, Dir dir) {
        int from = Math.max(0, entryIndex - 5);
        int to   = entryIndex;
        BigDecimal best = null;
        for (int j = from; j <= to; j++) {
            BigDecimal t9 = bars.get(j).tema9();
            if (t9 == null) continue;
            best = (best == null) ? t9 : (dir == Dir.LONG ? best.min(t9) : best.max(t9));
        }
        return (best == null) ? null : safeStop(best, dir);
    }

    /* ===================== ВСПОМОГАТЕЛЬНЫЕ ===================== */

    private static class Range {
        final BigDecimal lo, hi;
        Range(BigDecimal a, BigDecimal b){ this.lo=a.min(b); this.hi=a.max(b); }
        boolean contains(BigDecimal x){ return x!=null && x.compareTo(lo)>=0 && x.compareTo(hi)<=0; }
    }

    private static void addIfInZone(List<BigDecimal> dst, BigDecimal lvl, Range z){
        if (lvl!=null && z.contains(lvl)) dst.add(lvl);
    }

    /** Среднее двух значений (или null). */
    private static BigDecimal avg(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) return null;
        return a.add(b).divide(new BigDecimal("2"), 10, RoundingMode.HALF_UP);
    }

    /** Импульсная свеча в окне по |c-o|/o; при равенстве — более поздняя. */
    private Integer findStrongestImpulseInWindow(List<Bar> bars, int from, int to) {
        Integer bestIdx=null; BigDecimal bestScore=null;
        for (int i=Math.max(0,from); i<=Math.min(to,bars.size()-1); i++){
            boolean imp=false;
            try { imp = bars.get(i).isImpulse(); } catch (Throwable ignore) {}
            if (!imp) continue;
            BigDecimal o=bars.get(i).open(), c=bars.get(i).close();
            if (o==null || o.signum()==0 || c==null) continue;
            BigDecimal score = c.subtract(o).abs().divide(o.abs(), 10, RoundingMode.HALF_UP);
            if (bestIdx==null || score.compareTo(bestScore)>0 || (score.compareTo(bestScore)==0 && i>bestIdx)) {
                bestIdx=i; bestScore=score;
            }
        }
        return bestIdx;
    }

    /** Индексы кроссов EMA11/EMA30 в окне. */
    private List<Integer> findCrossIdxInWindow(List<Bar> bars, int from, int to){
        List<Integer> res=new ArrayList<>();
        int lo=Math.max(1,from), hi=Math.min(to,bars.size()-1);
        for(int i=lo;i<=hi;i++){
            Bar p=bars.get(i-1), c=bars.get(i);
            if(p.ema11()==null||p.ema30()==null||c.ema11()==null||c.ema30()==null) continue;
            boolean up  = p.ema11().compareTo(p.ema30())<0 && c.ema11().compareTo(c.ema30())>=0;
            boolean dn  = p.ema11().compareTo(p.ema30())>0 && c.ema11().compareTo(c.ema30())<=0;
            if(up||dn) res.add(i);
        }
        return res;
    }

    /** Эквивалентность уровней: |a-b|/((a+b)/2) <= 0.03% */
    private boolean areEquivalent(BigDecimal a, BigDecimal b){
        if(a==null||b==null) return false;
        BigDecimal diff=a.subtract(b).abs(), mean=a.add(b).divide(new BigDecimal("2"),10,RoundingMode.HALF_UP);
        if(mean.signum()==0) return diff.signum()==0;
        return diff.divide(mean,10,RoundingMode.HALF_UP).compareTo(EQUIV_REL)<=0;
    }

    /** sizing: худший случай = выход всем объёмом по стопу (+ обе комиссии) */
    private static BigDecimal calcQtyWorstCase(BigDecimal entry, BigDecimal stop, BigDecimal riskUsd){
        if (riskUsd==null || riskUsd.signum()<=0) return BigDecimal.ZERO;
        BigDecimal delta = entry.subtract(stop).abs();
        BigDecimal feeIn  = entry.multiply(FEE_IN);
        BigDecimal feeOut = stop.multiply(FEE_STOP);
        BigDecimal denom = delta.add(feeIn).add(feeOut);
        if (denom.signum()<=0) return BigDecimal.ZERO;
        BigDecimal raw = riskUsd.divide(denom,10,RoundingMode.HALF_UP);
        BigDecimal floored = floorToStep(raw, STEP_QTY);
        return floored.compareTo(MIN_QTY)>=0 ? floored : BigDecimal.ZERO;
    }

    /** Цена BE+fees (net ≥ 0 при выходе по стопу). */
    private static BigDecimal beWithFees(BigDecimal entry, Dir dir){
        if (dir==Dir.LONG) {
            return entry.multiply(BigDecimal.ONE.add(FEE_IN))
                    .divide(BigDecimal.ONE.subtract(FEE_STOP), 10, RoundingMode.HALF_UP);
        } else {
            return entry.multiply(BigDecimal.ONE.subtract(FEE_IN))
                    .divide(BigDecimal.ONE.add(FEE_STOP), 10, RoundingMode.HALF_UP);
        }
    }

    /** Фактор объёма по фильтру stop%. */
    private static BigDecimal volumeFactor(BigDecimal entry, BigDecimal stop){
        if (entry==null || entry.signum()==0 || stop==null) return BigDecimal.ZERO;
        BigDecimal pct = entry.subtract(stop).abs().divide(entry, 10, RoundingMode.HALF_UP);
        if (pct.compareTo(STOP_PCT_LOW) < 0) return BigDecimal.ZERO;
        if (pct.compareTo(STOP_PCT_MID) < 0) return new BigDecimal("0.5");
        return BigDecimal.ONE;
    }

    /** Режимный фильтр: наклон EMA30 в сторону входа и не слишком маленький. */
    private static boolean ema30SlopeOk(Bar prev, Bar cur, Dir dir){
        if (prev==null || cur==null || prev.ema30()==null || cur.ema30()==null || cur.ema30().signum()==0) return true;
        int cmp = cur.ema30().compareTo(prev.ema30());
        BigDecimal slope = cur.ema30().subtract(prev.ema30()).abs().divide(cur.ema30(), 10, RoundingMode.HALF_UP);
        if (dir==Dir.LONG)  return cmp>=0 && slope.compareTo(EMA30_SLOPE_MIN)>=0;
        else                return cmp<=0 && slope.compareTo(EMA30_SLOPE_MIN)>=0;
    }

    /** Округление стопа в пользу безопасности. */
    private static BigDecimal safeStop(BigDecimal stop, Dir dir){
        if (stop==null) return null;
        if (dir==Dir.LONG)
            return stop.divide(PRICE_STEP,0,RoundingMode.FLOOR).multiply(PRICE_STEP);
        else
            return stop.divide(PRICE_STEP,0,RoundingMode.CEILING).multiply(PRICE_STEP);
    }

    private static Instant nextOpenTime(Bar b, Duration tf){ return b.openTime().plus(tf); }
    private static boolean is2hBoundary(Instant ts){
        ZonedDateTime z=ts.atZone(ZoneOffset.UTC); return z.getMinute()==0 && (z.getHour()%2==0);
    }

    private static BigDecimal floorToStep(BigDecimal v, BigDecimal step){
        if(v==null || v.signum()<=0) return BigDecimal.ZERO;
        return v.divide(step,0,RoundingMode.DOWN).multiply(step);
    }
    private static BigDecimal scale2(BigDecimal v){ return v.setScale(2, RoundingMode.HALF_UP); }
    private static BigDecimal scale3(BigDecimal v){ return v.setScale(3, RoundingMode.HALF_UP); }

    private static BacktestTrade buildTrade(
            Position pos, Instant entryTime, BigDecimal entryPrice, BigDecimal initStop,
            Instant exitTime, BigDecimal exitPrice
    ){
        BacktestTrade t = new BacktestTrade();
        t.setEntryTime(entryTime);
        t.setSide(pos.dir==Dir.LONG ? "LONG" : "SHORT");
        t.setEntryPrice(scale2(entryPrice));
        t.setStopPrice(scale2(initStop));
        t.setQtyBtc(scale3(pos.qtyBtc));
        t.setExitTime(exitTime);
        t.setExitPrice(scale2(exitPrice));
        return t;
    }
}
