-- MainPRO: сделки и pnl (по две строки на пару: MAIN и HEDGE)

CREATE TABLE IF NOT EXISTS mainpro_backtest_trades (
    id           BIGSERIAL PRIMARY KEY,
    pair_id      BIGINT      NOT NULL,                           -- связь двух строк пары
    role         VARCHAR(6)  NOT NULL CHECK (role IN ('MAIN','HEDGE')),
    side         VARCHAR(5)  NOT NULL CHECK (side IN ('LONG','SHORT')),
    entry_time   TIMESTAMP   NOT NULL,                           -- UTC
    entry_price  NUMERIC(18,2) NOT NULL,
    stop_price   NUMERIC(18,2) NOT NULL,                         -- для MAIN: TEMA9; для HEDGE: SL(1R)
    qty_btc      NUMERIC(18,3) NOT NULL,

    exit_time    TIMESTAMP   NOT NULL,
    exit_price   NUMERIC(18,2) NOT NULL,

    reason       VARCHAR(32) NOT NULL                            -- см. enum причин в коде
);

CREATE INDEX IF NOT EXISTS idx_mainpro_trades_pair_role ON mainpro_backtest_trades(pair_id, role);
CREATE INDEX IF NOT EXISTS idx_mainpro_trades_entry_time ON mainpro_backtest_trades(entry_time);
CREATE INDEX IF NOT EXISTS idx_mainpro_trades_exit_time  ON mainpro_backtest_trades(exit_time);

CREATE TABLE IF NOT EXISTS mainpro_backtest_pnl (
    trade_id     BIGINT PRIMARY KEY REFERENCES mainpro_backtest_trades(id) ON DELETE CASCADE,
    pair_id      BIGINT      NOT NULL,
    role         VARCHAR(6)  NOT NULL CHECK (role IN ('MAIN','HEDGE')),
    side         VARCHAR(5)  NOT NULL CHECK (side IN ('LONG','SHORT')),

    entry_time   TIMESTAMP   NOT NULL,
    exit_time    TIMESTAMP   NOT NULL,

    entry_price  NUMERIC(18,2) NOT NULL,
    stop_price   NUMERIC(18,2) NOT NULL,
    qty_btc      NUMERIC(18,3) NOT NULL,

    pnl_gross    NUMERIC(18,2) NOT NULL,
    fee_total    NUMERIC(18,2) NOT NULL,
    pnl_net      NUMERIC(18,2) NOT NULL,

    reason       VARCHAR(32) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_mainpro_pnl_pair_role ON mainpro_backtest_pnl(pair_id, role);
CREATE INDEX IF NOT EXISTS idx_mainpro_pnl_exit_time ON mainpro_backtest_pnl(exit_time);
