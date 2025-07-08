-- файл: src/main/resources/db/migration/V5__widen_candle_numeric.sql

ALTER TABLE candles
  ALTER COLUMN volume      TYPE numeric(18,8),
  ALTER COLUMN quote_volume TYPE numeric(18,8);
