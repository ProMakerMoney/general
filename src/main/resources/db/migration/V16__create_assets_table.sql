CREATE TABLE IF NOT EXISTS assets (
    id SERIAL PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL,
    timeframe VARCHAR(10) NOT NULL,
    is_active BOOLEAN DEFAULT TRUE,
    added_at TIMESTAMP DEFAULT now(),
    updated_at TIMESTAMP,
    comment TEXT,
    UNIQUE (symbol, timeframe)
);
