-- V7: привести типы индикаторных колонок к DOUBLE PRECISION
-- (Hibernate ожидает float(53) для полей типа Double)

ALTER TABLE public.indicator_values
    ALTER COLUMN ema11     TYPE DOUBLE PRECISION USING ema11::double precision,
    ALTER COLUMN ema30     TYPE DOUBLE PRECISION USING ema30::double precision,
    ALTER COLUMN ema110    TYPE DOUBLE PRECISION USING ema110::double precision,
    ALTER COLUMN ema200    TYPE DOUBLE PRECISION USING ema200::double precision,
    ALTER COLUMN tema9     TYPE DOUBLE PRECISION USING tema9::double precision,
    ALTER COLUMN rsi2h     TYPE DOUBLE PRECISION USING rsi2h::double precision,
    ALTER COLUMN sma_rsi2h TYPE DOUBLE PRECISION USING sma_rsi2h::double precision;

-- Зафиксируем дефолты для «прогрева»
ALTER TABLE public.indicator_values
    ALTER COLUMN ema11     SET DEFAULT -1,
    ALTER COLUMN ema30     SET DEFAULT -1,
    ALTER COLUMN ema110    SET DEFAULT -1,
    ALTER COLUMN ema200    SET DEFAULT -1,
    ALTER COLUMN tema9     SET DEFAULT -1,
    ALTER COLUMN rsi2h     SET DEFAULT -1,
    ALTER COLUMN sma_rsi2h SET DEFAULT -1;
