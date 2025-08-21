CREATE TABLE IF NOT EXISTS indicator_values (
    symbol VARCHAR(32) NOT NULL,
    timeframe VARCHAR(8) NOT NULL,
    open_time TIMESTAMPTZ NOT NULL,

    ema_short DOUBLE PRECISION,
    ema_long DOUBLE PRECISION,
    ema110 DOUBLE PRECISION,
    ema200 DOUBLE PRECISION,
    tema_smoothed DOUBLE PRECISION,

    PRIMARY KEY (symbol, timeframe, open_time)
);
