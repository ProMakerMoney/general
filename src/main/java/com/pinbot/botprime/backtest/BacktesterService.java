package com.pinbot.botprime.backtest;


import com.pinbot.botprime.strategy.FirstStrategy;
import com.pinbot.botprime.strategy.Strategy;
import com.pinbot.botprime.trade.BacktestTrade;
import com.pinbot.botprime.trade.BacktestTradeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.util.Collections;
import java.util.List;


@Service
public class BacktesterService {
    private static final Logger log = LoggerFactory.getLogger(BacktesterService.class);


    private final IndicatorDao indicatorDao;
    private final BacktestTradeRepository tradeRepo;
    private final Strategy strategy = new FirstStrategy(); // можно внедрить как бин при желании


    public BacktesterService(IndicatorDao indicatorDao, BacktestTradeRepository tradeRepo) {
        this.indicatorDao = indicatorDao;
        this.tradeRepo = tradeRepo;
    }


    @Transactional
    public String run() {
// Очистка таблицы
        tradeRepo.deleteAllInBatch();


// Данные
        var bars = indicatorDao.fetchAllBarsAsc();
        if (bars.isEmpty()) {
            log.info("Данных нет. Сделок не создано.");
            return "Обсчитано 0 сделок. Добавлены в таблицу backtest_trades.";
        }


// Бэктест стратегии
        List<BacktestTrade> trades = strategy.backtest(bars);


// Сохранить сделки
        tradeRepo.saveAll(trades);


        int n = trades.size();
        log.info("Обсчитано {} сделок. Добавлены в таблицу backtest_trades.", n);


// Показать последние 3 сделки
        int from = Math.max(0, n - 3);
        List<BacktestTrade> tail = n == 0 ? Collections.emptyList() : trades.subList(from, n);
        for (BacktestTrade t : tail) {
            log.info("{} {} entry={} stop={} qty={} -> exit={} {}",
                    t.getEntryTime(), t.getSide(),
                    t.getEntryPrice(), t.getStopPrice(), t.getQtyBtc(),
                    t.getExitTime(), t.getExitPrice());
        }


        return String.format("Обсчитано %d сделок. Добавлены в таблицу backtest_trades.", n);
    }
}