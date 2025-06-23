CREATE TABLE IF NOT EXISTS candles (
    id          BIGSERIAL PRIMARY KEY,
    symbol      VARCHAR(20)  NOT NULL,
    timeframe   VARCHAR(5)   NOT NULL,
    open_time   TIMESTAMP    NOT NULL,
    close_time  TIMESTAMP    NOT NULL,
    open        NUMERIC(18,8) NOT NULL,
    high        NUMERIC(18,8) NOT NULL,
    low         NUMERIC(18,8) NOT NULL,
    close       NUMERIC(18,8) NOT NULL,
    volume      NUMERIC(30,12) NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_candles_symbol_tf_open
    ON candles(symbol, timeframe, open_time);
