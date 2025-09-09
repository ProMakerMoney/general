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
 * - Риск фиксированный: 100 USDT.
 * - Комиссии учитываются в расчёте объёма (худший случай = выход по стопу всем объёмом).
 * - TP1 = 2R на 30% объёма, остаток 70%.
 * - После TP1 стоп переводится в настоящий BE+fees.
 * - RSI-выходы анализируются на 2h (сравнение i-4 и i).
 * - Стопы округляются в пользу безопасности (шаг цены 0.01).
 * - Встроен фильтр боковика по относительной дистанции стопа:
 *      stop_pct = |entry - stop| / entry.
 *      < 0.6% — пропуск; 0.6–1.0% — половинный объём; >=1.0% — полный объём.
 */
public class MainStrategy {

    /* ========================= КОНСТАНТЫ ========================= */

    // Риск и шаги
    private static final BigDecimal RISK_USDT = new BigDecimal("100");
    private static final BigDecimal MIN_QTY   = new BigDecimal("0.001");
    private static final BigDecimal STEP_QTY  = new BigDecimal("0.001");
    private static final BigDecimal PRICE_STEP= new BigDecimal("0.01");
    private static final Duration   TF        = Duration.ofMinutes(30);

    // Комиссии (примерные — замени на реальные для своей биржи/аккаунта)
    private static final BigDecimal FEE_IN       = new BigDecimal("0.0002");  // вход
    private static final BigDecimal FEE_TP       = new BigDecimal("0.0002");  // TP
    private static final BigDecimal FEE_STOP     = new BigDecimal("0.00055"); // стоп (тейкер)

    // Фильтр боковика по расстоянию стопа (красный вариант 0.6%/1.0%)
    private static final BigDecimal STOP_PCT_LOW = new BigDecimal("0.006"); // 0.6%
    private static final BigDecimal STOP_PCT_MID = new BigDecimal("0.010"); // 1.0%

    // Прочее
    private static final boolean DEBUG = false;
    private static final BigDecimal EQUIV_REL = new BigDecimal("0.0003"); // 0.03 %

    /* ========================= ТИПЫ ========================= */

    enum Dir { LONG, SHORT }

    private static class PendingEntry {
        int entryIndex;
        Dir dir;
        BigDecimal stopPrice;
        BigDecimal entryPrice;
        BigDecimal qtyBtc;
        String stopSource;
        boolean impulse;
    }

    private static class Position {
        Dir dir;
        BigDecimal entryPrice;
        BigDecimal stopPrice;
        BigDecimal initialStop;
        BigDecimal qtyFull;
        BigDecimal qtyHalf1;   // 30%
        BigDecimal qtyHalf2;   // 70%
        Instant entryTime;
        int entryIndex;
        boolean tp1Done = false;
        BigDecimal tp1Price = null;
        boolean armed75 = false;
        boolean armed35 = false;
        String stopSource;
        boolean impulse;
    }

    private static class WindowAfterSignal1 { Dir dir; int startedAt; int deadline; int s1Index; }
    private static class WindowAfterSignal2 { int startedAt; int deadline; }

    private static class StopCalcResult {
        final BigDecimal stop;
        final String source;
        final boolean impulse;
        StopCalcResult(BigDecimal stop, String source, boolean impulse) {
            this.stop = stop; this.source = source; this.impulse = impulse;
        }
    }

    private static class Cand { BigDecimal level; String source; Cand(BigDecimal l, String s) { level=l; source=s; } }
    private static class Range {
        BigDecimal lo, hi;
        Range(BigDecimal a, BigDecimal b){ lo=a.min(b); hi=a.max(b); }
        boolean contains(BigDecimal x){ return x!=null && x.compareTo(lo)>=0 && x.compareTo(hi)<=0; }
    }

    /** Если уровень попадает в зону — добавить кандидата. */
    private static void addIfInZone(List<Cand> dst, Cand c, Range z) {
        if (c != null && c.level != null && z != null && z.contains(c.level)) {
            dst.add(c);
        }
    }

    /** Среднее двух чисел (или null, если одно из них null). */
    private static BigDecimal avg(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) return null;
        return a.add(b).divide(BigDecimal.valueOf(2), 10, RoundingMode.HALF_UP);
    }

    /* ========================= ОСНОВНОЙ ПРОХОД ========================= */

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

            /* 1) Активируем отложенный вход */
            if (pending != null && pending.entryIndex == i) {
                pos = new Position();
                pos.dir = pending.dir;
                pos.entryPrice = pending.entryPrice;
                pos.stopPrice = pending.stopPrice;
                pos.initialStop = pending.stopPrice;
                pos.qtyFull = pending.qtyBtc;
                pos.entryTime = b.openTime();
                pos.entryIndex = i;
                pos.stopSource = pending.stopSource;
                pos.impulse = pending.impulse;

                // 30/70 сплит
                if (pos.qtyFull.compareTo(new BigDecimal("0.002")) >= 0) {
                    BigDecimal tp1Frac = new BigDecimal("0.30");
                    pos.qtyHalf1 = floorToStep(pos.qtyFull.multiply(tp1Frac), STEP_QTY);
                    pos.qtyHalf2 = pos.qtyFull.subtract(pos.qtyHalf1);
                } else {
                    pos.qtyHalf1 = BigDecimal.ZERO;
                    pos.qtyHalf2 = pos.qtyFull;
                }
                pending = null;
            }

            /* 2) Управление открытой позицией: SL → TP1 → RSI(2h) */
            boolean closedThisBar = false;
            if (pos != null) {
                // SL
                if (pos.dir == Dir.LONG ? b.low().compareTo(pos.stopPrice) <= 0
                        : b.high().compareTo(pos.stopPrice) >= 0) {
                    String reason = pos.tp1Done ? "ONLY_TP_1" : "STOP_LOSS";
                    trades.add(buildTrade(pos, nextOpenTime(b), safePrice(pos.stopPrice), reason));
                    pos = null; closedThisBar = true;
                }

                // TP1 = 2R (на 30% объёма), затем стоп → BE+fees
                if (!closedThisBar && !pos.tp1Done && pos.qtyHalf1.signum() > 0) {
                    BigDecimal r = pos.entryPrice.subtract(pos.initialStop).abs();
                    BigDecimal tp1 = (pos.dir == Dir.LONG)
                            ? pos.entryPrice.add(r.multiply(new BigDecimal("2")))
                            : pos.entryPrice.subtract(r.multiply(new BigDecimal("2")));
                    boolean touched = (pos.dir == Dir.LONG) ? b.high().compareTo(tp1) >= 0
                            : b.low().compareTo(tp1)  <= 0;
                    if (touched) {
                        pos.tp1Done = true;
                        pos.tp1Price = safePrice(tp1);

                        // Перевод стопа в настоящий BE+fees
                        BigDecimal beWithFees = computeBeWithFeesStop(pos);
                        BigDecimal beSafe = safeStop(beWithFees, pos.dir);
                        pos.stopPrice = (pos.dir == Dir.LONG)
                                ? pos.stopPrice.max(beSafe)
                                : pos.stopPrice.min(beSafe);
                    }
                }

                // RSI-выход 2h (сравнение i-4 и i)
                if (!closedThisBar && i >= 4 && is2hBoundary(b.openTime())) {
                    Bar p2h = bars.get(i - 4);
                    BigDecimal r0 = p2h.rsi2h(), s0 = p2h.smaRsi2h();
                    BigDecimal r1 = b.rsi2h(),    s1 = b.smaRsi2h();
                    if (r0 != null && s0 != null && r1 != null && s1 != null) {
                        if (pos.dir == Dir.LONG) {
                            if (!pos.armed75 && r1.compareTo(new BigDecimal("75")) >= 0) pos.armed75 = true;
                            boolean cross = r0.compareTo(s0) > 0 && r1.compareTo(s1) <= 0;
                            boolean ex75  = pos.armed75 && r1.compareTo(new BigDecimal("75")) < 0;
                            if (cross || ex75) {
                                trades.add(buildTrade(pos, nextOpenTime(b), safePrice(b.close()),
                                        cross ? "RSI_CROSS" : "RSI_75_35"));
                                pos = null; closedThisBar = true;
                            }
                        } else {
                            if (!pos.armed35 && r1.compareTo(new BigDecimal("35")) <= 0) pos.armed35 = true;
                            boolean cross = r0.compareTo(s0) < 0 && r1.compareTo(s1) >= 0;
                            boolean ex35  = pos.armed35 && r1.compareTo(new BigDecimal("35")) > 0;
                            if (cross || ex35) {
                                trades.add(buildTrade(pos, nextOpenTime(b), safePrice(b.close()),
                                        cross ? "RSI_CROSS" : "RSI_75_35"));
                                pos = null; closedThisBar = true;
                            }
                        }
                    }
                }
            }

            /* 3) Сигналы 30m */
            boolean s1Long = false, s1Short = false, s2;
            if (prev != null) {
                s1Long  = prev.ema11().compareTo(prev.ema30()) < 0 && b.ema11().compareTo(b.ema30()) >= 0;
                s1Short = prev.ema11().compareTo(prev.ema30()) > 0 && b.ema11().compareTo(b.ema30()) <= 0;
            }
            s2 = b.low().compareTo(b.ema110()) <= 0 && b.ema110().compareTo(b.high()) <= 0;

            /* 4) Окна */
            WindowAfterSignal1 w1New = null;
            if (s1Long)  { w1New = new WindowAfterSignal1(); w1New.dir=Dir.LONG;  w1New.startedAt=i; w1New.deadline=i+2; w1New.s1Index=i; }
            if (s1Short) { w1New = new WindowAfterSignal1(); w1New.dir=Dir.SHORT; w1New.startedAt=i; w1New.deadline=i+2; w1New.s1Index=i; }
            if (w1New != null) w1 = w1New;

            WindowAfterSignal2 w2New = null;
            if (s2) { w2New = new WindowAfterSignal2(); w2New.startedAt=i; w2New.deadline=i+5; }
            if (w2New != null) w2 = w2New;

            /* 5) Пара сигналов сформирована? */
            Dir entryDir = null;
            if (w1 != null && s2 && i <= w1.deadline) entryDir = w1.dir;
            if (w2 != null && i <= w2.deadline && (s1Long || s1Short))
                entryDir = s1Long ? Dir.LONG : Dir.SHORT;

            /* 6) Переворот */
            if (pos != null && entryDir != null && pos.dir != entryDir) {
                trades.add(buildTrade(pos, nextOpenTime(b), safePrice(b.close()), "REVERSAL_CLOSE"));
                pos = null; closedThisBar = true;
            }

            /* 7) Планируем вход на i+1 */
            if (entryDir != null) {
                int entryIndex = i + 1;
                if (entryIndex < bars.size()) {
                    Bar entryBar = bars.get(entryIndex);
                    StopCalcResult sc = computeStopForEntry(bars, entryIndex, entryDir);
                    if (sc != null && sc.stop != null) {
                        BigDecimal entryPrice = entryBar.open();

                        // === ФИЛЬТР БОКОВИКА ПО STOP_PCT ===
                        BigDecimal factor = filterVolumeFactor(entryPrice, sc.stop);
                        if (factor.signum() > 0) {
                            BigDecimal qty = calcQty(entryPrice, sc.stop).multiply(factor);
                            qty = floorToStep(qty, STEP_QTY);

                            if (qty.compareTo(MIN_QTY) >= 0) {
                                pending = new PendingEntry();
                                pending.entryIndex = entryIndex;
                                pending.dir        = entryDir;
                                pending.stopPrice  = safeStop(sc.stop, entryDir);
                                pending.entryPrice = entryPrice;
                                pending.qtyBtc     = qty;
                                pending.stopSource = sc.source;
                                pending.impulse    = sc.impulse;
                            }
                        } else {
                            // фильтр сказал "пропустить сделку"
                            if (DEBUG) System.out.println("Filtered out by stop_pct < " + STOP_PCT_LOW);
                        }
                    }
                }
                w1 = null; w2 = null;
            }

            /* 8) Истечение окон */
            if (w1 != null && i > w1.deadline) w1 = null;
            if (w2 != null && i > w2.deadline) w2 = null;
        }
        return trades;
    }

    /* ========================= СТОП-ЛОГИКА ========================= */

    private StopCalcResult computeStopForEntry(List<Bar> bars, int entryIndex, Dir dir) {
        int from = Math.max(0, entryIndex - 5);
        int to   = entryIndex;

        Integer impIdx = findStrongestImpulseInWindow(bars, from, to);
        boolean impulse = (impIdx != null);

        if (impIdx == null) {
            BigDecimal stop = stopByTema9Window(bars, entryIndex, dir);
            if (DEBUG && stop == null) System.out.println("TEMA9 returned null -> no trade");
            return new StopCalcResult(stop, "TEMA9", false);
        }

        Bar imp = bars.get(impIdx);
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

        Range zone1 = (dir == Dir.LONG) ? new Range(bodyLow, mid) : new Range(mid, bodyHigh);
        Range zone2 = (dir == Dir.LONG) ? new Range(bodyLow.subtract(half), bodyLow)
                : new Range(bodyHigh, bodyHigh.add(half));

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

        cands.sort(Comparator.comparing(c -> c.level));
        BigDecimal min = cands.get(0).level;
        BigDecimal max = cands.get(cands.size() - 1).level;
        BigDecimal chosen = areEquivalent(min, max)
                ? (dir == Dir.LONG ? min : max)
                : (dir == Dir.LONG ? min : max);
        String src = "TEMA9";
        for (Cand c : cands) if (c.level.compareTo(chosen) == 0) { src = c.source; break; }
        return new StopCalcResult(chosen, src, true);
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
        return best;
    }

    private Integer findStrongestImpulseInWindow(List<Bar> bars, int from, int to) {
        Integer bestIdx=null; BigDecimal bestScore=null;
        for(int i=Math.max(0,from);i<=Math.min(to,bars.size()-1);i++){
            try{
                if(!bars.get(i).isImpulse()) continue;
                BigDecimal open=bars.get(i).open(), close=bars.get(i).close();
                if(open==null||open.signum()==0||close==null) continue;
                BigDecimal score = close.subtract(open).abs().divide(open,10,RoundingMode.HALF_UP);
                if(bestIdx==null||score.compareTo(bestScore)>0||(score.compareTo(bestScore)==0&&i>bestIdx))
                { bestIdx=i; bestScore=score; }
            }catch(Throwable t){/*ignore*/}
        }
        return bestIdx;
    }

    private List<Integer> findCrossIdxInWindow(List<Bar> bars, int from, int to){
        List<Integer> res=new ArrayList<>();
        int lo=Math.max(1,from), hi=Math.min(to,bars.size()-1);
        for(int i=lo;i<=hi;i++){
            Bar prev=bars.get(i-1), cur=bars.get(i);
            if(prev.ema11()==null||prev.ema30()==null||cur.ema11()==null||cur.ema30()==null) continue;
            boolean up=prev.ema11().compareTo(prev.ema30())<0&&cur.ema11().compareTo(cur.ema30())>=0;
            boolean down=prev.ema11().compareTo(prev.ema30())>0&&cur.ema11().compareTo(cur.ema30())<=0;
            if(up||down) res.add(i);
        }
        return res;
    }

    private boolean areEquivalent(BigDecimal a, BigDecimal b){
        if(a==null||b==null) return false;
        BigDecimal diff=a.subtract(b).abs(), mean=a.add(b).divide(BigDecimal.valueOf(2),10,RoundingMode.HALF_UP);
        if(mean.signum()==0) return diff.signum()==0;
        return diff.divide(mean,10,RoundingMode.HALF_UP).compareTo(EQUIV_REL)<=0;
    }

    /* ========================= РИСК & ОБЪЁМ (с комиссиями) ========================= */

    /** qty = RISK_USDT / ( |entry - stop| + entry*FEE_IN + stop*FEE_STOP ) */
    private BigDecimal calcQty(BigDecimal entry, BigDecimal stop){
        BigDecimal delta = entry.subtract(stop).abs();
        BigDecimal feeIn  = entry.multiply(FEE_IN);
        BigDecimal feeOutStop = stop.multiply(FEE_STOP);
        BigDecimal denom = delta.add(feeIn).add(feeOutStop);
        if (denom.signum() <= 0) {
            if (DEBUG) System.out.println("delta + fees <= 0 -> no trade");
            return BigDecimal.ZERO;
        }
        BigDecimal raw = RISK_USDT.divide(denom, 10, RoundingMode.HALF_UP);
        BigDecimal floored = floorToStep(raw, STEP_QTY);
        return floored.compareTo(MIN_QTY) >= 0 ? floored : BigDecimal.ZERO;
    }

    /* ========================= BE + FEES после TP1 ========================= */

    private BigDecimal computeBeWithFeesStop(Position pos) {
        if (!pos.tp1Done || pos.qtyHalf2.signum() == 0) {
            return pos.entryPrice;
        }

        BigDecimal E  = pos.entryPrice;
        BigDecimal P1 = pos.tp1Price;
        BigDecimal q1 = pos.qtyHalf1;
        BigDecimal q2 = pos.qtyHalf2;
        BigDecimal Q  = pos.qtyFull;

        if (pos.dir == Dir.LONG) {
            BigDecimal numerator = E.multiply(Q).multiply(BigDecimal.ONE.add(FEE_IN))
                    .add(q1.multiply(P1).multiply(FEE_TP.subtract(BigDecimal.ONE)));
            BigDecimal denom = q2.multiply(BigDecimal.ONE.subtract(FEE_STOP));
            if (denom.signum() == 0) return E;
            BigDecimal S = numerator.divide(denom, 10, RoundingMode.HALF_UP);
            return S.max(E);
        } else {
            BigDecimal numerator = q1.multiply(E.subtract(P1))
                    .add(q2.multiply(E))
                    .subtract(E.multiply(FEE_IN).multiply(Q))
                    .subtract(P1.multiply(FEE_TP).multiply(q1));
            BigDecimal denom = q2.multiply(BigDecimal.ONE.add(FEE_STOP));
            if (denom.signum() == 0) return E;
            BigDecimal S = numerator.divide(denom, 10, RoundingMode.HALF_UP);
            return S.min(E);
        }
    }

    /* ========================= ФИЛЬТР STOP_PCT ========================= */

    /** stop_pct = |entry - stop| / entry */
    private static BigDecimal stopPct(BigDecimal entry, BigDecimal stop){
        if (entry == null || stop == null || entry.signum() == 0) return BigDecimal.ZERO;
        return entry.subtract(stop).abs().divide(entry, 10, RoundingMode.HALF_UP);
    }

    /**
     * Возвращает коэффициент объёма в зависимости от stop_pct:
     *   0 → сделку пропускаем;
     *   0.5 → половинный объём;
     *   1 → полный объём.
     */
    private static BigDecimal filterVolumeFactor(BigDecimal entry, BigDecimal stop){
        BigDecimal pct = stopPct(entry, stop);
        if (pct.compareTo(STOP_PCT_LOW) < 0) return BigDecimal.ZERO;      // пропуск
        if (pct.compareTo(STOP_PCT_MID) < 0) return new BigDecimal("0.5"); // половина
        return BigDecimal.ONE;                                             // полный
    }

    /* ========================= ОКРУГЛЕНИЯ ========================= */

    /** Округляем стоп в пользу безопасности: LONG — вниз, SHORT — вверх (шаг 0.01). */
    private BigDecimal safeStop(BigDecimal stop, Dir dir){
        if (dir == Dir.LONG)
            return stop.divide(PRICE_STEP,0,RoundingMode.FLOOR).multiply(PRICE_STEP);
        else
            return stop.divide(PRICE_STEP,0,RoundingMode.CEILING).multiply(PRICE_STEP);
    }
    private static BigDecimal safePrice(BigDecimal v){ return v.setScale(2, RoundingMode.HALF_UP); }

    private static BigDecimal floorToStep(BigDecimal val, BigDecimal step){
        if(val.signum()<=0) return BigDecimal.ZERO;
        return val.divide(step,0,RoundingMode.DOWN).multiply(step);
    }

    /* ========================= ВРЕМЯ ========================= */

    private static Instant nextOpenTime(Bar b){ return b.openTime().plus(TF); }
    private static boolean is2hBoundary(Instant ts){
        ZonedDateTime z=ts.atZone(ZoneOffset.UTC);
        return z.getMinute()==0 && (z.getHour()%2==0);
    }

    /* ========================= СБОРКА ТРЕЙДА ========================= */

    private static MainBacktestTrade buildTrade(Position pos, Instant exitTime, BigDecimal exitPrice, String reason){
        MainBacktestTrade t=new MainBacktestTrade();
        t.setEntryTime(pos.entryTime);
        t.setSide(pos.dir==Dir.LONG?"LONG":"SHORT");
        t.setEntryPrice(safePrice(pos.entryPrice));
        t.setStopPrice(safePrice(pos.initialStop));
        t.setQtyBtc(pos.qtyFull.setScale(3,RoundingMode.HALF_UP));
        t.setTp1Price(pos.tp1Done?pos.tp1Price:null);
        t.setExitTime(exitTime);
        t.setExitPrice(exitPrice);
        t.setTp2Price(exitPrice);
        t.setReason(reason);
        t.setStopSource(pos.stopSource);
        t.setImpulse(pos.impulse);
        return t;
    }
}
