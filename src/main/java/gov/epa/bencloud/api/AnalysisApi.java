package gov.epa.bencloud.api;

import static gov.epa.bencloud.server.database.jooq.data.Tables.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jooq.DSLContext;
import org.jooq.JSONFormat;
import org.jooq.Record;
import org.jooq.Record18;
import org.jooq.Result;
import org.jooq.Table;
import org.jooq.JSONFormat.RecordFormat;
import org.jooq.impl.DSL;
import org.pac4j.core.profile.UserProfile;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import gov.epa.bencloud.api.model.ExposureTaskConfig;
import gov.epa.bencloud.server.analysis.ExposureSyncRunner;
import gov.epa.bencloud.server.database.JooqUtil;
import gov.epa.bencloud.server.database.jooq.data.tables.records.GetExposureResultsRecord;
import gov.epa.bencloud.server.tasks.model.Task;
import gov.epa.bencloud.server.util.ParameterUtil;
import spark.Request;
import spark.Response;

public class AnalysisApi {
	private static final ObjectMapper objectMapper = new ObjectMapper();

	/**
	 * Synchronous exposure analysis.
	 *
	 * Accepts an {@link ExposureTaskConfig} JSON body and returns a JSON payload containing:
	 * - taskUuid (string)
	 * - resultDatasetId (number)
	 * - elapsedMs (number)
	 * - filteredRecordsCount (number; count for returned query, not full dataset)
	 * - records (array; paged)
	 */
	public static Object postExposureAnalysisSync(Request request, Response response, Optional<UserProfile> userProfile) {
		Instant start = Instant.now();

		ExposureTaskConfig cfg;
		try {
			cfg = objectMapper.readValue(request.body(), ExposureTaskConfig.class);
		} catch (JsonProcessingException e) {
			return CoreApi.getErrorResponseBadRequest(request, response);
		}

		// Defaults / normalization
		if (cfg.name == null || cfg.name.isBlank()) {
			cfg.name = "Exposure analysis";
		}
		cfg.gridDefinitionId = AirQualityApi.getAirQualityLayerGridId(cfg.aqBaselineId);

		String taskUuid = UUID.randomUUID().toString();
		Task task = new Task();
		task.setUuid(taskUuid);
		task.setType("Exposure");
		task.setName(cfg.name);
		task.setUserIdentifier(userProfile.map(UserProfile::getId).orElse("anonymous"));
		try {
			task.setParameters(objectMapper.writeValueAsString(cfg));
		} catch (JsonProcessingException e) {
			return CoreApi.getErrorResponse(request, response, 400, "Unable to serialize exposure config");
		}

		// Run analysis synchronously (writes dataset + results)
		Integer datasetId;
		try {
			datasetId = ExposureSyncRunner.run(task, cfg);
		} catch (Exception e) {
			return CoreApi.getErrorResponse(request, response, 400, e.getMessage());
		}

		// Return a paged view of results for immediate consumption
		String efIdsParam = ParameterUtil.getParameterValueAsString(request.raw().getParameter("efId"), "");
		List<Integer> efIds = (efIdsParam == null || efIdsParam.isBlank())
				? null
				: Stream.of(efIdsParam.split(",")).map(String::trim).filter(s -> !s.isBlank()).mapToInt(Integer::parseInt).boxed()
						.collect(Collectors.toList());

		int gridId = ParameterUtil.getParameterValueAsInteger(request.raw().getParameter("gridId"),
				ExposureApi.getBaselineGridForExposureResults(datasetId));
		int page = ParameterUtil.getParameterValueAsInteger(request.raw().getParameter("page"), 1);
		int rowsPerPage = ParameterUtil.getParameterValueAsInteger(request.raw().getParameter("rowsPerPage"), 5000);

		if (page < 1) {
			return CoreApi.getErrorResponse(request, response, 400, "page must be >= 1");
		}
		int maxRowsPerPage = 5000;
		if (rowsPerPage < 1) {
			return CoreApi.getErrorResponse(request, response, 400, "rowsPerPage must be >= 1");
		}
		if (rowsPerPage > maxRowsPerPage) {
			return CoreApi.getErrorResponse(request, response, 413, "rowsPerPage too large (max " + maxRowsPerPage + ")");
		}

		DSLContext create = DSL.using(JooqUtil.getJooqConfiguration(taskUuid));
		CrosswalksApi.ensureCrosswalkExists(ExposureApi.getBaselineGridForExposureResults(datasetId), gridId);

		Table<GetExposureResultsRecord> efResultRecords = create
				.selectFrom(GET_EXPOSURE_RESULTS(datasetId, efIds == null ? null : efIds.toArray(new Integer[0]), gridId))
				.asTable("ef_result_records");

		Result<Record18<Integer, Integer, String, String, Integer, Integer, String, String, String, String, String, Double, Double, Double, Double, Double, Double, Double>> efRecords = create
				.select(efResultRecords.field(GET_EXPOSURE_RESULTS.GRID_COL).as("column"),
						efResultRecords.field(GET_EXPOSURE_RESULTS.GRID_ROW).as("row"),
						EXPOSURE_RESULT_FUNCTION_CONFIG.POPULATION_GROUP, EXPOSURE_RESULT_FUNCTION_CONFIG.HIDDEN_SORT_ORDER,
						EXPOSURE_RESULT_FUNCTION_CONFIG.START_AGE, EXPOSURE_RESULT_FUNCTION_CONFIG.END_AGE,
						EXPOSURE_RESULT_FUNCTION_CONFIG.FUNCTION_TYPE, RACE.NAME.as("race"),
						ETHNICITY.NAME.as("ethnicity"), GENDER.NAME.as("gender"), VARIABLE_ENTRY.NAME.as("variable"),
						efResultRecords.field(GET_EXPOSURE_RESULTS.DELTA_AQ),
						efResultRecords.field(GET_EXPOSURE_RESULTS.BASELINE_AQ),
						efResultRecords.field(GET_EXPOSURE_RESULTS.SCENARIO_AQ),
						DSL.when(efResultRecords.field(GET_EXPOSURE_RESULTS.BASELINE_AQ).eq(0.0), 0.0)
								.otherwise(efResultRecords.field(GET_EXPOSURE_RESULTS.DELTA_AQ)
										.div(efResultRecords.field(GET_EXPOSURE_RESULTS.BASELINE_AQ)).times(100.0))
								.as("delta_aq_percent"),
						efResultRecords.field(GET_EXPOSURE_RESULTS.SUBGROUP_POPULATION),
						efResultRecords.field(GET_EXPOSURE_RESULTS.ALL_POPULATION),
						DSL.when(efResultRecords.field(GET_EXPOSURE_RESULTS.ALL_POPULATION).eq(0.0), 0.0)
								.otherwise(efResultRecords.field(GET_EXPOSURE_RESULTS.SUBGROUP_POPULATION)
										.div(efResultRecords.field(GET_EXPOSURE_RESULTS.ALL_POPULATION)).times(100.0))
								.as("percent_of_population"))
				.from(efResultRecords)
				.leftJoin(EXPOSURE_FUNCTION)
				.on(efResultRecords.field(GET_EXPOSURE_RESULTS.EXPOSURE_FUNCTION_ID).eq(EXPOSURE_FUNCTION.ID))
				.join(EXPOSURE_RESULT_FUNCTION_CONFIG)
				.on(EXPOSURE_RESULT_FUNCTION_CONFIG.EXPOSURE_RESULT_DATASET_ID.eq(datasetId)
						.and(EXPOSURE_RESULT_FUNCTION_CONFIG.EXPOSURE_FUNCTION_ID
								.eq(efResultRecords.field(GET_EXPOSURE_RESULTS.EXPOSURE_FUNCTION_ID)))
						.and(EXPOSURE_RESULT_FUNCTION_CONFIG.EXPOSURE_FUNCTION_INSTANCE_ID
								.eq(efResultRecords.field(GET_EXPOSURE_RESULTS.EXPOSURE_FUNCTION_INSTANCE_ID))))
				.leftJoin(RACE).on(EXPOSURE_RESULT_FUNCTION_CONFIG.RACE_ID.eq(RACE.ID))
				.join(ETHNICITY).on(EXPOSURE_RESULT_FUNCTION_CONFIG.ETHNICITY_ID.eq(ETHNICITY.ID))
				.join(GENDER).on(EXPOSURE_RESULT_FUNCTION_CONFIG.GENDER_ID.eq(GENDER.ID))
				.leftJoin(VARIABLE_ENTRY).on(EXPOSURE_RESULT_FUNCTION_CONFIG.VARIABLE_ID.eq(VARIABLE_ENTRY.ID))
				.orderBy(efResultRecords.field(GET_EXPOSURE_RESULTS.GRID_COL).asc(),
						efResultRecords.field(GET_EXPOSURE_RESULTS.GRID_ROW).asc(),
						EXPOSURE_RESULT_FUNCTION_CONFIG.HIDDEN_SORT_ORDER.asc())
				.offset((page * rowsPerPage) - rowsPerPage)
				.limit(rowsPerPage)
				.fetch();

		ObjectNode out = objectMapper.createObjectNode();
		out.put("taskUuid", taskUuid);
		out.put("resultDatasetId", datasetId);
		out.put("elapsedMs", Duration.between(start, Instant.now()).toMillis());
		out.put("filteredRecordsCount", efRecords.size());

		// jOOQ already shapes records; serialize into JSON array
		String recordsJson = efRecords.formatJSON(new JSONFormat().header(false).recordFormat(RecordFormat.OBJECT));
		try {
			out.set("records", objectMapper.readTree(recordsJson));
		} catch (JsonProcessingException e) {
			// fallback: return without records
		}

		response.type("application/json");
		return out;
	}
}

