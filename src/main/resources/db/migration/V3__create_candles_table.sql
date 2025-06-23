CREATE TABLE candles (
    time          BIGINT      PRIMARY KEY,
    open          NUMERIC(18,8) NOT NULL,
    high          NUMERIC(18,8) NOT NULL,
    low           NUMERIC(18,8) NOT NULL,
    close         NUMERIC(18,8) NOT NULL,
    volume        NUMERIC(18,8) NOT NULL,
    quote_volume  NUMERIC(18,8) NOT NULL
);
