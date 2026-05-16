package gov.epa.bencloud.api;

import static gov.epa.bencloud.server.database.jooq.data.Tables.*;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.servlet.MultipartConfigElement;
import javax.servlet.http.Part;

import org.jooq.DSLContext;
import org.jooq.JSONFormat;
import org.jooq.Record1;
import org.jooq.Record3;
import org.jooq.Record4;
import org.jooq.Result;
import org.jooq.JSONFormat.RecordFormat;
import org.jooq.impl.DSL;
import org.pac4j.core.profile.UserProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opencsv.CSVReader;

import gov.epa.bencloud.api.model.ExposureConfig;
import gov.epa.bencloud.api.model.ExposureTaskConfig;
import gov.epa.bencloud.api.model.HIFConfig;
import gov.epa.bencloud.api.model.HIFTaskConfig;
import gov.epa.bencloud.api.model.ValidationMessage;
import gov.epa.bencloud.api.util.ApiUtil;
import gov.epa.bencloud.server.database.JooqUtil;
import gov.epa.bencloud.server.database.jooq.data.Routines;
import gov.epa.bencloud.server.database.jooq.data.tables.records.GetPopulationRecord;
import gov.epa.bencloud.server.database.jooq.data.tables.records.PopulationDatasetRecord;
import gov.epa.bencloud.server.database.jooq.data.tables.records.PopulationEntryRecord;
import spark.Request;
import spark.Response;

/*
 * Methods related to population data
 */
public class PopulationApi {

	/**
	 * 
	 * @param hifTaskConfig
	 * @return a map of population entry groups, with key = grid cell id,
	 * 			value = population records
	 */
	public static Map<Long, Result<GetPopulationRecord>> getPopulationEntryGroups(HIFTaskConfig hifTaskConfig, String taskUuid) {

		Integer aqGrid = AirQualityApi.getAirQualityLayerGridId(hifTaskConfig.aqBaselineId);
		
		// Get the array of age ranges to include based on the configured hifs
		ArrayList<Integer> ageRangeIds = getAgeRangesForHifs(hifTaskConfig);
        Integer arrAgeRangeIds[] = new Integer[ageRangeIds.size()];
        arrAgeRangeIds = ageRangeIds.toArray(arrAgeRangeIds);
        
        //Get array of race, ethnicity and gender to include based on the configured hifs
        //TODO: If all hifs calls for "all" or null, set groupby = false. Will the values in lookup table stay forever? 
        ArrayList<Integer> raceIds = getRacesForHifs(hifTaskConfig);
        Integer arrRaceIds[] = new Integer[raceIds.size()];
        arrRaceIds = raceIds.toArray(arrRaceIds);
        boolean booGroupByRace = false;  //1ASIAN, 2BLACK, 3NATAMER, 4WHITE, 5All, 6null  
		for(Integer race : raceIds) {
        	if(race != 5) {
        		booGroupByRace = true;
        		break;
        	}
        }
        
        ArrayList<Integer> ethnicityIds = getEthnicityForHifs(hifTaskConfig);
        Integer arrEthnicityIds[] = new Integer[ethnicityIds.size()];
        arrEthnicityIds = ethnicityIds.toArray(arrEthnicityIds);
        boolean booGroupByEthnicity = false;  //1NON-HISP, 2HISP, 3All, 4null       
		for(Integer ethnicity : ethnicityIds) {
        	if(ethnicity != 3) {
        		booGroupByEthnicity = true;
        		break;
        	}
        }
        
        ArrayList<Integer> genderIds = getGendersForHifs(hifTaskConfig);
        Integer arrGenderIds[] = new Integer[genderIds.size()];
        arrGenderIds = genderIds.toArray(arrGenderIds);
        boolean booGroupByGender = false; //1F, 2M, 3All, 4null 
		for(Integer gender : genderIds) {
        	if(gender != 3) {
        		booGroupByGender = true;
        		break;
        	}
        }

		//If the crosswalk isn't there, create it now
		CrosswalksApi.ensureCrosswalkExists(getPopulationGridDefinitionId(hifTaskConfig.popId),aqGrid);

		// Use .trasaction to ensure work_mem setting applies to the get_population transaction.
		// Variables inside a lambda must be final or effectively final
		Integer[] arrAgeRangeIdsFinal = arrAgeRangeIds;
		boolean booGroupByRaceFinal = booGroupByRace;
		boolean booGroupByEthnicityFinal = booGroupByEthnicity;
		boolean booGroupByGenderFinal = booGroupByGender;
		Map<Long, Result<GetPopulationRecord>> popRecords = DSL.using(JooqUtil.getJooqConfiguration(taskUuid))
		.transactionResult(ctx -> {
			DSLContext dsl = DSL.using(ctx);
			dsl.execute("SET LOCAL work_mem = '2097151kB'");
			return Routines.getPopulation(dsl.configuration(), 
				hifTaskConfig.popId, 
				hifTaskConfig.popYear,
				null, //arrRaceIds, 
				null, //arrEthnicityIds, 
				null, //arrGenderIds, 
				arrAgeRangeIdsFinal, 
				booGroupByRaceFinal, 
				booGroupByEthnicityFinal, 
				booGroupByGenderFinal, 
				true, //groupbyAgeRange
				aqGrid //outputGridDefinitionId
				).intoGroups(GET_POPULATION.GRID_CELL_ID);
		});
		return popRecords;
		
	}

	/**
	 * 
	 * @param exposureTaskConfig
	 * @return a map of population entry groups, with key = grid cell id,
	 * 			value = population records
	 */
	public static Map<Long, Result<GetPopulationRecord>> getPopulationEntryGroups(ExposureTaskConfig exposureTaskConfig, String taskUuid) {

		Integer aqGrid = AirQualityApi.getAirQualityLayerGridId(exposureTaskConfig.aqBaselineId);
		
		// Get the array of age ranges to include based on the configured exposure functions
		ArrayList<Integer> ageRangeIds = getAgeRangesForExposureFunctions(exposureTaskConfig);
        Integer arrAgeRangeIds[] = new Integer[ageRangeIds.size()];
        arrAgeRangeIds = ageRangeIds.toArray(arrAgeRangeIds);
        
        //Get array of race, ethnicity and gender to include based on the configured hifs
        //TODO: If all hifs calls for "all" or null, set groupby = false. Will the values in lookup table stay forever? 
        ArrayList<Integer> raceIds = getRacesForExposureFunctions(exposureTaskConfig);
        Integer arrRaceIds[] = new Integer[raceIds.size()];
        arrRaceIds = raceIds.toArray(arrRaceIds);
        boolean booGroupByRace = false;  //1ASIAN, 2BLACK, 3NATAMER, 4WHITE, 5All, 6null  
		for(Integer race : raceIds) {
        	if(race != 5) {
        		booGroupByRace = true;
        		break;
        	}
        }
        
        ArrayList<Integer> ethnicityIds = getEthnicityForExposureFunctions(exposureTaskConfig);
        Integer arrEthnicityIds[] = new Integer[ethnicityIds.size()];
        arrEthnicityIds = ethnicityIds.toArray(arrEthnicityIds);
        boolean booGroupByEthnicity = false;  //1NON-HISP, 2HISP, 3All, 4null       
		for(Integer ethnicity : ethnicityIds) {
        	if(ethnicity != 3) {
        		booGroupByEthnicity = true;
        		break;
        	}
        }
        
        ArrayList<Integer> genderIds = getGendersForExposureFunctions(exposureTaskConfig);
        Integer arrGenderIds[] = new Integer[genderIds.size()];
        arrGenderIds = genderIds.toArray(arrGenderIds);
        boolean booGroupByGender = false; //1F, 2M, 3All, 4null 
		for(Integer gender : genderIds) {
        	if(gender != 3) {
        		booGroupByGender = true;
        		break;
        	}
        }

		//If the crosswalk isn't there, create it now
		CrosswalksApi.ensureCrosswalkExists(getPopulationGridDefinitionId(exposureTaskConfig.popId),aqGrid);

		// Use .trasaction to ensure work_mem setting applies to the get_population transaction.
		// Variables inside a lambda must be final or effectively final
		Integer[] arrAgeRangeIdsFinal = arrAgeRangeIds;
		boolean booGroupByRaceFinal = booGroupByRace;
		boolean booGroupByEthnicityFinal = booGroupByEthnicity;
		boolean booGroupByGenderFinal = booGroupByGender;
		Map<Long, Result<GetPopulationRecord>> popRecords = DSL.using(JooqUtil.getJooqConfiguration(taskUuid))
		.transactionResult(ctx -> {
			DSLContext dsl = DSL.using(ctx);
			dsl.execute("SET LOCAL work_mem = '2097151kB'");
			return Routines.getPopulation(dsl.configuration(), 
				exposureTaskConfig.popId, 
				exposureTaskConfig.popYear,
				null, //arrRaceIds, 
				null, //arrEthnicityIds, 
				null, //arrGenderIds, 
				arrAgeRangeIdsFinal, 
				booGroupByRaceFinal, 
				booGroupByEthnicityFinal, 
				booGroupByGenderFinal, 
				true, //groupbyAgeRange
				aqGrid //outputGridDefinitionId
				).intoGroups(GET_POPULATION.GRID_CELL_ID);
		});
		return popRecords;
	}

	/**
	 * 
	 * @param hifTaskConfig
	 * @return a list of age ranges for health impact functions in the given hif task configuration.
	 */
	private static ArrayList<Integer> getAgeRangesForHifs(HIFTaskConfig hifTaskConfig) {
		
		int minHifAge = 999;
		int maxHifAge = 0;
		
		for(HIFConfig hif : hifTaskConfig.hifs) {
			minHifAge = hif.startAge < minHifAge ? hif.startAge : minHifAge;
			maxHifAge = hif.endAge > maxHifAge ? hif.endAge : maxHifAge;
		}
		
		Record1<Integer> popConfig = DSL.using(JooqUtil.getJooqConfiguration())
		.select(POPULATION_DATASET.POP_CONFIG_ID)
		.from(POPULATION_DATASET)
		.where(POPULATION_DATASET.ID.eq(hifTaskConfig.popId))
		.fetchOne();
		
		
		Result<Record3<Integer, Short, Short>> popAgeRanges = DSL.using(JooqUtil.getJooqConfiguration())
				.select(AGE_RANGE.ID, AGE_RANGE.START_AGE, AGE_RANGE.END_AGE)
				.from(AGE_RANGE)
				.where(AGE_RANGE.POP_CONFIG_ID.eq(popConfig.value1())
						.and(AGE_RANGE.END_AGE.greaterOrEqual((short) minHifAge))
						.and(AGE_RANGE.START_AGE.lessOrEqual((short) maxHifAge))
						)
				.fetch();
		
		ArrayList<Integer> ageRangeIds = new  ArrayList<Integer>();
		
		for(Record3<Integer, Short, Short> ageRange : popAgeRanges) {
			ageRangeIds.add(ageRange.value1());
		}
		
		return ageRangeIds;
	}
	
	/**
	 * 
	 * @param exposureTaskConfig
	 * @return a list of age ranges for health impact functions in the given exposure task configuration.
	 */
	private static ArrayList<Integer> getAgeRangesForExposureFunctions(ExposureTaskConfig exposureTaskConfig) {
		
		int minHifAge = 999;
		int maxHifAge = 0;
		
		for(ExposureConfig exposureConfig : exposureTaskConfig.exposureFunctions) {
			minHifAge = exposureConfig.startAge < minHifAge ? exposureConfig.startAge : minHifAge;
			maxHifAge = exposureConfig.endAge > maxHifAge ? exposureConfig.endAge : maxHifAge;
		}
		
		Record1<Integer> popConfig = DSL.using(JooqUtil.getJooqConfiguration())
		.select(POPULATION_DATASET.POP_CONFIG_ID)
		.from(POPULATION_DATASET)
		.where(POPULATION_DATASET.ID.eq(exposureTaskConfig.popId))
		.fetchOne();
		
		
		Result<Record3<Integer, Short, Short>> popAgeRanges = DSL.using(JooqUtil.getJooqConfiguration())
				.select(AGE_RANGE.ID, AGE_RANGE.START_AGE, AGE_RANGE.END_AGE)
				.from(AGE_RANGE)
				.where(AGE_RANGE.POP_CONFIG_ID.eq(popConfig.value1())
						.and(AGE_RANGE.END_AGE.greaterOrEqual((short) minHifAge))
						.and(AGE_RANGE.START_AGE.lessOrEqual((short) maxHifAge))
						)
				.fetch();
		
		ArrayList<Integer> ageRangeIds = new  ArrayList<Integer>();
		
		for(Record3<Integer, Short, Short> ageRange : popAgeRanges) {
			ageRangeIds.add(ageRange.value1());
		}
		
		return ageRangeIds;
	}
	
	public static ArrayList<Integer> getRacesForHifs(HIFTaskConfig hifTaskConfig){
		ArrayList<Integer> raceIds = new  ArrayList<Integer>();
		for(HIFConfig hif : hifTaskConfig.hifs) {
			if(!raceIds.contains(hif.race)) {
				raceIds.add(hif.race);
			}
		}		
		return raceIds;		
	}
	
	public static ArrayList<Integer> getRacesForExposureFunctions(ExposureTaskConfig exposureTaskConfig){
		ArrayList<Integer> raceIds = new  ArrayList<Integer>();
		for(ExposureConfig exposureConfig : exposureTaskConfig.exposureFunctions) {
			if(!raceIds.contains(exposureConfig.race)) {
				raceIds.add(exposureConfig.race);
			}
		}		
		return raceIds;		
	}
	
	public static ArrayList<Integer> getEthnicityForHifs(HIFTaskConfig hifTaskConfig){
		ArrayList<Integer> ethnicityIds = new  ArrayList<Integer>();
		for(HIFConfig hif : hifTaskConfig.hifs) {
			if(!ethnicityIds.contains(hif.ethnicity)) {
				ethnicityIds.add(hif.ethnicity);
			}
		}		
		return ethnicityIds;		
	}
	
	public static ArrayList<Integer> getEthnicityForExposureFunctions(ExposureTaskConfig exposureTaskConfig){
		ArrayList<Integer> ethnicityIds = new  ArrayList<Integer>();
		for(ExposureConfig exposureConfig : exposureTaskConfig.exposureFunctions) {
			if(!ethnicityIds.contains(exposureConfig.ethnicity)) {
				ethnicityIds.add(exposureConfig.ethnicity);
			}
		}		
		return ethnicityIds;		
	}
	
	public static ArrayList<Integer> getGendersForHifs(HIFTaskConfig hifTaskConfig){
		ArrayList<Integer> genderIds = new  ArrayList<Integer>();
		for(HIFConfig hif : hifTaskConfig.hifs) {
			if(!genderIds.contains(hif.gender)) {
				genderIds.add(hif.gender);
			}
		}		
		return genderIds;		
	}
	
	public static ArrayList<Integer> getGendersForExposureFunctions(ExposureTaskConfig exposureTaskConfig){
		ArrayList<Integer> genderIds = new  ArrayList<Integer>();
		for(ExposureConfig exposureConfig : exposureTaskConfig.exposureFunctions) {
			if(!genderIds.contains(exposureConfig.gender)) {
				genderIds.add(exposureConfig.gender);
			}
		}		
		return genderIds;		
	}

	/**
	 * 
	 * @param id Population dataset id
	 * @return population age ranges for a given population dataset.
	 */
	public static Result<Record3<Integer, Short, Short>> getPopAgeRanges(Integer id) {

		Record1<Integer> popConfig = DSL.using(JooqUtil.getJooqConfiguration())
		.select(POPULATION_DATASET.POP_CONFIG_ID)
		.from(POPULATION_DATASET)
		.where(POPULATION_DATASET.ID.eq(id))
		.fetchOne();
		
		Result<Record3<Integer, Short, Short>> popAgeRanges = DSL.using(JooqUtil.getJooqConfiguration())
				.select(AGE_RANGE.ID, AGE_RANGE.START_AGE, AGE_RANGE.END_AGE)
				.from(AGE_RANGE)
				.where(AGE_RANGE.POP_CONFIG_ID.eq(popConfig.value1()))
				.fetch();
		
		return popAgeRanges;
	}
	
	/**
	 * 
	 * @param request
	 * @param response
	 * @param userProfile
	 * @return a JSON represenation of all population datasets
	 */
	public static Object getAllPopulationDatasets(Request request, Response response, Optional<UserProfile> userProfile) {
		try {
						Result<Record4<String, Integer, Integer, Short[]>> records = DSL.using(JooqUtil.getJooqConfiguration())
					.select(POPULATION_DATASET.NAME,
							POPULATION_DATASET.ID,
							POPULATION_DATASET.GRID_DEFINITION_ID,
							DSL.arrayAggDistinct(T_POP_DATASET_YEAR.POP_YEAR).orderBy(T_POP_DATASET_YEAR.POP_YEAR).as("years"))
					.from(POPULATION_DATASET)
					.join(T_POP_DATASET_YEAR).on(POPULATION_DATASET.ID.eq(T_POP_DATASET_YEAR.POP_DATASET_ID))
					.groupBy(POPULATION_DATASET.NAME,
							POPULATION_DATASET.ID,
							POPULATION_DATASET.GRID_DEFINITION_ID)
					.orderBy(POPULATION_DATASET.NAME)
					.fetch();		
			
			response.type("application/json");
			return records.formatJSON(new JSONFormat().header(false).recordFormat(RecordFormat.OBJECT));
		} catch (Exception e) {
			return CoreApi.getErrorResponse(request, response, 500, e.getMessage() + ": " + e.getStackTrace());
		}

	}

	/**
	 * Paged-style payload for datacenter UI: total count plus one row per population dataset with distinct years.
	 * Local dev: {@code DSL.noCondition()} (no extra filters).
	 */
	public static Object getAllPopulationDatasetsInfo(Request request, Response response, Optional<UserProfile> userProfile) {
		try {
			Result<Record4<String, Integer, Integer, Short[]>> popRecords = DSL.using(JooqUtil.getJooqConfiguration())
					.select(POPULATION_DATASET.NAME,
							POPULATION_DATASET.ID,
							POPULATION_DATASET.GRID_DEFINITION_ID,
							DSL.arrayAggDistinct(T_POP_DATASET_YEAR.POP_YEAR).orderBy(T_POP_DATASET_YEAR.POP_YEAR).as("years"))
					.from(POPULATION_DATASET)
					.join(T_POP_DATASET_YEAR).on(POPULATION_DATASET.ID.eq(T_POP_DATASET_YEAR.POP_DATASET_ID))
					.where(DSL.noCondition())
					.groupBy(POPULATION_DATASET.NAME,
							POPULATION_DATASET.ID,
							POPULATION_DATASET.GRID_DEFINITION_ID)
					.orderBy(POPULATION_DATASET.NAME)
					.fetch();

			int filteredRecordsCount = popRecords.size();

			ObjectMapper mapper = new ObjectMapper();
			ObjectNode data = mapper.createObjectNode();
			data.put("filteredRecordsCount", filteredRecordsCount);
			data.set("records", mapper.valueToTree(popRecords.intoMaps()));

			response.type("application/json");
			return mapper.writeValueAsString(data);
		} catch (JsonProcessingException e) {
			return CoreApi.getErrorResponse(request, response, 500, e.getMessage());
		} catch (Exception e) {
			return CoreApi.getErrorResponse(request, response, 500, e.getMessage() + ": " + e.getStackTrace());
		}
	}
	
	/**
	 * 
	 * @param id 
	 * @return a ist of population dataset information
	 */
	public static Record3<String, Integer, String> getPopulationDatasetInfo(Integer id) {

		DSLContext create = DSL.using(JooqUtil.getJooqConfiguration());

		Record3<String, Integer, String> record = create
				.select(POPULATION_DATASET.NAME,
						POPULATION_DATASET.GRID_DEFINITION_ID,
						GRID_DEFINITION.NAME)
				.from(POPULATION_DATASET)
				.join(GRID_DEFINITION).on(POPULATION_DATASET.GRID_DEFINITION_ID.eq(GRID_DEFINITION.ID))
				.where(POPULATION_DATASET.ID.eq(id))
				.fetchOne();
		
		if (record == null) {
			record = create.newRecord(
				POPULATION_DATASET.NAME,
				POPULATION_DATASET.GRID_DEFINITION_ID,
				GRID_DEFINITION.NAME);
			if (id == 40) {
				record.set(POPULATION_DATASET.NAME, "US CMAQ 12km Nation - 2010 census");
				record.set(POPULATION_DATASET.GRID_DEFINITION_ID, 28);
				record.set(GRID_DEFINITION.NAME,"CMAQ 12km Nation");
			} else {
				record.set(POPULATION_DATASET.NAME, "dataset removed");
				record.set(POPULATION_DATASET.GRID_DEFINITION_ID, null);
				record.set(GRID_DEFINITION.NAME,"dataset removed");
			}
		}
		
		return record;
	}

	/**
	 * 
	 * @param id 
	 * @return the population dataset's grid definition id
	 */
public static Integer getPopulationGridDefinitionId(Integer id) {

Record1<Integer> record = DSL.using(JooqUtil.getJooqConfiguration())
.select(POPULATION_DATASET.GRID_DEFINITION_ID)
.from(POPULATION_DATASET)
.where(POPULATION_DATASET.ID.eq(id))
.fetchOne();

return record.value1();
}

/**
* POST endpoint for importing population data from CSV
* Expected CSV columns: Race,Gender,AgeRange,Ethnicity,Year,Row,Column,Population
*/
public static Object postPopulationData(Request request, Response response, Optional<UserProfile> userProfile) {
request.attribute("org.eclipse.jetty.multipartConfig", new MultipartConfigElement("/temp"));

String populationName;
Integer gridId;
Integer popConfigId;
Integer popYear;

ValidationMessage validationMsg = new ValidationMessage();

try {
populationName = ApiUtil.getMultipartFormParameterAsString(request, "name");
gridId = ApiUtil.getMultipartFormParameterAsInteger(request, "gridId");
popConfigId = ApiUtil.getMultipartFormParameterAsInteger(request, "popConfigId");
popYear = ApiUtil.getMultipartFormParameterAsInteger(request, "popYear");
} catch (NumberFormatException e) {
e.printStackTrace();
return CoreApi.getErrorResponseInvalidId(request, response);
} catch (IllegalArgumentException e) {
e.printStackTrace();
return CoreApi.getErrorResponseInvalidId(request, response);
}

if (populationName == null || gridId == null || popConfigId == null || popYear == null) {
response.type("application/json");
validationMsg.success = false;
validationMsg.messages.add(new ValidationMessage.Message("error",
"Missing required parameters: name, gridId, popConfigId, popYear."));
return CoreApi.transformValMsgToJSON(validationMsg);
}

String userId = userProfile.get().getId();

// Check for duplicate name
List<String> existingNames = DSL.using(JooqUtil.getJooqConfiguration())
.select(POPULATION_DATASET.NAME)
.from(POPULATION_DATASET)
.where(POPULATION_DATASET.NAME.eq(populationName))
.fetch(POPULATION_DATASET.NAME);

if (existingNames.contains(populationName)) {
validationMsg.success = false;
validationMsg.messages.add(new ValidationMessage.Message("error",
"A population dataset named " + populationName + " already exists."));
response.type("application/json");
return CoreApi.transformValMsgToJSON(validationMsg);
}

// Get next dataset ID
Integer datasetId = DSL.using(JooqUtil.getJooqConfiguration())
.select(DSL.max(POPULATION_DATASET.ID))
.from(POPULATION_DATASET)
.fetchOne(DSL.max(POPULATION_DATASET.ID));
if (datasetId == null) {
datasetId = 1;
} else {
datasetId++;
}

// Mapping lookups
Map<String, Integer> raceMap = new HashMap<>();
Map<String, Integer> genderMap = new HashMap<>();
Map<String, Integer> ethnicityMap = new HashMap<>();
Map<String, Integer> ageRangeMap = new HashMap<>();

// Load reference data
DSL.using(JooqUtil.getJooqConfiguration())
.select(RACE.ID, RACE.NAME)
.from(RACE)
.fetch()
.forEach(r -> raceMap.put(r.value2().toUpperCase(), r.value1()));

DSL.using(JooqUtil.getJooqConfiguration())
.select(GENDER.ID, GENDER.NAME)
.from(GENDER)
.fetch()
.forEach(g -> genderMap.put(g.value2().toUpperCase(), g.value1()));

DSL.using(JooqUtil.getJooqConfiguration())
.select(ETHNICITY.ID, ETHNICITY.NAME)
.from(ETHNICITY)
.fetch()
.forEach(e -> ethnicityMap.put(e.value2().toUpperCase(), e.value1()));

DSL.using(JooqUtil.getJooqConfiguration())
.select(AGE_RANGE.ID, AGE_RANGE.NAME)
.from(AGE_RANGE)
.fetch()
.forEach(a -> ageRangeMap.put(a.value2().toUpperCase(), a.value1()));

// Create population dataset
PopulationDatasetRecord popDataset = DSL.using(JooqUtil.getJooqConfiguration())
.newRecord(POPULATION_DATASET);
popDataset.setId(datasetId);
popDataset.setName(populationName);
popDataset.setPopConfigId(popConfigId);
popDataset.setGridDefinitionId(gridId);
popDataset.setApplyGrowth(0);
popDataset.store();

// Process CSV file
try (InputStream is = request.raw().getPart("file").getInputStream();
CSVReader csvReader = new CSVReader(new InputStreamReader(is))) {

String[] record;
String[] headers = csvReader.readNext();

// Column indices
int raceIdx = -1, genderIdx = -1, ageIdx = -1, ethIdx = -1;
int yearIdx = -1, rowIdx = -1, colIdx = -1, popIdx = -1;

for (int i = 0; i < headers.length; i++) {
switch (headers[i].toUpperCase()) {
case "RACE": raceIdx = i; break;
case "GENDER": genderIdx = i; break;
case "AGERANGE": ageIdx = i; break;
case "ETHNICITY": ethIdx = i; break;
case "YEAR": yearIdx = i; break;
case "ROW": rowIdx = i; break;
case "COLUMN": colIdx = i; break;
case "POPULATION": popIdx = i; break;
}
}

if (raceIdx == -1 || genderIdx == -1 || ageIdx == -1 || ethIdx == -1 ||
rowIdx == -1 || colIdx == -1 || popIdx == -1) {
validationMsg.success = false;
validationMsg.messages.add(new ValidationMessage.Message("error",
"CSV must have columns: Race, Gender, AgeRange, Ethnicity, Row, Column, Population"));
response.type("application/json");
return CoreApi.transformValMsgToJSON(validationMsg);
}

// Track entries to avoid duplicates
Map<String, Integer> entryMap = new HashMap<>();
int entryId = DSL.using(JooqUtil.getJooqConfiguration())
.select(DSL.coalesce(DSL.max(POPULATION_ENTRY.ID), 0))
.from(POPULATION_ENTRY)
.fetchOne(DSL.coalesce(DSL.max(POPULATION_ENTRY.ID), 0)) + 1;

List<PopulationEntryRecord> entries = new ArrayList<>();
List<Object[]> values = new ArrayList<>();
int batchSize = 1000;
int rowCount = 0;

while ((record = csvReader.readNext()) != null) {
if (record.length < 8) continue;

String race = record[raceIdx].toUpperCase();
String gender = record[genderIdx].toUpperCase();
String ageRange = record[ageIdx].toUpperCase();
String ethnicity = record[ethIdx].toUpperCase();
int row = Integer.parseInt(record[rowIdx]);
int col = Integer.parseInt(record[colIdx]);
double population = Double.parseDouble(record[popIdx]);

Integer raceId = raceMap.get(race);
Integer genderId = genderMap.get(gender);
Integer ageId = ageRangeMap.get(ageRange);
Integer ethId = ethnicityMap.get(ethnicity);

if (raceId == null || genderId == null || ageId == null || ethId == null) {
continue;
}

String entryKey = datasetId + "-" + raceId + "-" + ethId + "-" + genderId + "-" + ageId + "-" + popYear;
Integer currentEntryId;

if (!entryMap.containsKey(entryKey)) {
currentEntryId = entryId++;
entryMap.put(entryKey, currentEntryId);

PopulationEntryRecord entry = DSL.using(JooqUtil.getJooqConfiguration())
.newRecord(POPULATION_ENTRY);
entry.setId(currentEntryId);
entry.setPopDatasetId(datasetId);
entry.setRaceId(raceId);
entry.setEthnicityId(ethId);
entry.setGenderId(genderId);
entry.setAgeRangeId(ageId);
entry.setPopYear(popYear.shortValue());
entries.add(entry);
} else {
currentEntryId = entryMap.get(entryKey);
}

long gridCellId = ((long) row << 32) | (col & 0xFFFFFFFFL);
values.add(new Object[]{currentEntryId, gridCellId, population});

if (values.size() >= batchSize) {
DSL.using(JooqUtil.getJooqConfiguration())
.batchInsert(entries)
.execute();
entries.clear();

for (Object[] val : values) {
DSL.using(JooqUtil.getJooqConfiguration())
.insertInto(POPULATION_VALUE)
.set(POPULATION_VALUE.POP_ENTRY_ID, (Integer) val[0])
.set(POPULATION_VALUE.GRID_CELL_ID, (Long) val[1])
.set(POPULATION_VALUE.POP_VALUE, (Double) val[2])
.onConflictDoNothing()
.execute();
}
values.clear();
rowCount += batchSize;
}
}

// Insert remaining entries and values
if (!entries.isEmpty()) {
DSL.using(JooqUtil.getJooqConfiguration())
.batchInsert(entries)
.execute();
}

if (!values.isEmpty()) {
for (Object[] val : values) {
DSL.using(JooqUtil.getJooqConfiguration())
.insertInto(POPULATION_VALUE)
.set(POPULATION_VALUE.POP_ENTRY_ID, (Integer) val[0])
.set(POPULATION_VALUE.GRID_CELL_ID, (Long) val[1])
.set(POPULATION_VALUE.POP_VALUE, (Double) val[2])
.onConflictDoNothing()
.execute();
}
}

// Update sequences
DSL.using(JooqUtil.getJooqConfiguration())
.alterSequenceIfExists(DSL.sequence(DSL.name("data", "population_dataset_id_seq")))
.restartWith(BigInteger.valueOf(datasetId + 1))
.execute();

DSL.using(JooqUtil.getJooqConfiguration())
.alterSequenceIfExists(DSL.sequence(DSL.name("data", "population_entry_id_seq")))
.restartWith(BigInteger.valueOf(entryId))
.execute();

// Insert year record
DSL.using(JooqUtil.getJooqConfiguration())
.insertInto(T_POP_DATASET_YEAR)
.set(T_POP_DATASET_YEAR.POP_DATASET_ID, datasetId)
.set(T_POP_DATASET_YEAR.POP_YEAR, popYear.shortValue())
.onConflictDoNothing()
.execute();

validationMsg.success = true;
validationMsg.messages.add(new ValidationMessage.Message("info",
"Population dataset '" + populationName + "' imported successfully with " + rowCount + " records."));
response.type("application/json");
return CoreApi.transformValMsgToJSON(validationMsg);

} catch (Exception e) {
e.printStackTrace();
validationMsg.success = false;
validationMsg.messages.add(new ValidationMessage.Message("error",
"Error importing population data: " + e.getMessage()));
response.type("application/json");
return CoreApi.transformValMsgToJSON(validationMsg);
}
}

}
