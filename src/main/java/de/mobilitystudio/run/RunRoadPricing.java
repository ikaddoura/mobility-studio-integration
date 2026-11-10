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

import org.matsim.api.core.v01.Scenario;
import org.matsim.contrib.roadpricing.RoadPricing;
import org.matsim.contrib.roadpricing.RoadPricingConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.scenario.ScenarioUtils;

public final class RunRoadPricing {

	public static void main(String[] args) {

		// load the config:
		Config config;
		if ( args==null || args.length==0 || args[0]==null ){
			throw new RuntimeException( "No config file given as argument." ) ;
		} else {
			config = ConfigUtils.loadConfig( args, new RoadPricingConfigGroup() );
		}

		// load the scenario:
		Scenario scenario = ScenarioUtils.loadScenario( config );

		// instantiate the controler:
		Controler controler = new Controler( scenario );

		// use the road pricing module:
		RoadPricing.configure( controler );

		// run the controler:
		controler.run();
	}
}
