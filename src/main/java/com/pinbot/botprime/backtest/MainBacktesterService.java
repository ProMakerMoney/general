package com.pinbot.botprime.backtest;

import com.pinbot.botprime.strategy.MainStrategy;
import com.pinbot.botprime.trade.MainBacktestTrade;
import com.pinbot.botprime.trade.MainBacktestTradeRepository;
import com.pinbot.botprime.trade.MainBacktestPnl;
import com.pinbot.botprime.trade.MainBacktestPnlRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.List;

@Service
public class MainBacktesterService {
    private static final Logger log = LoggerFactory.getLogger(MainBacktesterService.class);

    private final IndicatorDao indicatorDao;
    private final MainBacktestTradeRepository tradeRepo;
    private final MainBacktestPnlRepository pnlRepo;
    private final MainStrategy strategy = new MainStrategy();

    public MainBacktesterService(IndicatorDao indicatorDao,
                                 MainBacktestTradeRepository tradeRepo,
                                 MainBacktestPnlRepository pnlRepo) {
        this.indicatorDao = indicatorDao;
        this.tradeRepo = tradeRepo;
        this.pnlRepo = pnlRepo;
    }

    @Transactional
    public String run() {
        // Чистим только таблицы основной стратегии
        pnlRepo.deleteAllInBatch();
        tradeRepo.deleteAllInBatch();

        var bars = indicatorDao.fetchAllBarsAsc();
        if (bars.isEmpty()) {
            log.info("Данных нет. Сделок не создано.");
            return "Обсчитано 0 сделок. Добавлены в таблицу main_backtest_trades.";
        }

        List<MainBacktestTrade> trades = strategy.backtest(bars);
        List<MainBacktestTrade> saved = tradeRepo.saveAll(trades);

        final BigDecimal feePerSide = new BigDecimal("0.00055");

        for (MainBacktestTrade t : saved) {
            BigDecimal qty = t.getQtyBtc();
            BigDecimal entry = t.getEntryPrice();
            BigDecimal tp1   = t.getTp1Price(); // may be null
            BigDecimal tp2   = t.getTp2Price();
            String reason    = t.getReason();

            // Делим объём пополам для расчёта PnL, если TP1 был и объем позволяет
            BigDecimal qty1 = BigDecimal.ZERO; // для TP1
            BigDecimal qty2 = qty;             // остаток
            if (tp1 != null && qty.compareTo(new BigDecimal("0.002")) >= 0) {
                qty1 = floorToStep(qty.divide(new BigDecimal("2"), 10, RoundingMode.DOWN), new BigDecimal("0.001"));
                qty2 = qty.subtract(qty1);
            }

            // PnL по частям
            BigDecimal pnl1 = BigDecimal.ZERO;
            if (tp1 != null && qty1.compareTo(BigDecimal.ZERO) > 0) {
                pnl1 = (isLong(t) ? tp1.subtract(entry) : entry.subtract(tp1)).multiply(qty1);
            }
            pnl1 = scale2(pnl1);

            BigDecimal pnl2 = (isLong(t) ? tp2.subtract(entry) : entry.subtract(tp2)).multiply(qty2);
            pnl2 = scale2(pnl2);

            // Комиссия «как раньше», без дробления: на весь объём по entry и tp2
            BigDecimal entryFee = entry.multiply(qty).multiply(feePerSide);
            BigDecimal exitFee  = tp2  .multiply(qty).multiply(feePerSide);
            BigDecimal feeTotal = scale2(entryFee.add(exitFee));

            BigDecimal net = scale2(pnl1.add(pnl2).subtract(feeTotal));

            MainBacktestPnl p = new MainBacktestPnl();
            p.setTradeId(t.getId());
            p.setEntryTime(t.getEntryTime());
            p.setExitTime(t.getExitTime());
            p.setSide(t.getSide());
            p.setEntryPrice(entry);
            p.setStopPrice(t.getStopPrice());
            p.setQtyBtc(qty);
            p.setTp1Price(tp1);
            p.setTp2Price(tp2);
            p.setPnlTp1(pnl1);
            p.setPnlTp2(pnl2);
            p.setFeeTotal(feeTotal);
            p.setNetTotal(net);
            p.setReason(reason);

            pnlRepo.save(p);
        }

        int n = saved.size();
        log.info("[MAIN] Обсчитано {} сделок. Добавлены в таблицу main_backtest_trades.", n);
        int from = Math.max(0, n - 3);
        List<MainBacktestTrade> tail = n == 0 ? Collections.emptyList() : saved.subList(from, n);
        for (MainBacktestTrade t : tail) {
            log.info("[MAIN] {} {} entry={} stop={} qty={} tp1={} -> exit={} {} ({})",
                    t.getEntryTime(), t.getSide(),
                    t.getEntryPrice(), t.getStopPrice(), t.getQtyBtc(),
                    t.getTp1Price(), t.getExitTime(), t.getExitPrice(), t.getReason());
        }
        return String.format("[MAIN] Обсчитано %d сделок. Добавлены в таблицу main_backtest_trades.", n);
    }

    private static boolean isLong(MainBacktestTrade t) { return "LONG".equals(t.getSide()); }
    private static BigDecimal floorToStep(BigDecimal v, BigDecimal step) {
        if (v.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;
        BigDecimal steps = v.divide(step, 0, RoundingMode.DOWN);
        return steps.multiply(step);
    }
    private static BigDecimal scale2(BigDecimal v) { return v.setScale(2, RoundingMode.HALF_UP); }
}
