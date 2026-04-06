/*** Create fake data for test grid with health incidence data and health impact functions ***/
UPDATE "data".settings SET value_int=98 where "key"='version';

-- Ensure basic data exists
INSERT INTO data.race (id, name) VALUES (5, 'All') ON CONFLICT (id) DO NOTHING;
INSERT INTO data.gender (id, name) VALUES (3, 'All') ON CONFLICT (id) DO NOTHING;
INSERT INTO data.ethnicity (id, name) VALUES (3, 'All') ON CONFLICT (id) DO NOTHING;
SELECT SETVAL('data.race_id_seq', (SELECT MAX(id) FROM data.race));
SELECT SETVAL('data.gender_id_seq', (SELECT MAX(id) FROM data.gender));
SELECT SETVAL('data.ethnicity_id_seq', (SELECT MAX(id) FROM data.ethnicity));

INSERT INTO data.endpoint_group (id, name, share_scope) VALUES 
(1, 'Incidence, Neurological', 1),
(2, 'Incidence, Respiratory', 1),
(10, 'Asthma', 1),
(12, 'Mortality', 1),
(13, 'School Loss Days', 1)
ON CONFLICT (id) DO NOTHING;
SELECT SETVAL('data.endpoint_group_id_seq', (SELECT MAX(id) FROM data.endpoint_group));

INSERT INTO data.endpoint (id, endpoint_group_id, name, display_name) VALUES 
(1, 1, 'Incidence, Neurological', 'Incidence, Neurological'),
(2, 2, 'Incidence, Respiratory', 'Incidence, Respiratory'),
(22, 10, 'Asthma', 'Asthma'),
(36, 13, 'School Loss Days', 'School Loss Days'),
(50, 12, 'Mortality, All Cause', 'Mortality, All Cause')
ON CONFLICT (id) DO NOTHING;
SELECT SETVAL('data.endpoint_id_seq', (SELECT MAX(id) FROM data.endpoint));

INSERT INTO data.pollutant_metric (id, pollutant_id, name) VALUES 
(11, 6, 'Daily Index'),
(8, 4, 'D24HourMean')
ON CONFLICT (id) DO NOTHING;
SELECT SETVAL('data.pollutant_metric_id_seq', (SELECT MAX(id) FROM data.pollutant_metric));

INSERT INTO data.timing_type (id, name) VALUES 
(1, 'Annual'),
(2, 'Daily')
ON CONFLICT (id) DO NOTHING;
SELECT SETVAL('data.timing_type_id_seq', (SELECT MAX(id) FROM data.timing_type));

UPDATE data.grid_definition 
SET is_admin_layer = 'Y',
    draw_priority = 1,
    outline_color = '#000000'
WHERE id = 69 AND name = 'Test Grid';

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.tables 
                   WHERE table_schema = 'grids' 
                   AND table_name = 'grid_69') THEN
        CREATE TABLE grids.grid_69 (
            col int4 NOT NULL,
            row int4 NOT NULL,
            geom geometry(POLYGON, 4326) NULL
        );
        
CREATE INDEX grid_69_geom_idx ON grids.grid_69 USING gist (geom);
        
INSERT INTO grids.grid_69 (col, row, geom)
        SELECT 
            c as col,
            r as row,
ST_MakeEnvelope(
        -100.0 + (c * 0.1),
        30.0 + (r * 0.1),
        -100.0 + ((c + 1) * 0.1),
        30.0 + ((r + 1) * 0.1),
        4326
      ) as geom
        FROM generate_series(1, 10) as c,
             generate_series(1, 10) as r;
    END IF;
END $$;

INSERT INTO data.incidence_dataset (id, name, grid_definition_id, share_scope)
VALUES (100, 'Test Incidence Dataset', 69, 1)
ON CONFLICT (id) DO NOTHING;

SELECT SETVAL('data.incidence_dataset_id_seq', (SELECT MAX(id) FROM data.incidence_dataset));

INSERT INTO data.incidence_entry (id, incidence_dataset_id, year, endpoint_group_id, endpoint_id, race_id, gender_id, start_age, end_age, prevalence, ethnicity_id)
VALUES 
(10001, 100, 2025, 1, 1, 5, 3, 0, 99, false, 3),
(10002, 100, 2025, 2, 2, 5, 3, 18, 65, false, 3),
(10003, 100, 2025, 10, 22, 5, 3, 65, 99, false, 3)
ON CONFLICT (id) DO NOTHING;

SELECT SETVAL('data.incidence_entry_id_seq', (SELECT MAX(id) FROM data.incidence_entry));

INSERT INTO data.incidence_value (incidence_entry_id, grid_cell_id, grid_col, grid_row, value)
SELECT 
    ie.id as incidence_entry_id,
    ((g.col::bigint + g.row::bigint) * (g.col::bigint + g.row::bigint + 1) * 0.5) + g.row::bigint as grid_cell_id,
    g.col as grid_col,
    g.row as grid_row,
CASE
    WHEN ie.endpoint_id = 1 THEN 0.05 + (random() * 0.1)
    WHEN ie.endpoint_id = 2 THEN 0.02 + (random() * 0.05)
    ELSE 0.01 + (random() * 0.03)
  END as value
FROM data.incidence_entry ie
CROSS JOIN (SELECT DISTINCT col, row FROM grids.grid_69 WHERE col <= 10 AND row <= 10) g
WHERE ie.incidence_dataset_id = 100;

INSERT INTO data.health_impact_function_dataset (id, name)
VALUES (100, 'Test Health Impact Functions')
ON CONFLICT (id) DO NOTHING;

SELECT SETVAL('data.health_impact_function_dataset_id_seq', (SELECT MAX(id) FROM data.health_impact_function_dataset));

INSERT INTO data.health_impact_function (
    id, health_impact_function_dataset_id, endpoint_group_id, endpoint_id, pollutant_id, 
    metric_id, seasonal_metric_id, metric_statistic, author, function_year, location, 
    other_pollutants, qualifier, reference, start_age, end_age, function_text, 
    beta, dist_beta, 
    p1_beta, p2_beta, val_a, name_a, val_b, name_b, val_c, name_c, baseline_function_text, 
    race_id, gender_id, ethnicity_id, start_day, end_day, share_scope, timing_id
)
VALUES 
(10001, 100, 12, 50, 6, 11, NULL, 1, 'Test Author', 2025, 'Test Location', 
 NULL, 'Test qualifier for mortality', 'Test reference', 25, 99, 
 '(1-(1/exp(BETA*DELTAQ)))*INCIDENCE*POPULATION', 
 0.01, 'Normal', 0.005, 0.0, 
 0.0, NULL, 0.0, NULL, 0.0, NULL, 'INCIDENCE*POPULATION', 
 5, 3, 3, NULL, NULL, 1, 1),

(10002, 100, 2, 2, 6, 11, NULL, 1, 'Test Author', 2025, 'Test Location', 
 NULL, 'Test qualifier for hospitalization', 'Test reference', 18, 99, 
 '(1-(1/((1-INCIDENCE)*exp(BETA*DELTAQ)+INCIDENCE)))*INCIDENCE*POPULATION*A', 
 0.02, 'Normal', 0.008, 0.0, 
 0.98, 'Survival rate', 0.0, NULL, 0.0, NULL, 'INCIDENCE*POPULATION*A', 
 5, 3, 3, NULL, NULL, 1, 1),

(10003, 100, 10, 22, 6, 11, NULL, 1, 'Test Author', 2025, 'Test Location', 
 NULL, 'Test qualifier for asthma', 'Test reference', 0, 18, 
 '(1-(1/exp(BETA*DELTAQ)))*INCIDENCE*POPULATION', 
 0.015, 'Normal', 0.007, 0.0, 
 0.0, NULL, 0.0, NULL, 0.0, NULL, 'INCIDENCE*POPULATION', 
 5, 3, 3, NULL, NULL, 1, 1),

(10004, 100, 13, 36, 4, 8, NULL, 1, 'Test Author', 2025, 'Test Location', 
 NULL, 'Test qualifier for ozone', 'Test reference', 5, 18, 
 '(1-(1/exp(BETA*DELTAQ)))*INCIDENCE*POPULATION*A', 
 0.008, 'Normal', 0.004, 0.0, 
 0.39, 'School days scalar', 0.945, 'Population at-risk', 0.0, NULL, 'INCIDENCE*POPULATION*A', 
 5, 3, 3, 120, 272, 1, 1)
ON CONFLICT (id) DO UPDATE SET
    health_impact_function_dataset_id = EXCLUDED.health_impact_function_dataset_id,
    endpoint_group_id = EXCLUDED.endpoint_group_id,
    endpoint_id = EXCLUDED.endpoint_id,
    pollutant_id = EXCLUDED.pollutant_id,
    metric_id = EXCLUDED.metric_id,
    author = EXCLUDED.author,
    function_text = EXCLUDED.function_text,
    share_scope = EXCLUDED.share_scope,
    timing_id = EXCLUDED.timing_id;

SELECT SETVAL('data.health_impact_function_id_seq', (SELECT MAX(id) FROM data.health_impact_function));

INSERT INTO data.health_impact_function_group_member (health_impact_function_group_id, health_impact_function_id)
VALUES 
(1, 10001),
(1, 10002),
(1, 10003),
(1, 10004)
ON CONFLICT DO NOTHING;
