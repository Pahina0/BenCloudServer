/*** Fix Missing Grid and Metric Dependencies ***/
UPDATE "data".settings SET value_int=96 where "key"='version';

-- Add missing Grid Definition required by Patch 92
INSERT INTO data.grid_definition (id, name, col_count, row_count) 
VALUES (69, 'Test Grid', 100, 100) 
ON CONFLICT (id) DO NOTHING;

-- Add missing Ozone seasonal metric required by Patch 92
-- Metric ID 2 is D8HourMax (from patch 95)
INSERT INTO data.seasonal_metric (id, metric_id, name) 
VALUES (3, 2, 'WarmSeason_D8HourMax') 
ON CONFLICT (id) DO NOTHING;

-- Update sequences
SELECT SETVAL('data.grid_definition_id_seq', (SELECT MAX(id) FROM data.grid_definition));
SELECT SETVAL('data.seasonal_metric_id_seq', (SELECT MAX(id) FROM data.seasonal_metric));
