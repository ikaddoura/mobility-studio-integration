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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.contrib.signals.SignalSystemsConfigGroup;
import org.matsim.contrib.signals.builder.Signals;
import org.matsim.contrib.signals.data.SignalsData;
import org.matsim.contrib.signals.data.SignalsDataLoader;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigGroup;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.io.IOUtils;

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

		// the signals contrib reads the files as plain xml streams, i.e. gzipped files need to be decompressed first
		signalsConfig.setSignalSystemFile(decompressIfGzipped(config, signalsConfig.getSignalSystemFile()));
		signalsConfig.setSignalGroupsFile(decompressIfGzipped(config, signalsConfig.getSignalGroupsFile()));
		signalsConfig.setSignalControlFile(decompressIfGzipped(config, signalsConfig.getSignalControlFile()));
		signalsConfig.setAmberTimesFile(decompressIfGzipped(config, signalsConfig.getAmberTimesFile()));
		signalsConfig.setIntergreenTimesFile(decompressIfGzipped(config, signalsConfig.getIntergreenTimesFile()));
		signalsConfig.setConflictingDirectionsFile(decompressIfGzipped(config, signalsConfig.getConflictingDirectionsFile()));

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

	private static String decompressIfGzipped(Config config, String fileName) {
		if (fileName == null || !fileName.toLowerCase().endsWith(".gz")) {
			return fileName;
		}
		try {
			URL url = ConfigGroup.getInputFileURL(config.getContext(), fileName);
			String baseName = new File(url.getPath()).getName();
			String prefix = baseName.replaceAll("(?i)(\\.xml)?\\.gz$", "");
			Path tempFile = Files.createTempFile(prefix + "_", ".xml");
			tempFile.toFile().deleteOnExit();
			try (InputStream in = IOUtils.getInputStream(url)) {
				Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
			}
			log.info("Decompressed " + fileName + " to " + tempFile + ", since the signals contrib cannot read gzipped files.");
			return tempFile.toAbsolutePath().toString();
		} catch (IOException e) {
			throw new UncheckedIOException("Could not decompress signals file " + fileName, e);
		}
	}
}
