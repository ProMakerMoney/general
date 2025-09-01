package com.pinbot.botprime.backtest;

import com.pinbot.botprime.strategy.MainProStrategy;
import com.pinbot.botprime.trade.MainProBacktestPnl;
import com.pinbot.botprime.trade.MainProBacktestPnlRepository;
import com.pinbot.botprime.trade.MainProBacktestTrade;
import com.pinbot.botprime.trade.MainProBacktestTradeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;

@Service
public class MainProBacktesterService {

    private static final Logger log = LoggerFactory.getLogger(MainProBacktesterService.class);

    // Комиссия: 0.055% на вход и 0.055% на выход (taker + taker)
    private static final BigDecimal FEE_PER_SIDE = new BigDecimal("0.00055");

    private final IndicatorDao indicatorDao;
    private final MainProBacktestTradeRepository tradeRepo;
    private final MainProBacktestPnlRepository pnlRepo;

    public MainProBacktesterService(IndicatorDao indicatorDao,
                                    MainProBacktestTradeRepository tradeRepo,
                                    MainProBacktestPnlRepository pnlRepo) {
        this.indicatorDao = indicatorDao;
        this.tradeRepo = tradeRepo;
        this.pnlRepo = pnlRepo;
    }

    @Transactional
    public String run() {
        // чистим только таблицы MainPRO
        pnlRepo.deleteAllInBatch();
        tradeRepo.deleteAllInBatch();

        var bars = indicatorDao.fetchAllBarsAsc();
        if (bars.isEmpty()) {
            log.info("[MAINPRO] Данных нет.");
            return "[MAINPRO] Обсчитано 0 сделок.";
        }

        MainProStrategy strat = new MainProStrategy();
        List<MainProStrategy.TradeRow> rows = strat.backtest(bars);

        // Сохраняем trades + PnL
        int n = 0;
        for (MainProStrategy.TradeRow r : rows) {
            MainProBacktestTrade t = new MainProBacktestTrade();
            t.setPairId(r.pairId);
            t.setRole(r.role);
            t.setSide(r.side);
            t.setEntryTime(r.entryTime);
            t.setEntryPrice(r.entryPrice);
            t.setStopPrice(r.stopPrice);
            t.setQtyBtc(r.qtyBtc);
            t.setExitTime(r.exitTime);
            t.setExitPrice(r.exitPrice);
            t.setReason(r.reason);

            t = tradeRepo.save(t);
            n++;

            // PnL по каждой строке
            MainProBacktestPnl p = new MainProBacktestPnl();
            p.setTradeId(t.getId());
            p.setPairId(t.getPairId());
            p.setRole(t.getRole());
            p.setSide(t.getSide());
            p.setEntryTime(t.getEntryTime());
            p.setExitTime(t.getExitTime());
            p.setEntryPrice(t.getEntryPrice());
            p.setStopPrice(t.getStopPrice());
            p.setQtyBtc(t.getQtyBtc());

            BigDecimal gross = isLong(t)
                    ? t.getExitPrice().subtract(t.getEntryPrice())
                    : t.getEntryPrice().subtract(t.getExitPrice());
            gross = gross.multiply(t.getQtyBtc()).setScale(2, RoundingMode.HALF_UP);

            BigDecimal entryFee = t.getEntryPrice().multiply(t.getQtyBtc()).multiply(FEE_PER_SIDE);
            BigDecimal exitFee  = t.getExitPrice().multiply(t.getQtyBtc()).multiply(FEE_PER_SIDE);
            BigDecimal feeTotal = entryFee.add(exitFee).setScale(2, RoundingMode.HALF_UP);
            BigDecimal net      = gross.subtract(feeTotal).setScale(2, RoundingMode.HALF_UP);

            p.setPnlGross(gross);
            p.setFeeTotal(feeTotal);
            p.setPnlNet(net);
            p.setReason(t.getReason());

            pnlRepo.save(p);
        }

        log.info("[MAINPRO] Обсчитано {} сделок. Добавлены в mainpro_backtest_trades.", n);

        // последние 3 строки для быстрого контроля
        tradeRepo.findAll().stream()
                .sorted(Comparator.comparingLong(MainProBacktestTrade::getId))
                .skip(Math.max(0, n - 3))
                .forEach(t -> log.info("[MAINPRO] pair={} {} {} entry={} stop={} qty={} -> exit={} {} ({})",
                        t.getPairId(), t.getRole(), t.getSide(),
                        t.getEntryPrice(), t.getStopPrice(), t.getQtyBtc(),
                        t.getExitPrice(), t.getExitTime(), t.getReason()));

        return String.format("[MAINPRO] Обсчитано %d сделок. Добавлены в таблицу mainpro_backtest_trades.", n);
    }

    private static boolean isLong(MainProBacktestTrade t) { return "LONG".equals(t.getSide()); }
}
