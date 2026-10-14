/* *********************************************************************** *
 * project: org.matsim.*
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2023 by the members listed in the COPYING,        *
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

package de.mobilitystudio.run;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.contrib.signals.SignalSystemsConfigGroup;
import org.matsim.contrib.signals.builder.Signals;
import org.matsim.contrib.signals.data.SignalsData;
import org.matsim.contrib.signals.data.SignalsDataLoader;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.scenario.ScenarioUtils;

public final class RunSignals {
	private static final Logger log = LogManager.getLogger(RunSignals.class);

	public static void main(String[] args) {

		Config config;
		if ( args==null || args.length==0 || args[0]==null ){
			throw new RuntimeException( "No config file given as argument." ) ;
		} else {
			config = ConfigUtils.loadConfig( args, new SignalSystemsConfigGroup() );
		}

		SignalSystemsConfigGroup signalsConfig = ConfigUtils.addOrGetModule(config, SignalSystemsConfigGroup.GROUP_NAME, SignalSystemsConfigGroup.class);
		if (!signalsConfig.isUseSignalSystems()) {
			log.warn("useSignalsystems was false. Setting it to true, since MATSim is started in signals mode.");
			signalsConfig.setUseSignalSystems(true);
		}
		if (config.qsim().isUsingFastCapacityUpdate()) {
			log.warn("Fast flow capacity update does not support signals. Setting qsim.usingFastCapacityUpdate to false.");
			config.qsim().setUsingFastCapacityUpdate(false);
		}

		// --- create the scenario
		Scenario scenario = ScenarioUtils.loadScenario(config);
		// load the information about signals data (i.e. fill the SignalsData object) and add it to the scenario as scenario element
		scenario.addScenarioElement(SignalsData.ELEMENT_NAME, new SignalsDataLoader(config).loadSignalsData());

		// --- create the controler
		Controler controler = new Controler(scenario);
		// add the signals module to the simulation such that SignalsData is not only contained in the scenario but also used in the simulation
		Signals.configure(controler);
		controler.run();
	}
}
