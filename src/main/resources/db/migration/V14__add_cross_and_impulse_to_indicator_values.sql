-- Новые поля для indicator_values
ALTER TABLE public.indicator_values
    ADD COLUMN "CROSS"      DOUBLE PRECISION,
    ADD COLUMN is_impulse   BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN public.indicator_values."CROSS"    IS 'Цена пересечения EMA11/EMA30 на баре (если кросс был между i-1 и i; линейная интерполяция)';
COMMENT ON COLUMN public.indicator_values.is_impulse IS 'Импульсная свеча (детектится кодом)';
