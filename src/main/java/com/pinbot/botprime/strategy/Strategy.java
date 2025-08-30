package com.pinbot.botprime.strategy;


import com.pinbot.botprime.backtest.IndicatorDao.Bar;
import com.pinbot.botprime.trade.BacktestTrade;


import java.util.List;


public interface Strategy {
    List<BacktestTrade> backtest(List<Bar> bars);
}
