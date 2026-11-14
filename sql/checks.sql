-- Run against the configured lab database immediately after a fresh setup.
SELECT throwIf((SELECT count() FROM dim_realty) != 1000, 'dim_realty count');
SELECT throwIf((SELECT count() FROM dim_users FINAL) != 1000, 'dim_users count');
SELECT throwIf((SELECT count() FROM dim_gauges) != 1100, 'dim_gauges count');
SELECT throwIf((SELECT count() FROM fact_invoice) != 36000, 'invoice count');
SELECT throwIf((SELECT count() FROM fact_meter_readings FINAL) != 1096000, 'reading count');
SELECT throwIf((SELECT count() FROM dim_tariffs FINAL WHERE id = 1) != 2, 'SCD2 intervals');
SELECT throwIf((SELECT count() FROM fact_gauge_bindings FINAL) != 1000, 'active binding count');
SELECT throwIf((SELECT count() FROM fact_gauge_bindings FINAL WHERE version >= 2) != 100, 'replacement count');
SELECT throwIf(count() != 0, 'object/date coverage') FROM (
    SELECT realty_id FROM fact_meter_readings FINAL GROUP BY realty_id
    HAVING count() != 1096 OR min(reading_date) != '2023-11-01' OR max(reading_date) != '2026-10-31'
);
SELECT throwIf(count() != 0, 'multiple active gauges') FROM (
    SELECT realty_id FROM fact_gauge_bindings FINAL GROUP BY realty_id HAVING count() != 1
);
SELECT throwIf(count() != 0, 'historical invoice price') FROM fact_invoice
WHERE price != toUInt64(day_kw) * if(created_at < '2025-11-01 00:00:00', 500, 620)
             + toUInt64(night_kw) * if(created_at < '2025-11-01 00:00:00', 250, 310);
