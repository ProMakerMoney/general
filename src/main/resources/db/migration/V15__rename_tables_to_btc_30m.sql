-- V15__rename_tables_to_btc_30m.sql
-- Только переименования таблиц. Никаких изменений колонок/индексов/констрейнтов.

-- История (candles) -> btc_30m_history
ALTER TABLE IF EXISTS public.candles                RENAME TO btc_30m_history;

-- Индикаторы (indicator_values) -> btc_30m_indicators
ALTER TABLE IF EXISTS public.indicator_values       RENAME TO btc_30m_indicators;

-- Живые сделки (trades) -> btc_30m_trades
ALTER TABLE IF EXISTS public.trades                 RENAME TO btc_30m_trades;

-- Бэктест (обычный)
ALTER TABLE IF EXISTS public.backtest_trades        RENAME TO btc_30m_backtest_trades;
ALTER TABLE IF EXISTS public.backtest_pnl           RENAME TO btc_30m_backtest_pnl;

-- Бэктест MAIN
ALTER TABLE IF EXISTS public.main_backtest_trades   RENAME TO btc_30m_main_backtest_trades;
ALTER TABLE IF EXISTS public.main_backtest_pnl      RENAME TO btc_30m_main_backtest_pnl;

-- Бэктест MAINPRO (пары)
ALTER TABLE IF EXISTS public.mainpro_backtest_trades RENAME TO btc_30m_mainpro_backtest_trades;
ALTER TABLE IF EXISTS public.mainpro_backtest_pnl    RENAME TO btc_30m_mainpro_backtest_pnl;

-- (НЕ обязательно) если хочешь, можно переименовать связанные последовательности BIGSERIAL:
-- ALTER SEQUENCE IF EXISTS public.backtest_trades_id_seq       RENAME TO btc_30m_backtest_trades_id_seq;
-- ALTER SEQUENCE IF EXISTS public.main_backtest_trades_id_seq  RENAME TO btc_30m_main_backtest_trades_id_seq;
-- ALTER SEQUENCE IF EXISTS public.mainpro_backtest_trades_id_seq RENAME TO btc_30m_mainpro_backtest_trades_id_seq;
-- ... добавь по факту, если такие seq у тебя есть. Это косметика — работоспособность не влияет.
