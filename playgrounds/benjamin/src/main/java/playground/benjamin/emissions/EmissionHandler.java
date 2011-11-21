/* *********************************************************************** *
 * project: org.matsim.*
 * EmissionHandler.java
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2009 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */
package playground.benjamin.emissions;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import org.apache.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.groups.VspExperimentalConfigGroup;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.algorithms.EventWriterXML;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.vehicles.VehicleReaderV1;
import org.matsim.vehicles.VehicleUtils;
import org.matsim.vehicles.Vehicles;

import playground.benjamin.emissions.types.ColdPollutant;
import playground.benjamin.emissions.types.HbefaAvgColdEmissionFactor;
import playground.benjamin.emissions.types.HbefaAvgWarmEmissionFactorKey;
import playground.benjamin.emissions.types.HbefaDetailedWarmEmissionFactorKey;
import playground.benjamin.emissions.types.HbefaTrafficSituation;
import playground.benjamin.emissions.types.HbefaVehicleCategory;
import playground.benjamin.emissions.types.HbefaWarmEmissionFactor;
import playground.benjamin.emissions.types.WarmPollutant;

/**
 * @author benjamin
 *
 */
public class EmissionHandler {
	private static final Logger logger = Logger.getLogger(EmissionHandler.class);
	
	private final Scenario scenario;
	private static EventWriterXML emissionEventWriter;

	//===
	private static String roadTypeMappingFile;
	
	private static String averageFleetColdEmissionFactorsFile;
	private static String averageFleetWarmEmissionFactorsFile;

	private static String detailedWarmEmissionFactorsFile;
	//===
	Map<Integer, String> roadTypeMapping;
	
	private Map<HbefaAvgWarmEmissionFactorKey, HbefaWarmEmissionFactor> avgHbefaWarmTable;
	private Map<ColdPollutant, Map<Integer, Map<Integer, HbefaAvgColdEmissionFactor>>> avgHbefaColdTable;

	private Map<HbefaDetailedWarmEmissionFactorKey, HbefaWarmEmissionFactor> detailedHbefaWarmTable;
	//===
	private String vehicleFile;

	public EmissionHandler(Scenario scenario) {
		this.scenario = scenario;
	}

	public void createLookupTables() {
		logger.info("entering createLookupTables");
		
		getInputFiles();
		
		roadTypeMapping = createRoadTypeMapping(roadTypeMappingFile);

		avgHbefaWarmTable = createAvgHbefaWarmTable(averageFleetWarmEmissionFactorsFile);
		avgHbefaColdTable = createAvgHbefaColdTable(averageFleetColdEmissionFactorsFile);
		
		if(scenario.getConfig().vspExperimental().isUsingDetailedEmissionCalculation()){
			detailedHbefaWarmTable = createDetailedHbefaWarmTable(detailedWarmEmissionFactorsFile);
		}
		else{
			logger.warn("Detailed warm emission calculation is switched off in " + VspExperimentalConfigGroup.GROUP_NAME + " config group; Using fleet average values for all vehicles.");
		}
		logger.info("leaving createLookupTables");
	}

	private void getInputFiles() {
		
		roadTypeMappingFile = scenario.getConfig().vspExperimental().getEmissionRoadTypeMappingFile();

		averageFleetWarmEmissionFactorsFile = scenario.getConfig().vspExperimental().getAverageWarmEmissionFactorsFile();
		averageFleetColdEmissionFactorsFile = scenario.getConfig().vspExperimental().getAverageColdEmissionFactorsFile();
		
		detailedWarmEmissionFactorsFile = scenario.getConfig().vspExperimental().getDetailedWarmEmissionFactorsFile() ;
	}

	public void installEmissionEventHandler(EventsManager eventsManager, String emissionEventOutputFile) {
		logger.info("entering installEmissionsEventHandler") ;
		
		EventsManager emissionEventsManager = EventsUtils.createEventsManager();
		Network network = scenario.getNetwork() ;

		//TODO: Vehicles should be read in vspExperimentalConfigGroup
		vehicleFile = "../../detailedEval/emissions/testScenario/input/vehicles.xml";
		Vehicles vehicles = VehicleUtils.createVehiclesContainer();
		VehicleReaderV1 vehicleReader = new VehicleReaderV1(vehicles);
		vehicleReader.readFile(vehicleFile);
		
		// instantiate analysis modules
		WarmEmissionAnalysisModule warmEmissionAnalysisModule = new WarmEmissionAnalysisModule(
				roadTypeMapping,
				avgHbefaWarmTable,
				detailedHbefaWarmTable,
				emissionEventsManager);
		ColdEmissionAnalysisModule coldEmissionAnalysisModule = new ColdEmissionAnalysisModule (
				avgHbefaColdTable,
				emissionEventsManager);
		
		// create different emission handler
		WarmEmissionHandler warmEmissionHandler = new WarmEmissionHandler(
				vehicles,
				network,
				warmEmissionAnalysisModule);
		ColdEmissionHandler coldEmissionHandler = new ColdEmissionHandler(
				network,
				coldEmissionAnalysisModule);
		
		// create the writer for emission events
		emissionEventWriter = new EventWriterXML(emissionEventOutputFile);
		emissionEventsManager.addHandler(emissionEventWriter);
		
		// add the handler
		eventsManager.addHandler(warmEmissionHandler);
		eventsManager.addHandler(coldEmissionHandler);
		
		logger.info("leaving installEmissionsEventHandler") ;
	}

	private static Map<Integer, String> createRoadTypeMapping(String filename){
		logger.info("entering createRoadTypeMapping ...") ;
		
		Map<Integer, String> roadTypeMapping = new HashMap<Integer, String>();
		try{
			BufferedReader br = IOUtils.getBufferedReader(filename);
			String strLine = br.readLine();
			Map<String, Integer> indexFromKey = createIndexFromKey(strLine, ";");
			
			while ((strLine = br.readLine()) != null){
				if ( strLine.contains("\"")) throw new RuntimeException("cannot handle this character in parsing") ;
				
				String[] inputArray = strLine.split(";");
				Integer visumRtNr = Integer.parseInt(inputArray[indexFromKey.get("VISUM_RT_NR")]);
				String hbefaRtName = (inputArray[indexFromKey.get("HBEFA_RT_NAME")]);
				
				roadTypeMapping.put(visumRtNr, hbefaRtName);
			}
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
		logger.info("leaving createRoadTypeMapping ...") ;
		return roadTypeMapping;
	}
	
	private static Map<HbefaAvgWarmEmissionFactorKey, HbefaWarmEmissionFactor> createAvgHbefaWarmTable(String filename){
		logger.info("entering createAvgHbefaWarmTable ...");
		
		Map<HbefaAvgWarmEmissionFactorKey, HbefaWarmEmissionFactor> avgHbefaWarmTable = new HashMap<HbefaAvgWarmEmissionFactorKey, HbefaWarmEmissionFactor>();
		
		try{
			BufferedReader br = IOUtils.getBufferedReader(filename);
			String strLine = br.readLine();
			Map<String, Integer> indexFromKey = createIndexFromKey(strLine, ";");
			
			while ((strLine = br.readLine()) != null) {
				String[] array = strLine.split(";");
				
				HbefaAvgWarmEmissionFactorKey key = new HbefaAvgWarmEmissionFactorKey();
				key.setHbefaVehicleCategory(mapString2HbefaVehicleCategory(array[indexFromKey.get("VehCat")]));
				key.setHbefaComponent(mapComponent2WarmPollutant(array[indexFromKey.get("Component")]));
				key.setHbefaRoadCategory(mapString2HbefaRoadCategory(array[indexFromKey.get("TrafficSit")]));
				key.setHbefaTrafficSituation(mapString2HbefaTrafficSituation(array[indexFromKey.get("TrafficSit")]));
				
				HbefaWarmEmissionFactor value = new HbefaWarmEmissionFactor();
				value.setSpeed(Double.parseDouble(array[indexFromKey.get("V_weighted")]));
				value.setEmissionFactor(Double.parseDouble(array[indexFromKey.get("EFA_weighted")]));
				
				avgHbefaWarmTable.put(key, value);
			}
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}

		logger.info("leaving createAvgHbefaWarmTable ...");
		return avgHbefaWarmTable;
	}
	
	private static Map<ColdPollutant, Map<Integer, Map<Integer, HbefaAvgColdEmissionFactor>>> createAvgHbefaColdTable(String filename){
		logger.info("entering createAvgHbefaColdTable ...");
		
		Map<ColdPollutant, Map<Integer, Map<Integer, HbefaAvgColdEmissionFactor>>> avgHbefaColdTable =
			new TreeMap<ColdPollutant, Map<Integer, Map<Integer, HbefaAvgColdEmissionFactor>>>();
		try{
			BufferedReader br = IOUtils.getBufferedReader(filename);
			String strLine = br.readLine();
			Map<String, Integer> indexFromKey = createIndexFromKey(strLine, ";");
			
			while ((strLine = br.readLine()) != null)   {
				String[] array = strLine.split(";");
				HbefaAvgColdEmissionFactor coldEmissionFactor = new HbefaAvgColdEmissionFactor(
						array[indexFromKey.get("VehCat")],
						array[indexFromKey.get("Component")],
						array[indexFromKey.get("ParkingTime [h]")],
						array[indexFromKey.get("Distance [km]")],
						Double.parseDouble(array[indexFromKey.get("EFA_km_weighted")]));
				
				ColdPollutant coldPollutant = ColdPollutant.getValue(array[indexFromKey.get("Component")]);
				int parkingTime = Integer.valueOf(array[indexFromKey.get("ParkingTime [h]")].split("-")[0]);
				int distance = Integer.valueOf(array[indexFromKey.get("Distance [km]")].split("-")[0]);
				if (avgHbefaColdTable.get(coldPollutant) != null){
					if(avgHbefaColdTable.get(coldPollutant).get(distance) != null){
						avgHbefaColdTable.get(coldPollutant).get(distance).put(parkingTime, coldEmissionFactor);
					}
					else{
						Map<Integer, HbefaAvgColdEmissionFactor> tempParkingTime = new TreeMap<Integer, HbefaAvgColdEmissionFactor>();
						tempParkingTime.put(parkingTime, coldEmissionFactor);
						avgHbefaColdTable.get(coldPollutant).put(distance, tempParkingTime);	  
					}
				}
				else{
					Map<Integer,HbefaAvgColdEmissionFactor> tempParkingTime =	new TreeMap<Integer, HbefaAvgColdEmissionFactor>();
					tempParkingTime.put(parkingTime, coldEmissionFactor);
					Map<Integer, Map<Integer, HbefaAvgColdEmissionFactor>> tempDistance = new TreeMap<Integer, Map<Integer, HbefaAvgColdEmissionFactor>>();
					tempDistance.put(parkingTime, tempParkingTime);
					avgHbefaColdTable.put(coldPollutant, tempDistance);				
				}
			}
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
		logger.info("leaving createAvgHbefaColdTable ...");
		return avgHbefaColdTable;
	}
	
	private static Map<HbefaDetailedWarmEmissionFactorKey, HbefaWarmEmissionFactor> createDetailedHbefaWarmTable(String filename){
		logger.info("entering createDetailedHbefaWarmTable ...");

		Map<HbefaDetailedWarmEmissionFactorKey, HbefaWarmEmissionFactor> hbefaWarmTableDetailed = new HashMap<HbefaDetailedWarmEmissionFactorKey, HbefaWarmEmissionFactor>() ;
		try{
			BufferedReader br = IOUtils.getBufferedReader(filename);
			String strLine = br.readLine();

			Map<String, Integer> indexFromKey = createIndexFromKey(strLine, ";");

			while ((strLine = br.readLine()) != null) {
				String[] array = strLine.split(";");

				HbefaDetailedWarmEmissionFactorKey key = new HbefaDetailedWarmEmissionFactorKey();
				key.setHbefaVehicleCategory(mapString2HbefaVehicleCategory(array[indexFromKey.get("VehCat")]));
				key.setHbefaComponent(mapComponent2WarmPollutant(array[indexFromKey.get("Component")]));
				key.setHbefaRoadCategory(mapString2HbefaRoadCategory(array[indexFromKey.get("TrafficSit")]));
				key.setHbefaTrafficSituation(mapString2HbefaTrafficSituation(array[indexFromKey.get("TrafficSit")]));
				key.setHbefaTechnology(array[indexFromKey.get("Technology")]);
				key.setHbefaSizeClass(array[indexFromKey.get("SizeClasse")]);
				key.setHbefaEmConcept(array[indexFromKey.get("EmConcept")]);

				HbefaWarmEmissionFactor value = new HbefaWarmEmissionFactor();
				value.setSpeed(Double.parseDouble(array[indexFromKey.get("V")]));
				value.setEmissionFactor(Double.parseDouble(array[indexFromKey.get("EFA")]));

				hbefaWarmTableDetailed.put(key, value);
			}
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
		logger.info("entering createDetailedHbefaWarmTable ...");
		return hbefaWarmTableDetailed;
	}
	
	private static Map<String, Integer> createIndexFromKey(String strLine, String fieldSeparator) {
		String[] keys = strLine.split(fieldSeparator) ;

		Map<String, Integer> indexFromKey = new HashMap<String, Integer>() ;
		for ( int ii = 0; ii < keys.length; ii++ ) {
			indexFromKey.put(keys[ii], ii ) ;
		}
		return indexFromKey ;
	}

	private static HbefaVehicleCategory mapString2HbefaVehicleCategory(String string) {
		HbefaVehicleCategory hbefaVehicleCategory = null;
		if(string.contains("pass. car")) hbefaVehicleCategory = HbefaVehicleCategory.PASSENGER_CAR;
		else if(string.contains("HGV")) hbefaVehicleCategory = HbefaVehicleCategory.HEAVY_GOODS_VEHICLE;
		else{
			logger.warn("Could not map String " + string + " to any HbefaVehicleCategory; please check syntax in file " + averageFleetWarmEmissionFactorsFile);
			throw new RuntimeException();
		}
		return hbefaVehicleCategory;
	}

	private static WarmPollutant mapComponent2WarmPollutant(String string) {
		WarmPollutant warmPollutant = null;
		for(WarmPollutant wp : WarmPollutant.values()){
			if(string.equals(wp.getText())) warmPollutant = wp;
			else continue;
		}
		return warmPollutant;
	}

	private static String mapString2HbefaRoadCategory(String string) {
		String hbefaRoadCategory = null;
		String[] parts = string.split("/");
		hbefaRoadCategory = parts[0] + "/" + parts[1] + "/" + parts[2];
		return hbefaRoadCategory;
	}

	private static HbefaTrafficSituation mapString2HbefaTrafficSituation(String string) {
		HbefaTrafficSituation hbefaTrafficSituation = null;
		if(string.endsWith("Freeflow")) hbefaTrafficSituation = HbefaTrafficSituation.FREEFLOW;
		else if(string.endsWith("Heavy")) hbefaTrafficSituation = HbefaTrafficSituation.HEAVY;
		else if(string.endsWith("Satur.")) hbefaTrafficSituation = HbefaTrafficSituation.SATURATED;
		else if(string.endsWith("St+Go")) hbefaTrafficSituation = HbefaTrafficSituation.STOPANDGO;
		else {
			logger.warn("Could not map String " + string + " to any HbefaTrafficSituation; please check syntax in file " + averageFleetWarmEmissionFactorsFile);
			throw new RuntimeException();
		}
		return hbefaTrafficSituation;
	}

	public EventWriterXML getEmissionEventWriter() {
		return emissionEventWriter;
	}
}
