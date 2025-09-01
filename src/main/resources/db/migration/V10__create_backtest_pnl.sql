CREATE TABLE IF NOT EXISTS backtest_pnl (
trade_id BIGINT PRIMARY KEY REFERENCES backtest_trades(id) ON DELETE CASCADE,
entry_time TIMESTAMP NOT NULL,
exit_time TIMESTAMP NOT NULL,
side VARCHAR(5) NOT NULL CHECK (side IN ('LONG','SHORT')),
entry_price NUMERIC(18,2) NOT NULL,
exit_price NUMERIC(18,2) NOT NULL,
qty_btc NUMERIC(18,3) NOT NULL,
fee_total NUMERIC(18,2) NOT NULL,
gross NUMERIC(18,2) NOT NULL,
net NUMERIC(18,2) NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_backtest_pnl_exit_time ON backtest_pnl(exit_time);