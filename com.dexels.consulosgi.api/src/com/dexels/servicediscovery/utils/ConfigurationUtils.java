package com.dexels.servicediscovery.utils;

import java.io.IOException;
import java.util.Dictionary;

import org.osgi.framework.InvalidSyntaxException;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConfigurationUtils {
	
	
	private final static Logger logger = LoggerFactory.getLogger(ConfigurationUtils.class);

	
	public static Configuration emitFactoryIfChanged(ConfigurationAdmin configAdmin,String factoryPid, String filter, Dictionary<String, Object> settings)
			throws IOException {
		Configuration config = createOrReuseFactoryConfiguration(configAdmin,factoryPid, filter);
		updateIfChanged(config, settings);
		return config;
	}

	public static Configuration createOrReuseFactoryConfiguration(ConfigurationAdmin configAdmin,String factoryPid, final String filter)
			throws IOException {
		Configuration cc = null;
		try {
			Configuration[] c = configAdmin.listConfigurations(filter);
			if (c != null && c.length > 1) {
				logger.warn("Multiple configurations found for filter: {}", filter);
			}
			if (c != null && c.length > 0) {
				cc = c[0];
			} else {
				logger.debug("No config found");
			}
		} catch (InvalidSyntaxException e) {
			logger.error("Error in filter: {}", filter, e);
		}
		if (cc == null) {
			logger.debug("creating.");
			cc = configAdmin.createFactoryConfiguration(factoryPid, null);
		}
		return cc;
	}

	public static void updateIfChanged(Configuration c, Dictionary<String, Object> settings) throws IOException {
		Dictionary<String, Object> old = c.getProperties();
		if (old != null) {
			if (!old.equals(settings)) {
				c.update(settings);
			} else {
				logger.info("Ignoring equal");
			}
		} else {
			// this will make this component 'own' this configuration, unsure if
			// this is desirable.
			c.update(settings);
			logger.info("updating");
		}
	}
}
