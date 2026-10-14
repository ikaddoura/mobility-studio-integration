/* *********************************************************************** *
 * project: org.matsim.*
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2026 by the members listed in the COPYING,        *
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

package de.mobilitystudio.config;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ReplanningConfigGroup.StrategySettings;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup.ActivityParams;
import org.matsim.core.config.groups.ScoringConfigGroup.ModeParams;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.io.StreamingPopulationReader;
import org.matsim.core.replanning.strategies.DefaultPlanStrategiesModule.DefaultSelector;
import org.matsim.core.replanning.strategies.DefaultPlanStrategiesModule.DefaultStrategy;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scenario.ScenarioUtils;

/**
 * Derives sensible default values for a newly created config: standard replanning strategies
 * and scoring parameters for all activity types and modes found in the population.
 */
public final class PopulationBasedConfigDefaults {
	private static final Logger log = LogManager.getLogger(PopulationBasedConfigDefaults.class);

	private static final double DAY = 24 * 3600.;
	private static final double MIN_TYPICAL_DURATION = 600.;
	private static final double FALLBACK_TYPICAL_DURATION = 2 * 3600.;
	// for activity types plans start with (typically home), used if no duration can be derived
	private static final double FALLBACK_TYPICAL_DURATION_FIRST_ACTIVITY = 12 * 3600.;

	// e.g. "home_86400" or "work_28800" (typical duration encoded in the activity type, as in the open scenarios)
	private static final Pattern DURATION_SUFFIX = Pattern.compile(".*_(\\d+)$");

	private static final List<String> SUBTOUR_MODE_CHOICE_CANDIDATES = List.of(TransportMode.car, TransportMode.pt, TransportMode.bike, TransportMode.walk);
	private static final List<String> CHAIN_BASED_MODES = List.of(TransportMode.car, TransportMode.bike);

	private PopulationBasedConfigDefaults() {
	}

	public static final class PopulationScan {
		private final Map<String, List<Double>> activityDurations = new TreeMap<>();
		private final Set<String> legModes = new LinkedHashSet<>();
		private final Set<String> routingModes = new LinkedHashSet<>();
		private final Set<String> subpopulations = new LinkedHashSet<>();
		private final Set<String> firstActivityTypes = new LinkedHashSet<>();
		private long numberOfPersons = 0;

		public Set<String> getActivityTypes() {
			return activityDurations.keySet();
		}

		public Set<String> getLegModes() {
			return legModes;
		}

		public Set<String> getRoutingModes() {
			return routingModes;
		}

		public Set<String> getSubpopulations() {
			return subpopulations;
		}

		public long getNumberOfPersons() {
			return numberOfPersons;
		}
	}

	/**
	 * Reads the population file (streaming, selected plans only) and collects activity types with their
	 * observed durations, leg modes, routing modes and subpopulations.
	 *
	 * @param networkFile only read if the population cannot be parsed without it (e.g. old formats with node routes); may be null
	 */
	public static PopulationScan scanPopulation(File populationFile, File networkFile) {
		try {
			return scanPopulation(populationFile, ScenarioUtils.createScenario(ConfigUtils.createConfig()));
		} catch (RuntimeException e) {
			if (networkFile == null || !networkFile.exists()) {
				throw e;
			}
			log.warn("Could not read population without network (" + e.getMessage() + "). Trying again with network " + networkFile.getName() + ".");
			Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
			new MatsimNetworkReader(scenario.getNetwork()).readFile(networkFile.getAbsolutePath());
			return scanPopulation(populationFile, scenario);
		}
	}

	private static PopulationScan scanPopulation(File populationFile, Scenario scenario) {
		PopulationScan scan = new PopulationScan();

		StreamingPopulationReader reader = new StreamingPopulationReader(scenario);
		reader.addAlgorithm(person -> {
			scan.numberOfPersons++;
			scan.subpopulations.add(PopulationUtils.getSubpopulation(person));
			Plan plan = person.getSelectedPlan();
			if (plan != null) {
				scanPlan(plan, scan);
			}
		});
		reader.readFile(populationFile.getAbsolutePath());

		log.info("Scanned " + scan.numberOfPersons + " persons. Activity types: " + scan.getActivityTypes()
				+ ", leg modes: " + scan.legModes + ", routing modes: " + scan.routingModes + ", subpopulations: " + scan.subpopulations);
		return scan;
	}

	private static void scanPlan(Plan plan, PopulationScan scan) {
		List<Activity> activities = new ArrayList<>();
		for (PlanElement pe : plan.getPlanElements()) {
			if (pe instanceof Leg leg) {
				scan.legModes.add(leg.getMode());
				String routingMode = TripStructureUtils.getRoutingMode(leg);
				scan.routingModes.add(routingMode != null ? routingMode : leg.getMode());
			} else if (pe instanceof Activity act && !TripStructureUtils.isStageActivityType(act.getType())) {
				activities.add(act);
			}
		}

		if (!activities.isEmpty()) {
			scan.firstActivityTypes.add(activities.get(0).getType());
		}

		for (int i = 0; i < activities.size(); i++) {
			Activity act = activities.get(i);
			List<Double> durations = scan.activityDurations.computeIfAbsent(act.getType(), k -> new ArrayList<>());

			if (act.getMaximumDuration().isDefined()) {
				durations.add(act.getMaximumDuration().seconds());
			} else if (act.getStartTime().isDefined() && act.getEndTime().isDefined()) {
				durations.add(act.getEndTime().seconds() - act.getStartTime().seconds());
			} else if (i == 0 && activities.size() > 1 && act.getEndTime().isDefined()) {
				// first activity: wrap around with the last activity if it is of the same type (e.g. home overnight)
				Activity last = activities.get(activities.size() - 1);
				if (last.getType().equals(act.getType()) && last.getStartTime().isDefined()) {
					durations.add(act.getEndTime().seconds() + DAY - last.getStartTime().seconds());
				}
			}
		}
	}

	/**
	 * Adds activity params for all activity types and mode params for all leg modes of the population
	 * which are not yet defined in the config.
	 */
	public static void addScoringParams(Config config, PopulationScan scan) {
		ScoringConfigGroup scoring = config.scoring();

		for (Map.Entry<String, List<Double>> entry : scan.activityDurations.entrySet()) {
			String type = entry.getKey();
			if (scoring.getActivityParams(type) != null) {
				continue;
			}
			double typicalDuration = estimateTypicalDuration(type, entry.getValue(),
					scan.firstActivityTypes.contains(type) ? FALLBACK_TYPICAL_DURATION_FIRST_ACTIVITY : FALLBACK_TYPICAL_DURATION);
			ActivityParams params = new ActivityParams(type);
			params.setTypicalDuration(typicalDuration);
			scoring.addActivityParams(params);
			log.info("Adding activity params for '" + type + "' with typical duration " + (int) typicalDuration + " s.");
		}

		Set<String> modes = new LinkedHashSet<>(scan.legModes);
		modes.addAll(scan.routingModes);
		for (String mode : modes) {
			if (!scoring.getModes().containsKey(mode)) {
				scoring.addModeParams(new ModeParams(mode));
				log.info("Adding mode params for '" + mode + "' (default values, please check).");
			}
		}
	}

	static double estimateTypicalDuration(String type, List<Double> durations, double fallback) {
		Matcher matcher = DURATION_SUFFIX.matcher(type);
		if (matcher.matches()) {
			return Math.max(MIN_TYPICAL_DURATION, Double.parseDouble(matcher.group(1)));
		}

		List<Double> valid = new ArrayList<>();
		for (double d : durations) {
			if (d > 0 && d <= DAY) {
				valid.add(d);
			}
		}
		if (valid.isEmpty()) {
			return fallback;
		}
		Collections.sort(valid);
		double median = valid.get(valid.size() / 2);
		// round to full minutes
		return Math.max(MIN_TYPICAL_DURATION, Math.round(median / 60.) * 60.);
	}

	/**
	 * Adds the standard replanning strategies (ChangeExpBeta, ReRoute and, if there is a choice, SubtourModeChoice)
	 * for each subpopulation. Innovation is switched off after 80% of the iterations.
	 *
	 * @param modes the modes used in the population; may be empty if unknown
	 */
	public static void addDefaultStrategies(Config config, Set<String> subpopulations, Set<String> modes) {
		List<String> modeChoiceModes = new ArrayList<>();
		if (modes.isEmpty()) {
			modeChoiceModes.addAll(SUBTOUR_MODE_CHOICE_CANDIDATES);
		} else {
			for (String mode : SUBTOUR_MODE_CHOICE_CANDIDATES) {
				if (modes.contains(mode)) {
					modeChoiceModes.add(mode);
				}
			}
		}
		boolean useModeChoice = modeChoiceModes.size() >= 2;
		if (useModeChoice) {
			config.subtourModeChoice().setModes(modeChoiceModes.toArray(new String[0]));
			config.subtourModeChoice().setChainBasedModes(CHAIN_BASED_MODES.stream().filter(modeChoiceModes::contains).toArray(String[]::new));
		}

		Set<String> subpops = subpopulations.isEmpty() ? Collections.singleton(null) : subpopulations;
		for (String subpop : subpops) {
			addStrategy(config, subpop, DefaultSelector.ChangeExpBeta, useModeChoice ? 0.8 : 0.9);
			addStrategy(config, subpop, DefaultStrategy.ReRoute, 0.1);
			if (useModeChoice) {
				addStrategy(config, subpop, DefaultStrategy.SubtourModeChoice, 0.1);
			}
		}
		config.replanning().setFractionOfIterationsToDisableInnovation(0.8);
		log.info("Added default strategies for subpopulations " + subpops + (useModeChoice ? " with subtour mode choice between " + modeChoiceModes : "") + ".");
	}

	private static void addStrategy(Config config, String subpopulation, String strategyName, double weight) {
		StrategySettings settings = new StrategySettings();
		settings.setStrategyName(strategyName);
		settings.setWeight(weight);
		settings.setSubpopulation(subpopulation);
		config.replanning().addStrategySettings(settings);
	}
}
