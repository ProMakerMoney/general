package com.pinbot.botprime.strategy;

import com.pinbot.botprime.backtest.IndicatorDao.Bar;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * MainProStrategy (reinvest + risk protections)
 *
 * Новое:
 * 1) Реинвестирование: риск на ПАРУ (MAIN+HEDGE) = доля от текущего equity.
 * 2) Фильтр тренда: LONG только выше EMA200, SHORT только ниже EMA200.
 * 3) Дневной лимит стопов: если за день ≥ DAILY_STOP_LIMIT стопов MAIN — новые входы в этот день запрещены.
 * 4) Анти-разгон: при серии убыточных ПАР (consecutiveLosses >= LOSS_STREAK_RISK_DOWN)
 *    риск снижается до RISK_PCT_PER_PAIR_LOW, восстановление — после прибыльной ПАРЫ.
 */
public class MainProStrategy {

    /* ====== БАЗОВЫЕ ПАРАМЕТРЫ РИСКА/ДЕПОЗИТА ====== */
    private static final BigDecimal INITIAL_DEPOSIT       = new BigDecimal("15000");
    private static final BigDecimal RISK_PCT_PER_PAIR     = new BigDecimal("0.02"); // 2% от equity
    private static final BigDecimal RISK_PCT_PER_PAIR_LOW = new BigDecimal("0.01"); // 1% при серии лоссов
    private static final int        LOSS_STREAK_RISK_DOWN = 4;                      // ↓ риск после 4 убыточных ПАР подряд

    /* ====== КОМИССИИ (подстрой под свой аккаунт) ====== */
    private static final BigDecimal FEE_MAKER_IN_MAIN   = new BigDecimal("0.0002");
    private static final BigDecimal FEE_TAKER_OUT_MAIN  = new BigDecimal("0.00055");
    private static final BigDecimal FEE_TAKER_IN_HEDGE  = new BigDecimal("0.00055");
    private static final BigDecimal FEE_TAKER_OUT_HEDGE = new BigDecimal("0.00055");

    /* ====== ПРОЧИЕ НАСТРОЙКИ ====== */
    private static final BigDecimal HEDGE_RATIO = new BigDecimal("0.30"); // доля хеджа к MAIN
    private static final BigDecimal MIN_QTY     = new BigDecimal("0.001");
    private static final BigDecimal STEP_QTY    = new BigDecimal("0.001");
    private static final BigDecimal PRICE_STEP  = new BigDecimal("0.01");
    private static final Duration   TF          = Duration.ofMinutes(30);
    private static final BigDecimal EQUIV_REL   = new BigDecimal("0.0003"); // 0.03 %

    // Дневной лимит стопов MAIN (после достижения новых входов в этот день не делаем)
    private static final int DAILY_STOP_LIMIT = 3;

    public enum Dir  { LONG, SHORT }
    public enum Role { MAIN, HEDGE }

    /** Выгрузка результата в плоских строках */
    public static final class TradeRow {
        public final Long pairId;
        public final String role;     // MAIN | HEDGE
        public final String side;     // LONG | SHORT
        public final Instant entryTime;
        public final BigDecimal entryPrice;
        public final BigDecimal stopPrice;   // MAIN: исходный SL; HEDGE: его SL(1R)
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

    /* ====== ВНУТРЕННИЕ СТРУКТУРЫ ====== */
    private static class PendingPair {
        long pairId;
        int entryIndex;
        Dir mainDir;
        BigDecimal entryPrice;
        BigDecimal mainStop;
        BigDecimal mainQty;
        BigDecimal hedgeQty;
    }
    private static class OpenMain {
        long pairId;
        Dir dir;
        int entryIndex;
        Instant entryTime;
        BigDecimal entryPrice;
        BigDecimal stopPrice;
        BigDecimal qtyBtc;
        boolean armed75=false, armed35=false;
    }
    private static class OpenHedge {
        long pairId;
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

    private static final class QtyPair {
        final BigDecimal mainQty, hedgeQty;
        QtyPair(BigDecimal m, BigDecimal h){ this.mainQty=m; this.hedgeQty=h; }
    }

    /* ===================== ОСНОВНОЙ ПРОХОД ===================== */

    public List<TradeRow> backtest(List<Bar> bars) {
        List<TradeRow> out = new ArrayList<>();
        if (bars == null || bars.size() < 10) return out;

        BigDecimal equity = INITIAL_DEPOSIT.max(BigDecimal.ZERO); // для реинвестирования
        int consecutiveLossPairs = 0;                              // серия убыточных ПАР (MAIN+HEDGE в сумме)

        // контроль по дню
        LocalDate curDay = null;
        int dayStops = 0; // сколько STOP_LOSS (MAIN) сегодня

        PendingPair pending = null;
        OpenMain main = null;
        OpenHedge hedge = null;
        WindowAfterSignal1 w1 = null;
        WindowAfterSignal2 w2 = null;

        long nextPairId = 1L;

        for (int i = 0; i < bars.size(); i++) {
            Bar b = bars.get(i);
            Bar prev = (i > 0) ? bars.get(i-1) : null;

            // смена дня → сбрасываем счётчик дневных стопов
            LocalDate barDay = b.openTime().atZone(ZoneOffset.UTC).toLocalDate();
            if (curDay == null || !curDay.equals(barDay)) {
                curDay = barDay;
                dayStops = 0;
            }

            /* 1) Активация pending по OPEN(i), если не заблокирован день */
            if (pending != null && pending.entryIndex == i) {
                // если лимит достигнут — пропускаем активацию
                if (dayStops >= DAILY_STOP_LIMIT) {
                    pending = null; // игнорируем входы до конца дня
                } else {
                    // MAIN
                    main = new OpenMain();
                    main.pairId     = pending.pairId;
                    main.dir        = pending.mainDir;
                    main.entryIndex = i;
                    main.entryTime  = b.openTime();
                    main.entryPrice = safePrice(pending.entryPrice);
                    main.stopPrice  = safePrice(pending.mainStop);
                    main.qtyBtc     = pending.mainQty;

                    // HEDGE
                    hedge = new OpenHedge();
                    hedge.pairId     = pending.pairId;
                    hedge.dir        = (main.dir == Dir.LONG) ? Dir.SHORT : Dir.LONG;
                    hedge.entryIndex = i;
                    hedge.entryTime  = b.openTime();
                    hedge.entryPrice = main.entryPrice;
                    hedge.qtyBtc     = pending.hedgeQty;

                    BigDecimal R = main.entryPrice.subtract(main.stopPrice).abs();
                    if (main.dir == Dir.LONG) {
                        hedge.tpPrice = main.stopPrice;
                        hedge.slPrice = safePrice(main.entryPrice.add(R));
                    } else {
                        hedge.tpPrice = main.stopPrice;
                        hedge.slPrice = safePrice(main.entryPrice.subtract(R));
                    }
                    pending = null;
                }
            }

            /* 2) Управление открытой парой */
            if (main != null) {

                // приоритет: MAIN SL → HEDGE TP → HEDGE SL → REVERSAL → RSI(2h)

                // 2.1 MAIN SL
                boolean mainClosedThisBar = false;
                if (main.dir == Dir.LONG) {
                    if (b.low().compareTo(main.stopPrice) <= 0) {
                        // закрываем MAIN по SL
                        out.add(buildRow(main.pairId, Role.MAIN, main.dir, main.entryTime, main.entryPrice, main.stopPrice,
                                main.qtyBtc, nextOpenTime(b), main.stopPrice, "STOP_LOSS"));
                        BigDecimal pnlMain = pnlMain(main.dir, main.entryPrice, main.stopPrice, main.qtyBtc);
                        equity = equity.add(pnlMain);
                        dayStops++; // считаем дневной стоп MAIN

                        // HEDGE по TP (равен стопу мейна)
                        if (hedge != null) {
                            out.add(buildRow(hedge.pairId, Role.HEDGE, hedge.dir, hedge.entryTime, hedge.entryPrice, hedge.slPrice,
                                    hedge.qtyBtc, nextOpenTime(b), main.stopPrice, "HEDGE_TP_AT_MAIN_SL"));
                            equity = equity.add(pnlHedge(hedge.dir, hedge.entryPrice, main.stopPrice, hedge.qtyBtc));
                        }
                        // пара закрыта → обновим streak после подсчёта суммарного PnL пары
                        BigDecimal pairPnl = pnlMain;
                        if (hedge != null) {
                            pairPnl = pairPnl.add(pnlHedge(hedge.dir, hedge.entryPrice, main.stopPrice, hedge.qtyBtc));
                        }
                        consecutiveLossPairs = pairPnl.signum() < 0 ? consecutiveLossPairs + 1 : 0;

                        main = null; hedge = null; mainClosedThisBar = true;
                    }
                } else { // SHORT
                    if (b.high().compareTo(main.stopPrice) >= 0) {
                        out.add(buildRow(main.pairId, Role.MAIN, main.dir, main.entryTime, main.entryPrice, main.stopPrice,
                                main.qtyBtc, nextOpenTime(b), main.stopPrice, "STOP_LOSS"));
                        BigDecimal pnlMain = pnlMain(main.dir, main.entryPrice, main.stopPrice, main.qtyBtc);
                        equity = equity.add(pnlMain);
                        dayStops++;

                        if (hedge != null) {
                            out.add(buildRow(hedge.pairId, Role.HEDGE, hedge.dir, hedge.entryTime, hedge.entryPrice, hedge.slPrice,
                                    hedge.qtyBtc, nextOpenTime(b), main.stopPrice, "HEDGE_TP_AT_MAIN_SL"));
                            equity = equity.add(pnlHedge(hedge.dir, hedge.entryPrice, main.stopPrice, hedge.qtyBtc));
                        }
                        BigDecimal pairPnl = pnlMain;
                        if (hedge != null) {
                            pairPnl = pairPnl.add(pnlHedge(hedge.dir, hedge.entryPrice, main.stopPrice, hedge.qtyBtc));
                        }
                        consecutiveLossPairs = pairPnl.signum() < 0 ? consecutiveLossPairs + 1 : 0;

                        main = null; hedge = null; mainClosedThisBar = true;
                    }
                }

                // 2.2 HEDGE TP
                if (!mainClosedThisBar && hedge != null) {
                    boolean hedgeTp = (hedge.dir == Dir.LONG)
                            ? (b.high().compareTo(hedge.tpPrice) >= 0)
                            : (b.low().compareTo(hedge.tpPrice)  <= 0);
                    if (hedgeTp) {
                        out.add(buildRow(hedge.pairId, Role.HEDGE, hedge.dir, hedge.entryTime, hedge.entryPrice, hedge.slPrice,
                                hedge.qtyBtc, nextOpenTime(b), hedge.tpPrice, "HEDGE_TP_AT_MAIN_SL"));
                        equity = equity.add(pnlHedge(hedge.dir, hedge.entryPrice, hedge.tpPrice, hedge.qtyBtc));
                        hedge = null;
                    }
                }

                // 2.3 HEDGE SL
                if (!mainClosedThisBar && hedge != null) {
                    boolean hedgeSl = (hedge.dir == Dir.LONG)
                            ? (b.low().compareTo(hedge.slPrice) <= 0)
                            : (b.high().compareTo(hedge.slPrice) >= 0);
                    if (hedgeSl) {
                        out.add(buildRow(hedge.pairId, Role.HEDGE, hedge.dir, hedge.entryTime, hedge.entryPrice, hedge.slPrice,
                                hedge.qtyBtc, nextOpenTime(b), hedge.slPrice, "HEDGE_SL_1R"));
                        equity = equity.add(pnlHedge(hedge.dir, hedge.entryPrice, hedge.slPrice, hedge.qtyBtc));
                        hedge = null;
                    }
                }

                // 2.4 REVERSAL
                if (main != null) {
                    Dir revDir = detectPairDirectionOnBar(bars, i, prev);
                    if (revDir != null && revDir != main.dir) {
                        Instant xt = nextOpenTime(b);
                        BigDecimal xp = safePrice(b.close());

                        out.add(buildRow(main.pairId, Role.MAIN, main.dir, main.entryTime, main.entryPrice, main.stopPrice,
                                main.qtyBtc, xt, xp, "REVERSAL_CLOSE"));
                        BigDecimal pnlMain = pnlMain(main.dir, main.entryPrice, xp, main.qtyBtc);
                        equity = equity.add(pnlMain);

                        if (hedge != null) {
                            out.add(buildRow(hedge.pairId, Role.HEDGE, hedge.dir, hedge.entryTime, hedge.entryPrice, hedge.slPrice,
                                    hedge.qtyBtc, xt, xp, "PAIR_CLOSE_WITH_MAIN"));
                            equity = equity.add(pnlHedge(hedge.dir, hedge.entryPrice, xp, hedge.qtyBtc));
                        }
                        BigDecimal pairPnl = pnlMain;
                        if (hedge != null) pairPnl = pairPnl.add(pnlHedge(hedge.dir, hedge.entryPrice, xp, hedge.qtyBtc));
                        consecutiveLossPairs = pairPnl.signum() < 0 ? consecutiveLossPairs + 1 : 0;

                        main = null; hedge = null;

                        // постановка нового pending (если не заблокирован день)
                        if (dayStops < DAILY_STOP_LIMIT) {
                            int entryIndex = i + 1;
                            if (entryIndex < bars.size()) {
                                BigDecimal stopRaw = computeStopForEntry(bars, entryIndex, revDir);
                                if (stopRaw != null) {
                                    BigDecimal entryPrice = bars.get(entryIndex).open();
                                    BigDecimal safeStop   = safeStop(stopRaw, revDir);
                                    if (trendOk(bars.get(i), revDir)) { // тренд-фильтр
                                        BigDecimal riskPct = (consecutiveLossPairs >= LOSS_STREAK_RISK_DOWN)
                                                ? RISK_PCT_PER_PAIR_LOW : RISK_PCT_PER_PAIR;
                                        BigDecimal riskUsdt = equity.max(BigDecimal.ZERO).multiply(riskPct);
                                        QtyPair qty = calcQtyForPair(entryPrice, safeStop, riskUsdt);
                                        if (qty.mainQty.compareTo(MIN_QTY) >= 0) {
                                            PendingPair pp = new PendingPair();
                                            pp.pairId     = nextPairId++;
                                            pp.entryIndex = entryIndex;
                                            pp.mainDir    = revDir;
                                            pp.entryPrice = entryPrice;
                                            pp.mainStop   = safeStop;
                                            pp.mainQty    = qty.mainQty;
                                            pp.hedgeQty   = qty.hedgeQty;
                                            pending = pp;
                                        }
                                    }
                                }
                            }
                        }
                        continue; // к RSI не идём
                    }
                }

                // 2.5 RSI (на 2h)
                if (main != null && i >= 4 && is2hBoundary(b.openTime())) {
                    Bar p2h = bars.get(i - 4);
                    BigDecimal r0 = p2h.rsi2h(), s0 = p2h.smaRsi2h();
                    BigDecimal r1 = b.rsi2h(),  s1 = b.smaRsi2h();
                    if (r0 != null && s0 != null && r1 != null && s1 != null) {
                        boolean doExit=false; String reason="RSI_CROSS";
                        if (main.dir == Dir.LONG) {
                            if (r1.compareTo(new BigDecimal("75")) >= 0) main.armed75 = true;
                            boolean cross = r0.compareTo(s0) > 0 && r1.compareTo(s1) <= 0;
                            boolean ex75  = main.armed75 && r1.compareTo(new BigDecimal("75")) < 0;
                            doExit = cross || ex75;
                            reason = cross ? "RSI_CROSS" : "RSI_75_35";
                        } else {
                            if (r1.compareTo(new BigDecimal("35")) <= 0) main.armed35 = true;
                            boolean cross = r0.compareTo(s0) < 0 && r1.compareTo(s1) >= 0;
                            boolean ex35  = main.armed35 && r1.compareTo(new BigDecimal("35")) > 0;
                            doExit = cross || ex35;
                            reason = cross ? "RSI_CROSS" : "RSI_75_35";
                        }
                        if (doExit) {
                            Instant xt = nextOpenTime(b);
                            BigDecimal xp = safePrice(b.close());

                            out.add(buildRow(main.pairId, Role.MAIN, main.dir, main.entryTime, main.entryPrice, main.stopPrice,
                                    main.qtyBtc, xt, xp, reason));
                            BigDecimal pnlMain = pnlMain(main.dir, main.entryPrice, xp, main.qtyBtc);
                            equity = equity.add(pnlMain);

                            if (hedge != null) {
                                out.add(buildRow(hedge.pairId, Role.HEDGE, hedge.dir, hedge.entryTime, hedge.entryPrice, hedge.slPrice,
                                        hedge.qtyBtc, xt, xp, "PAIR_CLOSE_WITH_MAIN"));
                                equity = equity.add(pnlHedge(hedge.dir, hedge.entryPrice, xp, hedge.qtyBtc));
                            }
                            BigDecimal pairPnl = pnlMain;
                            if (hedge != null) pairPnl = pairPnl.add(pnlHedge(hedge.dir, hedge.entryPrice, xp, hedge.qtyBtc));
                            consecutiveLossPairs = pairPnl.signum() < 0 ? consecutiveLossPairs + 1 : 0;

                            main = null; hedge = null;
                        }
                    }
                }
            }

            /* 3) Сигналы и pending (если нет открытой пары и не заблокирован день) */
            if (main == null && pending == null && dayStops < DAILY_STOP_LIMIT) {
                boolean s1Long=false, s1Short=false, s2=false;
                if (prev != null) {
                    s1Long  = prev.ema11().compareTo(prev.ema30()) < 0 && b.ema11().compareTo(b.ema30()) >= 0;
                    s1Short = prev.ema11().compareTo(prev.ema30()) > 0 && b.ema11().compareTo(b.ema30()) <= 0;
                }
                s2 = b.low().compareTo(b.ema110()) <= 0 && b.ema110().compareTo(b.high()) <= 0;

                if (s1Long)  w1 = new WindowAfterSignal1(Dir.LONG,  i, i+2);
                if (s1Short) w1 = new WindowAfterSignal1(Dir.SHORT, i, i+2);
                if (s2)      w2 = new WindowAfterSignal2(i, i+5);

                Dir entryDir = null;
                if (w1 != null && s2 && i <= w1.deadline) {
                    entryDir = w1.dir;
                } else if (w2 != null && i <= w2.deadline && (s1Long || s1Short)) {
                    entryDir = s1Long ? Dir.LONG : Dir.SHORT;
                }

                if (entryDir != null && trendOk(b, entryDir)) { // тренд-фильтр
                    int entryIndex = i + 1;
                    if (entryIndex < bars.size()) {
                        BigDecimal stopRaw = computeStopForEntry(bars, entryIndex, entryDir);
                        if (stopRaw != null) {
                            BigDecimal entryPrice = bars.get(entryIndex).open();
                            BigDecimal safeStop   = safeStop(stopRaw, entryDir);

                            BigDecimal riskPct = (consecutiveLossPairs >= LOSS_STREAK_RISK_DOWN)
                                    ? RISK_PCT_PER_PAIR_LOW : RISK_PCT_PER_PAIR;
                            BigDecimal riskUsdt = equity.max(BigDecimal.ZERO).multiply(riskPct);

                            QtyPair qty = calcQtyForPair(entryPrice, safeStop, riskUsdt);
                            if (qty.mainQty.compareTo(MIN_QTY) >= 0) {
                                PendingPair pp = new PendingPair();
                                pp.pairId     = nextPairId++;
                                pp.entryIndex = entryIndex;
                                pp.mainDir    = entryDir;
                                pp.entryPrice = entryPrice;
                                pp.mainStop   = safeStop;
                                pp.mainQty    = qty.mainQty;
                                pp.hedgeQty   = qty.hedgeQty;
                                pending = pp;
                            }
                        }
                    }
                    w1 = null; w2 = null;
                }

                // Истечение окон
                if (w1 != null && i > w1.deadline) w1 = null;
                if (w2 != null && i > w2.deadline) w2 = null;
            }
        }

        return out;
    }

    /** Определяем, сформировалась ли в баре i пара сигналов противоположного направления.
     *  Возвращаем направление новой пары или null.
     *  Условия:
     *  - s1: кросс EMA11/EMA30 на текущем баре (направление LONG/SHORT)
     *  - s2: цена касается EMA110 (low <= ema110 <= high)
     */
    private static Dir detectPairDirectionOnBar(List<Bar> bars, int i, Bar prev) {
        if (bars == null || i <= 0 || i >= bars.size()) return null;
        Bar b = bars.get(i);
        if (b == null || prev == null) return null;

        // Нужны значения EMA и цены
        if (prev.ema11() == null || prev.ema30() == null ||
                b.ema11() == null || b.ema30() == null ||
                b.ema110() == null || b.low() == null || b.high() == null) {
            return null;
        }

        boolean s1Long  = prev.ema11().compareTo(prev.ema30()) < 0 && b.ema11().compareTo(b.ema30()) >= 0;
        boolean s1Short = prev.ema11().compareTo(prev.ema30()) > 0 && b.ema11().compareTo(b.ema30()) <= 0;
        boolean s2      = b.low().compareTo(b.ema110()) <= 0 && b.ema110().compareTo(b.high()) <= 0;

        if (s1Long && s2)  return Dir.LONG;
        if (s1Short && s2) return Dir.SHORT;
        return null;
    }

    /* ===================== РАСЧЁТ ОБЪЁМОВ (ПАРА) ===================== */

    private QtyPair calcQtyForPair(BigDecimal entry, BigDecimal mainStop, BigDecimal riskUsdt) {
        if (entry == null || mainStop == null || riskUsdt == null || riskUsdt.signum() <= 0) {
            return new QtyPair(BigDecimal.ZERO, BigDecimal.ZERO);
        }
        BigDecimal R = entry.subtract(mainStop).abs();

        // Комиссии на 1 BTC в худшем сценарии
        BigDecimal feesMain = entry.multiply(FEE_MAKER_IN_MAIN)
                .add(mainStop.multiply(FEE_TAKER_OUT_MAIN));

        BigDecimal hedgeExitPrice = (entry.compareTo(mainStop) > 0) ? entry.add(R) : entry.subtract(R);
        BigDecimal feesHedge = entry.multiply(FEE_TAKER_IN_HEDGE)
                .add(hedgeExitPrice.abs().multiply(FEE_TAKER_OUT_HEDGE));

        // Худший убыток на 1 BTC (MAIN 1R + HEDGE 1R с комиссиями, hedge по доле)
        BigDecimal worstLossPer1Btc = R
                .add(feesMain)
                .add(R.add(feesHedge).multiply(HEDGE_RATIO));

        if (worstLossPer1Btc.signum() <= 0) return new QtyPair(BigDecimal.ZERO, BigDecimal.ZERO);

        BigDecimal rawMain = riskUsdt.divide(worstLossPer1Btc, 10, RoundingMode.HALF_UP);
        BigDecimal mainQty = floorToStep(rawMain, STEP_QTY);
        if (mainQty.compareTo(MIN_QTY) < 0) return new QtyPair(BigDecimal.ZERO, BigDecimal.ZERO);

        BigDecimal hedgeQty = floorToStep(mainQty.multiply(HEDGE_RATIO), STEP_QTY);
        if (hedgeQty.compareTo(BigDecimal.ZERO) == 0) hedgeQty = MIN_QTY.min(mainQty);

        return new QtyPair(mainQty, hedgeQty);
    }

    /* ===================== PnL (с комиссиями) ===================== */

    private BigDecimal pnlMain(Dir dir, BigDecimal entry, BigDecimal exit, BigDecimal qty) {
        BigDecimal gross = (dir == Dir.LONG) ? exit.subtract(entry) : entry.subtract(exit);
        BigDecimal pnlGross = gross.multiply(qty);
        BigDecimal feeIn  = entry.multiply(qty).multiply(FEE_MAKER_IN_MAIN);
        BigDecimal feeOut = exit.multiply(qty).multiply(FEE_TAKER_OUT_MAIN);
        return pnlGross.subtract(feeIn).subtract(feeOut).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal pnlHedge(Dir dir, BigDecimal entry, BigDecimal exit, BigDecimal qty) {
        BigDecimal gross = (dir == Dir.LONG) ? exit.subtract(entry) : entry.subtract(exit);
        BigDecimal pnlGross = gross.multiply(qty);
        BigDecimal feeIn  = entry.multiply(qty).multiply(FEE_TAKER_IN_HEDGE);
        BigDecimal feeOut = exit.multiply(qty).multiply(FEE_TAKER_OUT_HEDGE);
        return pnlGross.subtract(feeIn).subtract(feeOut).setScale(2, RoundingMode.HALF_UP);
    }

    /* ===================== СТОП-ЛОГИКА ===================== */

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

        List<BigDecimal> c = new ArrayList<>();
        addIfInZone(c, imp.ema110(), zone1);
        addIfInZone(c, imp.ema200(), zone1);
        for (Integer crossIdx : findCrossIdxInWindow(bars, from, to)) {
            BigDecimal lvl = avg(bars.get(crossIdx).ema11(), bars.get(crossIdx).ema30());
            addIfInZone(c, lvl, zone1);
        }
        if (c.isEmpty()) {
            addIfInZone(c, imp.ema110(), zone2);
            addIfInZone(c, imp.ema200(), zone2);
            for (Integer crossIdx : findCrossIdxInWindow(bars, from, to)) {
                BigDecimal lvl = avg(bars.get(crossIdx).ema11(), bars.get(crossIdx).ema30());
                addIfInZone(c, lvl, zone2);
            }
        }
        if (c.isEmpty()) return stopByTema9Window(bars, entryIndex, dir);

        c.sort(Comparator.naturalOrder());
        BigDecimal min = c.get(0), max = c.get(c.size()-1);
        if (areEquivalent(min, max)) return (dir == Dir.LONG) ? min : max;
        return (dir == Dir.LONG) ? min : max;
    }

    /** LONG — минимум TEMA9, SHORT — максимум TEMA9 в окне [entryIndex-5 .. entryIndex]. */
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

    /* ===================== ФИЛЬТР ТРЕНДА ===================== */

    /** Разрешаем LONG только выше EMA200, SHORT — только ниже EMA200. Если EMA200 нет — не фильтруем. */
    private boolean trendOk(Bar b, Dir dir) {
        if (b == null || b.ema200() == null || b.close() == null) return true;
        int cmp = b.close().compareTo(b.ema200());
        return (dir == Dir.LONG) ? (cmp >= 0) : (cmp <= 0);
    }

    /* ===================== УТИЛИТЫ ===================== */

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

    private boolean areEquivalent(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) return false;
        BigDecimal diff = a.subtract(b).abs();
        BigDecimal mean = a.add(b).divide(new BigDecimal("2"), 10, RoundingMode.HALF_UP);
        if (mean.signum() == 0) return diff.signum() == 0;
        BigDecimal rel = diff.divide(mean, 10, RoundingMode.HALF_UP);
        return rel.compareTo(EQUIV_REL) <= 0;
    }

    private static TradeRow buildRow(long pairId, Role role, Dir dir,
                                     Instant entryTime, BigDecimal entryPrice, BigDecimal stopPrice, BigDecimal qty,
                                     Instant exitTime, BigDecimal exitPrice, String reason) {
        return new TradeRow(
                pairId,
                role.name(),
                dir.name(),
                entryTime,
                safePrice(entryPrice),
                safePrice(stopPrice),
                qty.setScale(3, RoundingMode.HALF_UP),
                exitTime,
                safePrice(exitPrice),
                reason
        );
    }

    private static Instant nextOpenTime(Bar b) { return b.openTime().plus(TF); }

    private static boolean is2hBoundary(Instant ts) {
        ZonedDateTime z = ts.atZone(ZoneOffset.UTC);
        return z.getMinute() == 0 && (z.getHour() % 2 == 0);
    }

    private static BigDecimal safeStop(BigDecimal stop, Dir dir) {
        if (stop == null) return null;
        BigDecimal steps = stop.divide(PRICE_STEP, 0,
                (dir == Dir.LONG) ? RoundingMode.FLOOR : RoundingMode.CEILING);
        return steps.multiply(PRICE_STEP);
    }

    private static BigDecimal safePrice(BigDecimal v) { return v.setScale(2, RoundingMode.HALF_UP); }

    private static BigDecimal floorToStep(BigDecimal value, BigDecimal step) {
        if (value.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;
        BigDecimal steps = value.divide(step, 0, RoundingMode.DOWN);
        return steps.multiply(step);
    }
}


