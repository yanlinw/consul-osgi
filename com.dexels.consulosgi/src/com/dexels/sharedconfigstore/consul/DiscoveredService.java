package com.dexels.sharedconfigstore.consul;

import java.util.Map;
import java.util.Set;

public interface DiscoveredService {

	public String getName();
	public String getId();
	public String getAddress();
	public int getPort();
	public Set<String> getTags();
	public Map<String, String> getAttributes();
	public String getPortType();

}