ALTER TABLE main_backtest_trades
  ADD COLUMN IF NOT EXISTS stop_source VARCHAR(12),   -- TEMA9 | EMA110 | CROSS
  ADD COLUMN IF NOT EXISTS impulse     BOOLEAN;       -- true, если выбор стопа делали в «импульсном» режиме