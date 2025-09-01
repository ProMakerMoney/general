-- Таблицы для основной стратегии (MainStrategy)
CREATE TABLE IF NOT EXISTS main_backtest_trades (
id BIGSERIAL PRIMARY KEY,
entry_time TIMESTAMP NOT NULL, -- UTC
side VARCHAR(5) NOT NULL CHECK (side IN ('LONG','SHORT')),
entry_price NUMERIC(18,2) NOT NULL,
stop_price NUMERIC(18,2) NOT NULL, -- исходный стоп (до перевода в безубыток)
qty_btc NUMERIC(18,3) NOT NULL, -- полный объём позиции


-- TP1 (частичный выход 50%)
tp1_price NUMERIC(18,2), -- nullable, если TP1 не сработал


-- Финальный выход позиции (TP2)
exit_time TIMESTAMP NOT NULL,
exit_price NUMERIC(18,2) NOT NULL,
tp2_price NUMERIC(18,2) NOT NULL, -- = exit_price (для удобства чтения)


reason VARCHAR(32) NOT NULL -- STOP_LOSS | ONLY_TP_1 | RSI_CROSS | RSI_75_35 | REVERSAL_CLOSE
);


CREATE INDEX IF NOT EXISTS idx_main_bt_trades_entry_time ON main_backtest_trades(entry_time);
CREATE INDEX IF NOT EXISTS idx_main_bt_trades_exit_time ON main_backtest_trades(exit_time);
CREATE INDEX IF NOT EXISTS idx_main_bt_trades_reason ON main_backtest_trades(reason);


-- PnL по основной стратегии
CREATE TABLE IF NOT EXISTS main_backtest_pnl (
trade_id BIGINT PRIMARY KEY REFERENCES main_backtest_trades(id) ON DELETE CASCADE,
entry_time TIMESTAMP NOT NULL,
exit_time TIMESTAMP NOT NULL,
side VARCHAR(5) NOT NULL CHECK (side IN ('LONG','SHORT')),
entry_price NUMERIC(18,2) NOT NULL,
stop_price NUMERIC(18,2) NOT NULL,
qty_btc NUMERIC(18,3) NOT NULL,


-- Цены выходов
tp1_price NUMERIC(18,2),
tp2_price NUMERIC(18,2) NOT NULL,


-- PnL по частям (на 50% и 50% объёма)
pnl_tp1 NUMERIC(18,2) NOT NULL,
pnl_tp2 NUMERIC(18,2) NOT NULL,


-- Комиссия как в базовой логике: entry_fee + exit_fee(по tp2_price) на ВЕСЬ объём
fee_total NUMERIC(18,2) NOT NULL,


net_total NUMERIC(18,2) NOT NULL,
reason VARCHAR(32) NOT NULL
);


CREATE INDEX IF NOT EXISTS idx_main_bt_pnl_exit_time ON main_backtest_pnl(exit_time);