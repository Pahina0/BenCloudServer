/***Initialize Pollutants***/
UPDATE "data".settings SET value_int=93 where "key"='version';

INSERT INTO "data".pollutant (id, name, friendly_name) VALUES (6, 'PM2.5', 'PM2.5') ON CONFLICT (id) DO NOTHING;
INSERT INTO "data".pollutant (id, name, friendly_name) VALUES (4, 'Ozone', 'Ozone') ON CONFLICT (id) DO NOTHING;
SELECT SETVAL('data.pollutant_id_seq', (SELECT MAX(id) FROM data.pollutant));

-- PM2.5 Metrics
INSERT INTO "data".pollutant_metric (id, pollutant_id, name) VALUES (1, 6, 'D24HourMean') ON CONFLICT (id) DO NOTHING;
-- Ozone Metrics
INSERT INTO "data".pollutant_metric (id, pollutant_id, name) VALUES (2, 4, 'D8HourMax') ON CONFLICT (id) DO NOTHING;
SELECT SETVAL('data.pollutant_metric_id_seq', (SELECT MAX(id) FROM data.pollutant_metric));

-- Seasonal Metrics
INSERT INTO "data".seasonal_metric (id, metric_id, name) VALUES (1, 1, 'QuarterlyMean') ON CONFLICT (id) DO NOTHING;
INSERT INTO "data".seasonal_metric (id, metric_id, name) VALUES (2, 2, 'SeasonalMean') ON CONFLICT (id) DO NOTHING;
SELECT SETVAL('data.seasonal_metric_id_seq', (SELECT MAX(id) FROM data.seasonal_metric));

-- Statistic Types
INSERT INTO "data".statistic_type (id, name) VALUES (1, 'Mean') ON CONFLICT (id) DO NOTHING;
SELECT SETVAL('data.statistic_type_id_seq', (SELECT MAX(id) FROM data.statistic_type));
