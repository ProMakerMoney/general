CREATE TABLE IF NOT EXISTS backtest_trades (
    id           BIGSERIAL PRIMARY KEY,
    entry_time   TIMESTAMP NOT NULL,             -- UTC
    side         VARCHAR(5) NOT NULL CHECK (side IN ('LONG','SHORT')),
    entry_price  NUMERIC(18,2) NOT NULL,
    stop_price   NUMERIC(18,2) NOT NULL,
    qty_btc      NUMERIC(18,3) NOT NULL,
    exit_time    TIMESTAMP NOT NULL,             -- UTC
    exit_price   NUMERIC(18,2) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_backtest_trades_entry_time ON backtest_trades(entry_time);
CREATE INDEX IF NOT EXISTS idx_backtest_trades_exit_time  ON backtest_trades(exit_time);
