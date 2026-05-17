-- Local dev seed for BenCloud
-- Safe to run multiple times (idempotent-ish).
--
-- What it does:
-- - Re-applies DB privileges used by the app user (benmap_system)
-- - Ensures population datasets show up in `/api/population-datasets-info` (fills `t_pop_dataset_year`)
-- - Creates a minimal population dataset aligned to the example AQ layers (grid 69)
-- - Creates a minimal crosswalk between grid 69 and grid 18 (identity mapping for shared grid_cell_id values)
--
-- Usage:
--   docker compose exec -T db psql -U postgres -d benmap -f /BenCloudServer/db/dev-seed-local.sql

\set ON_ERROR_STOP on

-- 1) Privileges (needed after DB recreate)
\i /BenCloudServer/db/set_privs.sql

-- 2) Ensure pop years join table has rows (UI depends on it)
INSERT INTO data.t_pop_dataset_year (pop_dataset_id, pop_year)
SELECT DISTINCT pop_dataset_id, pop_year
FROM data.population_entry
ON CONFLICT DO NOTHING;

-- 3) Minimal population config (\"All\" only)
INSERT INTO data.pop_config (id, name)
VALUES (1, 'ALL')
ON CONFLICT (id) DO NOTHING;

INSERT INTO data.age_range (id, pop_config_id, name, start_age, end_age)
VALUES (1, 1, 'All', 0, 200)
ON CONFLICT (id) DO UPDATE
SET pop_config_id = EXCLUDED.pop_config_id,
    name = EXCLUDED.name,
    start_age = EXCLUDED.start_age,
    end_age = EXCLUDED.end_age;

INSERT INTO data.pop_config_race (pop_config_id, race_id)
VALUES (1, 5)
ON CONFLICT DO NOTHING;

INSERT INTO data.pop_config_ethnicity (pop_config_id, ethnicity_id)
VALUES (1, 3)
ON CONFLICT DO NOTHING;

INSERT INTO data.pop_config_gender (pop_config_id, gender_id)
VALUES (1, 3)
ON CONFLICT DO NOTHING;

-- 4) Minimal dataset tied to example AQ grid (69)
-- Ensure grid 69 has its table name set (sometimes missing from patches)
UPDATE data.grid_definition SET table_name = 'grids.grid_69' WHERE id = 69 AND table_name IS NULL;

-- Fix example AQ surfaces to match standard health impact functions
-- PM2.5 example surfaces (3, 4) should use metric 11 (Daily Index) instead of 1 (D24HourMean)
UPDATE data.air_quality_cell SET metric_id = 11 WHERE air_quality_layer_id IN (3, 4) AND metric_id = 1;
UPDATE data.air_quality_layer_metrics SET metric_id = 11 WHERE air_quality_layer_id IN (3, 4) AND metric_id = 1;

INSERT INTO data.population_dataset (id, name, pop_config_id, grid_definition_id, apply_growth)
VALUES (1, 'USA Population TEST DATA', 1, 69, 0)
ON CONFLICT (id) DO UPDATE
SET name = EXCLUDED.name,
    pop_config_id = EXCLUDED.pop_config_id,
    grid_definition_id = EXCLUDED.grid_definition_id,
    apply_growth = EXCLUDED.apply_growth;

INSERT INTO data.population_entry (id, pop_dataset_id, race_id, ethnicity_id, gender_id, age_range_id, pop_year)
VALUES (1, 1, 5, 3, 3, 1, 2020)
ON CONFLICT (id) DO UPDATE
SET pop_dataset_id = EXCLUDED.pop_dataset_id,
    race_id = EXCLUDED.race_id,
    ethnicity_id = EXCLUDED.ethnicity_id,
    gender_id = EXCLUDED.gender_id,
    age_range_id = EXCLUDED.age_range_id,
    pop_year = EXCLUDED.pop_year;

-- Ensure dataset shows up in dataset-info endpoints that join `t_pop_dataset_year`
INSERT INTO data.t_pop_dataset_year (pop_dataset_id, pop_year)
VALUES (1, 2020)
ON CONFLICT DO NOTHING;

-- Values for all grid cells that exist in the example AQ layers (grid 69)
DELETE FROM data.population_value WHERE pop_entry_id = 1;
INSERT INTO data.population_value (pop_entry_id, grid_cell_id, pop_value)
SELECT 1, x.grid_cell_id, 1000.0
FROM (
  SELECT DISTINCT c.grid_cell_id
  FROM data.air_quality_cell c
  JOIN data.air_quality_layer l ON l.id = c.air_quality_layer_id
  WHERE l.grid_definition_id = 69
) x;

-- Growth metadata required by data.get_population() (mat view uses population_growth.base_pop_year)
CREATE TABLE IF NOT EXISTS data.population_growth_b2020
  PARTITION OF data.population_growth FOR VALUES IN ('2020');

INSERT INTO data.population_growth (
  base_pop_year, pop_year, race_id, gender_id, ethnicity_id, age_range_id, grid_cell_id, growth_value
)
SELECT 2020::smallint, 2020::smallint, 5, 3, 3, 1, MIN(grid_cell_id)::integer, 1.0
FROM data.population_value
ON CONFLICT DO NOTHING;

REFRESH MATERIALIZED VIEW data.mat_pop_dataset_base_year;

-- 4b) Make the built-in 2010 county dataset (popId=100) runnable in local dev.
-- Some DB snapshots ship a 2010 county population dataset but don't include complete grid metadata
-- (e.g. missing grid_definition rows), which can make crosswalk generation crash.
-- For local dev robustness we:
-- - Ensure a base_pop_year exists for 2010 so data.get_population() won't throw
-- - Pre-create a simple crosswalk 73 <-> 69 so tasks won't attempt runtime area-weight generation
--
-- NOTE: This crosswalk is DEV ONLY and not spatially meaningful (it maps all county cells onto one AQ cell).

INSERT INTO data.population_growth(base_pop_year,pop_year,race_id,gender_id,ethnicity_id,age_range_id,grid_cell_id,growth_value)
VALUES (2010,2010,5,3,3,1,1,1.0)
ON CONFLICT DO NOTHING;

REFRESH MATERIALIZED VIEW data.mat_pop_dataset_base_year;

-- Create crosswalk_dataset rows if missing
INSERT INTO data.crosswalk_dataset (source_grid_id, target_grid_id, created_date)
SELECT v.source_grid_id, v.target_grid_id, now()
FROM (VALUES (73,69),(69,73)) v(source_grid_id, target_grid_id)
WHERE NOT EXISTS (
  SELECT 1 FROM data.crosswalk_dataset d
  WHERE d.source_grid_id=v.source_grid_id AND d.target_grid_id=v.target_grid_id
);

DO $$
DECLARE
  cw_73_69 integer;
  cw_69_73 integer;
  target_cell bigint;
BEGIN
  -- If popId=100 isn't present in this DB, skip quietly
  IF NOT EXISTS (SELECT 1 FROM data.population_dataset WHERE id=100) THEN
    RETURN;
  END IF;

  SELECT id INTO cw_73_69 FROM data.crosswalk_dataset WHERE source_grid_id=73 AND target_grid_id=69 LIMIT 1;
  SELECT id INTO cw_69_73 FROM data.crosswalk_dataset WHERE source_grid_id=69 AND target_grid_id=73 LIMIT 1;

  SELECT MIN(c.grid_cell_id) INTO target_cell
  FROM data.air_quality_cell c
  JOIN data.air_quality_layer l ON l.id=c.air_quality_layer_id
  WHERE l.grid_definition_id=69;

  IF target_cell IS NULL THEN
    RETURN;
  END IF;

  -- 73 -> 69
  INSERT INTO data.crosswalk_entry(crosswalk_id, source_grid_cell_id, target_grid_cell_id, percentage, target_col, target_row)
  SELECT cw_73_69, v.grid_cell_id, c.grid_cell_id, 1.0, c.grid_col, c.grid_row
  FROM (
    SELECT DISTINCT pv.grid_cell_id
    FROM data.population_value pv
    JOIN data.population_entry pe ON pe.id=pv.pop_entry_id
    WHERE pe.pop_dataset_id=100
  ) v
  CROSS JOIN (
    SELECT DISTINCT c.grid_cell_id, c.grid_col, c.grid_row
    FROM data.air_quality_cell c
    JOIN data.air_quality_layer l ON l.id=c.air_quality_layer_id
    WHERE l.grid_definition_id=69
    LIMIT 1
  ) c
  ON CONFLICT DO NOTHING;

  -- 69 -> 73
  INSERT INTO data.crosswalk_entry(crosswalk_id, source_grid_cell_id, target_grid_cell_id, percentage, source_col, source_row)
  SELECT cw_69_73, c.grid_cell_id, v.grid_cell_id, 1.0, c.grid_col, c.grid_row
  FROM (
    SELECT DISTINCT pv.grid_cell_id
    FROM data.population_value pv
    JOIN data.population_entry pe ON pe.id=pv.pop_entry_id
    WHERE pe.pop_dataset_id=100
    LIMIT 1
  ) v
  CROSS JOIN (
    SELECT DISTINCT c.grid_cell_id, c.grid_col, c.grid_row
    FROM data.air_quality_cell c
    JOIN data.air_quality_layer l ON l.id=c.air_quality_layer_id
    WHERE l.grid_definition_id=69
  ) c
  ON CONFLICT DO NOTHING;
END $$;

-- 5) Minimal crosswalk 69 <-> 18 (identity mapping for the grid_cell_ids we actually use)
WITH existing AS (
  SELECT id, source_grid_id, target_grid_id
  FROM data.crosswalk_dataset
  WHERE (source_grid_id=69 AND target_grid_id=18)
     OR (source_grid_id=18 AND target_grid_id=69)
), ins AS (
  INSERT INTO data.crosswalk_dataset (source_grid_id, target_grid_id, created_date)
  SELECT v.source_grid_id, v.target_grid_id, now()
  FROM (VALUES (69,18),(18,69)) v(source_grid_id, target_grid_id)
  WHERE NOT EXISTS (
    SELECT 1 FROM existing e
    WHERE e.source_grid_id=v.source_grid_id AND e.target_grid_id=v.target_grid_id
  )
  RETURNING id, source_grid_id, target_grid_id
)
SELECT 1;

DO $$
DECLARE
  cw_69_18 integer;
  cw_18_69 integer;
BEGIN
  SELECT id INTO cw_69_18 FROM data.crosswalk_dataset WHERE source_grid_id=69 AND target_grid_id=18 LIMIT 1;
  SELECT id INTO cw_18_69 FROM data.crosswalk_dataset WHERE source_grid_id=18 AND target_grid_id=69 LIMIT 1;

  -- 69 -> 18 (needed by data.get_variable when output grid is 69 and variable source is 18)
  INSERT INTO data.crosswalk_entry (crosswalk_id, source_grid_cell_id, target_grid_cell_id, percentage, source_col, source_row, target_col, target_row)
  SELECT cw_69_18, c.grid_cell_id, c.grid_cell_id, 1.0, c.grid_col, c.grid_row, c.grid_col, c.grid_row
  FROM (
    SELECT DISTINCT c.grid_cell_id, c.grid_col, c.grid_row
    FROM data.air_quality_cell c
    JOIN data.air_quality_layer l ON l.id=c.air_quality_layer_id
    WHERE l.grid_definition_id=69
  ) c
  ON CONFLICT DO NOTHING;

  -- 18 -> 69 (reverse mapping, in case other routines need it)
  INSERT INTO data.crosswalk_entry (crosswalk_id, source_grid_cell_id, target_grid_cell_id, percentage, source_col, source_row, target_col, target_row)
  SELECT cw_18_69, c.grid_cell_id, c.grid_cell_id, 1.0, c.grid_col, c.grid_row, c.grid_col, c.grid_row
  FROM (
    SELECT DISTINCT c.grid_cell_id, c.grid_col, c.grid_row
    FROM data.air_quality_cell c
    JOIN data.air_quality_layer l ON l.id=c.air_quality_layer_id
    WHERE l.grid_definition_id=69
  ) c
  ON CONFLICT DO NOTHING;
END $$;

-- Final: make sure the list endpoint won't be empty
INSERT INTO data.t_pop_dataset_year (pop_dataset_id, pop_year)
VALUES (1, 2020)
ON CONFLICT DO NOTHING;


-- 6) Missing incidence data for example HIFs (ID 10001, 10003)
INSERT INTO data.incidence_entry (id, incidence_dataset_id, year, endpoint_group_id, endpoint_id, race_id, gender_id, start_age, end_age, prevalence, ethnicity_id)
VALUES 
(10004, 100, 2025, 12, 50, 5, 3, 25, 99, false, 3), -- Mortality for HIF 10001
(10005, 100, 2025, 10, 22, 5, 3, 0, 18, false, 3)    -- Asthma for HIF 10003
ON CONFLICT (id) DO UPDATE SET
    start_age = EXCLUDED.start_age,
    end_age = EXCLUDED.end_age,
    endpoint_id = EXCLUDED.endpoint_id;

INSERT INTO data.incidence_value (incidence_entry_id, grid_cell_id, grid_col, grid_row, value)
SELECT 
    ie.id as incidence_entry_id,
    ((g.col::bigint + g.row::bigint) * (g.col::bigint + g.row::bigint + 1) * 0.5) + g.row::bigint as grid_cell_id,
    g.col as grid_col,
    g.row as grid_row,
    CASE
        WHEN ie.endpoint_id = 50 THEN 0.001 + (random() * 0.005)
        ELSE 0.01 + (random() * 0.03)
    END as value
FROM data.incidence_entry ie
CROSS JOIN (SELECT DISTINCT col, row FROM grids.grid_69 WHERE col <= 10 AND row <= 10) g
WHERE ie.id IN (10004, 10005)
ON CONFLICT DO NOTHING;

-- Also fix the existing Entry 10003 which has the wrong age range for the example HIF
UPDATE data.incidence_entry SET start_age = 0, end_age = 18 WHERE id = 10003 AND endpoint_id = 22;

