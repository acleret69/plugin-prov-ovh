/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.ovh.catalog;

import lombok.Getter;
import lombok.Setter;

/**
 * AWS storage price configuration
 */
@Getter
@Setter
public abstract class AbstractAwsStoragePrice extends AwsCsvPrice {

	private String storageClass;
	private String volumeType;

}
