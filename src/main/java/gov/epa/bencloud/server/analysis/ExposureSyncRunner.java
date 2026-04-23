package gov.epa.bencloud.server.analysis;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Vector;

import org.jooq.Record;
import org.jooq.Result;
import org.mariuszgromada.math.mxparser.mXparser;

import gov.epa.bencloud.api.AirQualityApi;
import gov.epa.bencloud.api.PopulationApi;
import gov.epa.bencloud.api.function.EFunction;
import gov.epa.bencloud.api.model.AirQualityCell;
import gov.epa.bencloud.api.model.AirQualityCellMetric;
import gov.epa.bencloud.api.model.ExposureConfig;
import gov.epa.bencloud.api.model.ExposureTaskConfig;
import gov.epa.bencloud.api.model.ExposureTaskLog;
import gov.epa.bencloud.api.util.ApiUtil;
import gov.epa.bencloud.api.util.ExposureUtil;
import gov.epa.bencloud.server.database.jooq.data.tables.records.ExposureResultRecord;
import gov.epa.bencloud.server.database.jooq.data.tables.records.GetPopulationRecord;
import gov.epa.bencloud.server.tasks.runnable.ExposureTaskRunnable;
import gov.epa.bencloud.server.tasks.model.Task;

/**
 * Runs exposure analysis synchronously in-process.
 *
 * It writes results to the same tables as the async task runner (result dataset + records),
 * but does not interact with TaskQueue/TaskComplete.
 */
public final class ExposureSyncRunner {
	private ExposureSyncRunner() {}

	public static Integer run(Task task, ExposureTaskConfig exposureTaskConfig) {
		final int maxRowsInMemory = 100000;
		int rowsSaved = 0;

		exposureTaskConfig.gridDefinitionId = AirQualityApi.getAirQualityLayerGridId(exposureTaskConfig.aqBaselineId);

		ExposureTaskLog exposureTaskLog = new ExposureTaskLog(exposureTaskConfig, task.getUserIdentifier());
		exposureTaskLog.setDtStart(LocalDateTime.now());
		exposureTaskLog.addMessage("Starting Exposure analysis (sync)");

		// AQ maps
		Map<Long, AirQualityCell> baseline = AirQualityApi.getAirQualityLayerMap(exposureTaskConfig.aqBaselineId, task.getUuid());
		Map<Long, AirQualityCell> scenario = AirQualityApi.getAirQualityLayerMap(exposureTaskConfig.aqScenarioId, task.getUuid());

		ArrayList<EFunction> exposureFunctionList = new ArrayList<>();
		ArrayList<EFunction> complementFunctionList = new ArrayList<>();
		ArrayList<ExposureConfig> complementFunctionConfigs = new ArrayList<>();

		int idx = 0;
		for (ExposureConfig exposureConfig : exposureTaskConfig.exposureFunctions) {
			EFunction f = ExposureUtil.getFunctionForEF(exposureConfig.efId);
			exposureFunctionList.add(f);

			Record e = ExposureUtil.getFunctionDefinition(exposureConfig.efId);
			exposureConfig.efRecord = e.intoMap();
			exposureConfig.efRecord.put("hidden_sort_order", exposureConfig.efRecord.get("population_group"));
			if ("All: Reference (0-99)".equals(exposureConfig.efRecord.get("population_group"))) {
				exposureConfig.efRecord.replace("hidden_sort_order", "00. All: Reference (0-99)");
			}
			updateExposureConfigValues(exposureConfig, e);

			if (Boolean.TRUE.equals(exposureConfig.efRecord.get("generate_complement"))) {
				ExposureConfig complement = new ExposureConfig();
				int complementId = (int) exposureConfig.efId + 10000;
				complement.efInstanceId = (int) exposureConfig.efInstanceId + 10000;
				complement.efId = complementId;
				complement.efRecord = exposureConfig.efRecord;

				EFunction cf = ExposureUtil.getFunctionForEF(exposureConfig.efId);
				complementFunctionList.add(cf);

				Record ce = ExposureUtil.getFunctionDefinition(exposureConfig.efId);
				complement.efRecord = ce.intoMap();
				complement.efRecord.replace("id", complementId);
				if (exposureConfig.efRecord.get("complement_name") != null) {
					complement.efRecord.replace("population_group", exposureConfig.efRecord.get("complement_name"));
				} else {
					complement.efRecord.replace("population_group", ("Non-" + complement.efRecord.get("population_group")));
				}

				complement.efRecord.put("hidden_sort_order", exposureConfig.efRecord.get("population_group") + " - Complement");
				if (exposureConfig.race == 4) {
					complement.efRecord.put("complement_race", 3);
				} else if (exposureConfig.race == 3) {
					complement.efRecord.put("complement_race", 4);
				}
				if (exposureConfig.ethnicity == 2) {
					complement.efRecord.put("complement_ethnicity", 1);
				} else if (exposureConfig.ethnicity == 1) {
					complement.efRecord.put("complement_ethnicity", 2);
				}
				if (exposureConfig.gender == 2) {
					complement.efRecord.put("complement_gender", 1);
				} else if (exposureConfig.gender == 1) {
					complement.efRecord.put("complement_gender", 2);
				}

				updateExposureConfigValues(complement, ce);
				complementFunctionConfigs.add(complement);
			}

			idx++;
		}

		exposureFunctionList.addAll(complementFunctionList);
		exposureTaskConfig.exposureFunctions.addAll(complementFunctionConfigs);

		idx = 0;
		for (ExposureConfig exposureConfig : exposureTaskConfig.exposureFunctions) {
			exposureConfig.arrayIdx = idx++;
		}

		// Reuse the same age-range mapping logic as async runner
		ArrayList<java.util.HashMap<Integer, Double>> exposurePopAgeRangeMapping = ExposureTaskRunnable.getPopAgeRangeMapping(exposureTaskConfig);
		exposureTaskConfig.exposureFunctions.sort(ExposureConfig.ExposureConfigPopulationGroupComparator);

		// Load population (may throw PSQLException from get_population)
		Map<Long, Result<GetPopulationRecord>> populationMap = PopulationApi.getPopulationEntryGroups(exposureTaskConfig, task.getUuid());

		// Variables used by variable exposure functions
		List<Integer> requiredVariableIds = exposureTaskConfig.getRequiredVariableIds();
		Map<Integer, Map<Long, Double>> variables = ApiUtil.getVariableValuesFromIds(requiredVariableIds, exposureTaskConfig.gridDefinitionId);

		int totalCells = baseline.size();
		int currentCell = 0;

		Vector<ExposureResultRecord> exposureResults = new Vector<>(maxRowsInMemory);
		mXparser.setToOverrideBuiltinTokens();
		mXparser.disableUlpRounding();

		for (Entry<Long, AirQualityCell> baselineEntry : baseline.entrySet()) {
			currentCell++;
			AirQualityCell baselineCell = baselineEntry.getValue();
			AirQualityCell scenarioCell = scenario.getOrDefault(baselineEntry.getKey(), null);
			if (scenarioCell == null) {
				continue;
			}

			Result<GetPopulationRecord> populationCell = populationMap.getOrDefault(baselineEntry.getKey(), null);
			if (populationCell == null) {
				continue;
			}

			exposureTaskConfig.exposureFunctions.parallelStream().forEach((exposureConfig) -> {
				EFunction exposureFunction = exposureFunctionList.get(exposureConfig.arrayIdx);

				Map<String, Object> efRecord = exposureConfig.efRecord;
				Map<Integer, Map<Integer, AirQualityCellMetric>> baselineCellMetrics = baselineCell.getCellMetrics();
				Map<Integer, Map<Integer, AirQualityCellMetric>> scenarioCellMetrics = scenarioCell.getCellMetrics();

				// Temporary approach: select the first metric + first seasonal metric for this cell (same as ExposureTaskRunnable)
				Map<Integer, AirQualityCellMetric> baselineCellFirstMetric = baselineCellMetrics.get(baselineCellMetrics.keySet().toArray()[0]);
				Map<Integer, AirQualityCellMetric> scenarioCellFirstMetric = scenarioCellMetrics.get(scenarioCellMetrics.keySet().toArray()[0]);

				double baselineValue = baselineCellFirstMetric.get(baselineCellFirstMetric.keySet().toArray()[0]).getValue();
				double scenarioValue = scenarioCellFirstMetric.get(scenarioCellFirstMetric.keySet().toArray()[0]).getValue();

				double seasonalScalar = 1.0;
				double deltaQ = baselineValue - scenarioValue;

				double v1 = 1.0;
				boolean isVariableFunction = false;
				boolean isComplementFunction = efRecord.get("is_complement") != null ? (boolean) efRecord.get("is_complement") : false;

				if (exposureConfig.variable != null && variables.containsKey(exposureConfig.variable)) {
					isVariableFunction = true;
					v1 = variables.get(exposureConfig.variable).getOrDefault(baselineEntry.getKey(), 0.0);
				}

				double functionEstimate = 0.0;
				double totalSubgroupPop = 0.0;
				double totalAllPop = 0.0;

				java.util.HashMap<Integer, Double> popAgeRangeExposureMap = exposurePopAgeRangeMapping.get(exposureConfig.arrayIdx);

				if (exposureFunction.nativeFunction == null) {
					org.mariuszgromada.math.mxparser.Expression functionExpression = exposureFunction.interpretedFunction;
					functionExpression.setArgumentValue("DELTA", deltaQ);
					functionExpression.setArgumentValue("Q1", baselineValue);
					functionExpression.setArgumentValue("Q0", scenarioValue);
					if (isVariableFunction) {
						functionExpression.setArgumentValue("VARIABLE", isComplementFunction ? (1 - v1) : v1);
					}
				} else {
					exposureFunction.efArguments.deltaQ = deltaQ;
					exposureFunction.efArguments.q1 = baselineValue;
					exposureFunction.efArguments.q0 = scenarioValue;
					if (isVariableFunction) {
						exposureFunction.efArguments.v1 = isComplementFunction ? (1 - v1) : v1;
					}
				}

				for (GetPopulationRecord popCategory : populationCell) {
					Integer popRace = popCategory.getRaceId();
					Integer popEthnicity = popCategory.getEthnicityId();
					Integer popGender = popCategory.getGenderId();
					Integer popAgeRange = popCategory.getAgeRangeId();

					// Set arguments expected by expressions/native functions
					if (isComplementFunction && !isVariableFunction) {
						if (popAgeRangeExposureMap.containsKey(popAgeRange)
								&& (exposureConfig.race == 5 || exposureConfig.race != popRace)
								&& (exposureConfig.ethnicity == 3 || exposureConfig.ethnicity != popEthnicity)
								&& (exposureConfig.gender == 3 || exposureConfig.gender != popGender)) {
							double rangePop = popCategory.getPopValue().doubleValue() * popAgeRangeExposureMap.get(popAgeRange);
							totalSubgroupPop += rangePop;
							if (exposureFunction.nativeFunction == null) {
								exposureFunction.interpretedFunction.setArgumentValue("POPULATION", rangePop);
								functionEstimate += exposureFunction.interpretedFunction.calculate() * seasonalScalar;
							} else {
								exposureFunction.efArguments.population = rangePop;
								functionEstimate += exposureFunction.nativeFunction.calculate(exposureFunction.efArguments) * seasonalScalar;
							}
						}
					} else {
						if (popAgeRangeExposureMap.containsKey(popAgeRange)
								&& (exposureConfig.race == 5 || exposureConfig.race == popRace)
								&& (exposureConfig.ethnicity == 3 || exposureConfig.ethnicity == popEthnicity)
								&& (exposureConfig.gender == 3 || exposureConfig.gender == popGender)) {
							double rangePop = popCategory.getPopValue().doubleValue() * popAgeRangeExposureMap.get(popAgeRange);
							totalSubgroupPop += rangePop;
							if (exposureFunction.nativeFunction == null) {
								exposureFunction.interpretedFunction.setArgumentValue("POPULATION", rangePop);
								functionEstimate += exposureFunction.interpretedFunction.calculate() * seasonalScalar;
							} else {
								exposureFunction.efArguments.population = rangePop;
								functionEstimate += exposureFunction.nativeFunction.calculate(exposureFunction.efArguments) * seasonalScalar;
							}
						}
					}

					totalAllPop += popCategory.getPopValue().doubleValue();
				}

				if (totalSubgroupPop != 0.0) {
					ExposureResultRecord rec = new ExposureResultRecord();
					rec.setGridCellId(baselineEntry.getKey());
					rec.setGridCol(baselineCell.getGridCol());
					rec.setGridRow(baselineCell.getGridRow());
					rec.setExposureFunctionId(exposureConfig.efId);
					rec.setExposureFunctionInstanceId(exposureConfig.efInstanceId);
					rec.setSubgroupPopulation(totalSubgroupPop);

					rec.setAllPopulation(totalAllPop);
					rec.setDeltaAq(deltaQ);
					rec.setBaselineAq(baselineValue);
					rec.setScenarioAq(scenarioValue);
					rec.setResult(functionEstimate);

					synchronized (exposureResults) {
						exposureResults.add(rec);
					}
				}
			});

			// Save partials
			if (exposureResults.size() >= maxRowsInMemory) {
				rowsSaved += exposureResults.size();
				ExposureUtil.storeResults(task, exposureTaskConfig, exposureResults);
				exposureResults.clear();
			}
		}

		rowsSaved += exposureResults.size();
		ExposureUtil.storeResults(task, exposureTaskConfig, exposureResults);
		ExposureUtil.storeAggResults(task, 0);

		exposureTaskLog.addMessage(String.format("Saved %,d results", rowsSaved));
		exposureTaskLog.setSuccess(true);
		exposureTaskLog.setDtEnd(LocalDateTime.now());
		ExposureUtil.storeTaskLog(exposureTaskLog);

		return exposureTaskConfig.resultDatasetId;
	}

	private static void updateExposureConfigValues(ExposureConfig exposureConfig, Record e) {
		if (exposureConfig.startAge == null) {
			exposureConfig.startAge = e.get("start_age", Integer.class);
		}
		if (exposureConfig.endAge == null) {
			exposureConfig.endAge = e.get("end_age", Integer.class);
		}
		if (exposureConfig.race == null) {
			exposureConfig.race = e.get("race_id", Integer.class);
		}
		if (exposureConfig.gender == null) {
			exposureConfig.gender = e.get("gender_id", Integer.class);
		}
		if (exposureConfig.ethnicity == null) {
			exposureConfig.ethnicity = e.get("ethnicity_id", Integer.class);
		}
		if (exposureConfig.variable == null) {
			exposureConfig.variable = e.get("variable_id", Integer.class);
		}
	}

	// Age range mapping logic reused from ExposureTaskRunnable
}

