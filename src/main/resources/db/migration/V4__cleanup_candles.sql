-- 1) Удаляем всё лишнее, что могло накопиться:
ALTER TABLE candles DROP COLUMN IF EXISTS id;
ALTER TABLE candles DROP COLUMN IF EXISTS interval;
ALTER TABLE candles DROP COLUMN IF EXISTS timestamp;

-- 2) Добавляем quote_volume, если его нет:
ALTER TABLE candles
  ADD COLUMN IF NOT EXISTS quote_volume numeric(16,8) NOT NULL DEFAULT 0;

-- 3) Меняем первичный ключ на составной (symbol, timeframe, open_time):
--    Сначала сбрасываем старый PK (обычно называется candles_pkey)
ALTER TABLE candles
  DROP CONSTRAINT IF EXISTS candles_pkey;

--    Затем устанавливаем новый
ALTER TABLE candles
  ADD PRIMARY KEY (symbol, timeframe, open_time);
