/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.ovh.catalog;

import java.util.Map;

/**
 * Regional prices contract.
 */
public interface RegionalPrices {

	/**
	 * Return the prices by code name.
	 * 
	 * @return The prices by code name.
	 */
	Map<String, OvhPriceRegion> getPRegions();
}
