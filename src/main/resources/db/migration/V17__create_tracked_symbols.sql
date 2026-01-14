CREATE TABLE IF NOT EXISTS tracked_symbol (
    id          BIGSERIAL PRIMARY KEY,
    symbol      VARCHAR(50)  NOT NULL, -- "BTCUSDT", "ETHUSDT" и т.п. (id монеты/торговая пара)
    name        VARCHAR(100) NOT NULL, -- "Bitcoin", "Ethereum" и т.п.
    timeframe   VARCHAR(20)  NOT NULL, -- "30m", "2h" и т.п.

    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Чтобы не было дублей одной и той же монеты на одном таймфрейме
CREATE UNIQUE INDEX IF NOT EXISTS ux_tracked_symbol_symbol_timeframe
ON tracked_symbol (symbol, timeframe);

-- Поиск ускорим
CREATE INDEX IF NOT EXISTS ix_tracked_symbol_timeframe
ON tracked_symbol (timeframe);
