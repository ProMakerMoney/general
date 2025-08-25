-- V6: indicator_values под IndicatorValueEntity

CREATE TABLE public.indicator_values (
    -- ключ
    symbol        VARCHAR(32)  NOT NULL,
    timeframe     VARCHAR(8)   NOT NULL,
    open_time     TIMESTAMP    NOT NULL,

    -- поля свечи
    open          NUMERIC(18,8) NOT NULL,
    high          NUMERIC(18,8) NOT NULL,
    low           NUMERIC(18,8) NOT NULL,
    close         NUMERIC(18,8) NOT NULL,
    volume        NUMERIC(18,8) NOT NULL,
    quote_volume  NUMERIC(18,8) NOT NULL,

    -- индикаторы (по умолчанию -1 для «разогрева»/агрегации)
    ema11         NUMERIC(18,8) NOT NULL DEFAULT -1,
    ema30         NUMERIC(18,8) NOT NULL DEFAULT -1,
    ema110        NUMERIC(18,8) NOT NULL DEFAULT -1,
    ema200        NUMERIC(18,8) NOT NULL DEFAULT -1,
    tema9         NUMERIC(18,8) NOT NULL DEFAULT -1,
    rsi2h         NUMERIC(18,8) NOT NULL DEFAULT -1,
    sma_rsi2h     NUMERIC(18,8) NOT NULL DEFAULT -1,

    CONSTRAINT pk_indicator_values PRIMARY KEY (symbol, timeframe, open_time)
);

-- PK (symbol, timeframe, open_time) покрывает типовой запрос
-- WHERE symbol=? AND timeframe=? ORDER BY open_time