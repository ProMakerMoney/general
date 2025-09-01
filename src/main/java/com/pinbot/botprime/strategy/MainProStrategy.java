package com.pinbot.botprime.strategy;

import com.pinbot.botprime.backtest.IndicatorDao.Bar;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * MainPRO (на основе FirstStrategy + Hedge):
 * - Входы: пара S1/S2 как в FirstStrategy. Направление задаёт S1.
 * - Вход по OPEN следующего 30m бара.
 * - Стоп MAIN = TEMA9 на свече (entryIndex - 5) — строго по значению той свечи.
 *   (Если данных < 5 баров до входа или tema9=null — вход пропускаем.)
 * - Выход MAIN:
 *    * SL по цене стопа (интрабар), фиксация по next OPEN.
 *    * RSI только на 2h: кросс rsi/sma_rsi, или 75->ниже/35->выше; фиксация по next OPEN ценой close.
 *    * Переворот: если на текущем баре сформировалась пара противоположного направления —
 *      текущую пару (MAIN+HEDGE) закрываем reason=REVERSAL_CLOSE по close, time=next OPEN,
 *      и планируем новый вход на i+1.
 * - Hedge:
 *    * Открывается одновременно с MAIN, противоположная сторона, объём = объёму MAIN.
 *    * TP(HEDGE) = стоп MAIN (тот же уровень).
 *    * SL(HEDGE) = 1R от входа в противоположную сторону (симметрия).
 *    * Никаких RSI — только свой SL/TP.
 *    * Если MAIN закрывается по RSI/REVERSAL — HEDGE закрываем немедленно той же ценой/временем (PAIR_CLOSE_WITH_MAIN).
 * - Приоритет событий внутри бара: MAIN SL → HEDGE TP → HEDGE SL → REVERSAL → RSI(2h).
 * - В момент времени может быть открыта только одна пара (MAIN+HEDGE).
 * - Все executions фиксируются по времени next OPEN текущего бара.
 */
public class MainProStrategy {

    // Риск/комиссия/шаг — как согласовано
    private static final BigDecimal RISK_USDT = new BigDecimal("100");
    private static final BigDecimal FEE_RATE  = new BigDecimal("0.0011"); // 0.055% * 2 (вход+выход)
    private static final BigDecimal MIN_QTY   = new BigDecimal("0.001");
    private static final BigDecimal STEP_QTY  = new BigDecimal("0.001");
    private static final Duration   TF        = Duration.ofMinutes(30);

    public enum Dir  { LONG, SHORT }
    public enum Role { MAIN, HEDGE }

    /** Плоская строка для сохранения сделок сервисом */
    public static final class TradeRow {
        public final Long pairId;
        public final String role;     // MAIN | HEDGE
        public final String side;     // LONG | SHORT
        public final Instant entryTime;
        public final BigDecimal entryPrice;
        public final BigDecimal stopPrice;   // MAIN: tema9[i-5]; HEDGE: его SL(1R)
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
        BigDecimal mainStop;  // tema9[entryIndex-5]
        BigDecimal qtyBtc;
        Long pairId;
    }
    private static class OpenMain {
        Dir dir;
        int entryIndex;
        Instant entryTime;
        BigDecimal entryPrice;
        BigDecimal stopPrice; // tema9[i-5], фиксированный
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
                // Приоритет внутри бара:
                // 2.1 MAIN SL → 2.2 HEDGE TP → 2.3 HEDGE SL → 2.4 REVERSAL → 2.5 RSI(2h)

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

                // 2.2 HEDGE TP? (формально совпадает со стопом мейна; если MAIN ещё жив, TP не должен сработать)
                if (!mainClosedThisBar && hedge != null) {
                    boolean hedgeTp = (hedge.dir == Dir.LONG)
                            ? (b.high().compareTo(hedge.tpPrice) >= 0)
                            : (b.low().compareTo(hedge.tpPrice)  <= 0);
                    if (hedgeTp) {
                        out.add(buildRow(nextPairId, Role.HEDGE, hedge.dir, hedge.entryTime, hedge.entryPrice, hedge.slPrice,
                                hedge.qtyBtc, nextOpenTime(b), hedge.tpPrice, "HEDGE_TP_AT_MAIN_SL"));
                        // MAIN остаётся жить — но этот случай практически невозможен без пробития стопа MAIN
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

                // 2.4 REVERSAL? (если пара противоположного направления сформировалась на текущем баре)
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

                        // Планируем новый вход на i+1 под revDir (если хватит данных на стоп tema9[i+1-5])
                        int entryIndex = i + 1;
                        if (entryIndex < bars.size() && entryIndex - 5 >= 0) {
                            BigDecimal t9 = bars.get(entryIndex - 5).tema9();
                            if (t9 != null) {
                                BigDecimal entryPrice = bars.get(entryIndex).open();
                                BigDecimal qty = calcQty(entryPrice, t9);
                                if (qty.compareTo(MIN_QTY) >= 0) {
                                    pending = new PendingPair();
                                    pending.entryIndex = entryIndex;
                                    pending.mainDir    = revDir;
                                    pending.entryPrice = entryPrice;
                                    pending.mainStop   = t9;
                                    pending.qtyBtc     = qty;
                                    pending.pairId     = nextPairId++;
                                }
                            }
                        }
                        continue; // к RSI уже не идём — пара закрыта
                    }
                }

                // 2.5 RSI только на 2h границах (если пара всё ещё открыта)
                if (main != null && i >= 4 && is2hBoundary(b.openTime())) {
                    Bar prev2h = bars.get(i - 4);
                    BigDecimal rPrev = prev2h.rsi2h();
                    BigDecimal sPrev = prev2h.smaRsi2h();
                    BigDecimal r     = b.rsi2h();
                    BigDecimal s     = b.smaRsi2h();
                    if (rPrev != null && sPrev != null && r != null && s != null) {
                        boolean doExit = false; String reason = "RSI_CROSS";
                        if (main.dir == Dir.LONG) {
                            if (!main.armed75 && r.compareTo(new BigDecimal("75")) >= 0) main.armed75 = true;
                            boolean crossDown = rPrev.compareTo(sPrev) > 0 && r.compareTo(s) <= 0;
                            boolean armed75Ex = main.armed75 && r.compareTo(new BigDecimal("75")) < 0;
                            doExit = crossDown || armed75Ex;
                            reason = crossDown ? "RSI_CROSS" : "RSI_75_35";
                        } else {
                            if (!main.armed35 && r.compareTo(new BigDecimal("35")) <= 0) main.armed35 = true;
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
                    if (entryIndex < bars.size() && entryIndex - 5 >= 0) {
                        BigDecimal t9 = bars.get(entryIndex - 5).tema9();
                        if (t9 != null) {
                            BigDecimal entryPrice = bars.get(entryIndex).open();
                            BigDecimal qty = calcQty(entryPrice, t9);
                            if (qty.compareTo(MIN_QTY) >= 0) {
                                pending = new PendingPair();
                                pending.entryIndex = entryIndex;
                                pending.mainDir    = entryDir;
                                pending.entryPrice = entryPrice;
                                pending.mainStop   = t9;         // строго tema9[i-5]
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

    // ==== helpers ====
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
        // защита от нуля/ошибочного стопа
        if (delta.compareTo(new BigDecimal("0.01")) < 0) return BigDecimal.ZERO;

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

        // Проверяем обе комбинации на том же баре (строгое окно: s1→s2 и s2→s1)
        if (s1Long && s2)  return Dir.LONG;
        if (s1Short && s2) return Dir.SHORT;

        // Для s2→s1 на одном баре это вряд ли; используем строго как выше
        return null;
    }
}
