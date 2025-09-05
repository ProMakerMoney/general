package com.pinbot.botprime.backtest;

import com.pinbot.botprime.strategy.FirstStrategy;
import com.pinbot.botprime.strategy.Strategy;
import com.pinbot.botprime.trade.BacktestPnl;
import com.pinbot.botprime.trade.BacktestPnlRepository;
import com.pinbot.botprime.trade.BacktestTrade;
import com.pinbot.botprime.trade.BacktestTradeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.List;

@Service
public class BacktesterService {
    private static final Logger log = LoggerFactory.getLogger(BacktesterService.class);

    private final IndicatorDao indicatorDao;
    private final BacktestTradeRepository tradeRepo;
    private final BacktestPnlRepository pnlRepo;
    private final FirstStrategy strategy;   // <-- 1. инжектим бин

    public BacktesterService(IndicatorDao indicatorDao,
                             BacktestTradeRepository tradeRepo,
                             BacktestPnlRepository pnlRepo,
                             FirstStrategy strategy) {   // <-- 2. конструктор
        this.indicatorDao = indicatorDao;
        this.tradeRepo = tradeRepo;
        this.pnlRepo = pnlRepo;
        this.strategy = strategy;
    }

    @Transactional
    public String run() {
        // очищаем только старые таблицы
        pnlRepo.deleteAllInBatch();
        tradeRepo.deleteAllInBatch();

        var bars = indicatorDao.fetchAllBarsAsc();
        if (bars.isEmpty()) {
            log.info("Данных нет. Сделок не создано.");
            return "Обсчитано 0 сделок. Добавлены в таблицу backtest_trades.";
        }

        // прогон стратегии (внутри она проставит CROSS / is_impulse)
        List<BacktestTrade> trades = strategy.backtest(bars);


        // Сохранить сделки и получить их с id
        List<BacktestTrade> saved = tradeRepo.saveAll(trades);

        // Рассчитать и сохранить PnL для каждой сделки
        final BigDecimal feePerSide = new BigDecimal("0.00055"); // 0.055% за вход и за выход
        for (BacktestTrade t : saved) {
            BigDecimal qty        = t.getQtyBtc();
            BigDecimal entryPrice = t.getEntryPrice();
            BigDecimal exitPrice  = t.getExitPrice();

            BigDecimal entryFee = entryPrice.multiply(qty).multiply(feePerSide);
            BigDecimal exitFee  = exitPrice .multiply(qty).multiply(feePerSide);
            BigDecimal feeTotal = scale2(entryFee.add(exitFee));

            BigDecimal gross;
            if ("LONG".equals(t.getSide())) {
                gross = exitPrice.subtract(entryPrice).multiply(qty);
            } else {
                gross = entryPrice.subtract(exitPrice).multiply(qty);
            }
            gross = scale2(gross);

            BigDecimal net = scale2(gross.subtract(feeTotal));

            BacktestPnl p = new BacktestPnl();
            p.setTradeId(t.getId());
            p.setEntryTime(t.getEntryTime());
            p.setExitTime(t.getExitTime());
            p.setSide(t.getSide());
            p.setEntryPrice(entryPrice);
            p.setExitPrice(exitPrice);
            p.setQtyBtc(qty);
            p.setFeeTotal(feeTotal);
            p.setGross(gross);
            p.setNet(net);

            pnlRepo.save(p);
        }

        int n = saved.size();
        log.info("Обсчитано {} сделок. Добавлены в таблицу backtest_trades.", n);

        // Показать последние 3 сделки
        int from = Math.max(0, n - 3);
        List<BacktestTrade> tail = n == 0 ? Collections.emptyList() : saved.subList(from, n);
        for (BacktestTrade t : tail) {
            log.info("{} {} entry={} stop={} qty={} -> exit={} {}",
                    t.getEntryTime(), t.getSide(),
                    t.getEntryPrice(), t.getStopPrice(), t.getQtyBtc(),
                    t.getExitTime(), t.getExitPrice());
        }

        return String.format("Обсчитано %d сделок. Добавлены в таблицу backtest_trades.", n);
    }

    private static BigDecimal scale2(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
