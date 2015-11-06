package com.dexels.sharedconfigstore.http.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClients;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.JsonProcessingException;
import org.codehaus.jackson.map.ObjectMapper;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventAdmin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dexels.servicediscovery.http.api.ChangeEvent;
import com.dexels.servicediscovery.http.api.HttpCache;
import com.dexels.servicediscovery.http.api.HttpJsonApi;
import com.dexels.servicediscovery.http.api.KeyChange;

@Component(name = "dexels.consul.listener", immediate = true,configurationPolicy=ConfigurationPolicy.REQUIRE)
public class LongPollingHttpListenerImpl {

	private String blockIntervalInSeconds = "60";
	private final Map<String,Integer> lastIndexes = new HashMap<>();
	private final Map<String,byte[]> lastValues = new HashMap<>();
	private final Map<String,LongPollingCallback> currentCallbacks = new HashMap<>();
	
	private final static Logger logger = LoggerFactory.getLogger(LongPollingHttpListenerImpl.class);
	private CloseableHttpAsyncClient client;
	private HttpJsonApi consulClient;
	private EventAdmin eventAdmin;
	private HttpCache httpCache;
	private String path;
	
	private final ObjectMapper mapper = new ObjectMapper();
	private boolean active = false;
	private final Map<String,Object> settings = new HashMap<>();
	
	@Activate
    public void activate(Map<String, Object> settings) {
		try {
			this.settings.clear();
			this.settings.putAll(settings);
			logger.info("ACTIVATING LONGPOLLER");
			int timeout = Integer.parseInt(blockIntervalInSeconds) + 10;
			RequestConfig config = RequestConfig.custom().setConnectTimeout(timeout * 1000)
			        .setConnectionRequestTimeout(timeout * 1000).setSocketTimeout(timeout * 1000).build();
			client = HttpAsyncClients.custom().setDefaultRequestConfig(config).build();
			client.start();
			this.path = (String) settings.get("path");
			monitorURL(path);
			logger.info("ACTIVATED LONGPOLLER");
			setActive(true);
		} catch (Throwable e) {
			logger.error("Catch all: ", e);
		}
    }
	
	@Reference(unbind="clearConsulClient", policy=ReferencePolicy.DYNAMIC)
	public void setConsulClient(HttpJsonApi httpApi) {
		this.consulClient = httpApi;
	}

	public void clearConsulClient(HttpJsonApi httpApi) {
		this.consulClient = null;
	}

	@Reference(unbind="clearEventAdmin", policy=ReferencePolicy.DYNAMIC)
	public void setEventAdmin(EventAdmin eventAdmin) {
		this.eventAdmin = eventAdmin;
	}

	public void clearEventAdmin(EventAdmin eventAdmin) {
		this.eventAdmin = null;
	}
	
	@Reference(unbind="clearHttpCache",policy=ReferencePolicy.DYNAMIC)
	public void setHttpCache(HttpCache httpCache) {
		this.httpCache = httpCache;
	}
	
	public void clearHttpCache(HttpCache httpCache) {
		this.httpCache = null;
	}
	
	
	private void monitorURL(final String path) {
		if(!isActive()) {
			logger.warn("No not active, ignoring monitorURL");
			return;
		}
		Integer blockIndex = lastIndexes.get(path);
		String baseURL = this.consulClient.getHost()+path+(path.contains("?")?"&":"?")+"wait="+blockIntervalInSeconds+"s";
		final HttpGet get = (blockIndex!=null)?new HttpGet(baseURL+"&index="+blockIndex):new HttpGet(baseURL);
//		logger.info("Debug: {}",get.getURI());
        try {
        	LongPollingCallback callback = new LongPollingCallback(get, path, this);
        	currentCallbacks.put(path, callback);
        	client.execute(get,callback);
        } catch (Exception e) {
            logger.error("Got Exception on performing GET: ", e);
        }
	}

	
	void callFailed(String key, int responseCode) {
        logger.warn("Failed calling: "+key+" with code: "+responseCode+" sleeping to be sure");
        try {
			Thread.sleep(20000);
		} catch (InterruptedException e) {
		}
        monitorURL(key);
	}

	void valueChanged(String path, byte[] bs, Integer index, boolean recurse) {
		Integer prev = lastIndexes.get(path);
		byte[] old = lastValues.get(path);
		if(prev!=null && prev.equals(index)) {
		} else {
			lastIndexes.put(path, index);
			if (recurse) {
				JsonNode changes;
				try {
					changes = mapper.readTree(bs);
					processChanges(changes);
				} catch (JsonProcessingException e) {
					logger.error("Error: ", e);
				} catch (IOException e) {
					logger.error("Error: ", e);
				}
			} else {
		        lastValues.put(path,bs);
		        Map<String,Object> content = new HashMap<>();
		        content.put("old", old);
		        content.put("index",index);
		        Event event = createEventFromPath(path, content);
		        eventAdmin.sendEvent(event);
			}
		}
        monitorURL(path);
	}

	private Event createEventFromPath(String path, Map<String, Object> content) {
		String topic = null;
		if(path.indexOf('?')!=-1) {
			topic = path.split("\\?")[0];
		} else {
			topic = path;
		}
		Event event = new Event("consul"+topic,content);
		return event;
	}

	@Modified
	public void modify(Map<String,Object> newSettings) {
		logger.info("Modify");
		logger.info("Current: "+this.settings);
		logger.info("New: "+newSettings);
		if(this.settings.equals(newSettings)) {
			logger.info("Same settings");
		}
	}
	
	@Deactivate
	public void deactivate() {
		logger.info("DEACTIVATING LONGPOLLER");
		setActive(false);
		for (LongPollingCallback e : currentCallbacks.values()) {
			e.cancel();
		}
		try {
			client.close();
		} catch (IOException e1) {
			logger.error("Error: ", e1);
		}
		this.settings.clear();

		logger.info("DEACTIVATED LONGPOLLER");

	}

	void processChanges(JsonNode changes) {
		List<KeyChange> c = new ArrayList<>();
		for (JsonNode jsonNode : changes) {
			KeyChange kc = new KeyChange(jsonNode);
			c.add(kc);
		}
		ChangeEvent ce = new ChangeEvent(c);
		this.httpCache.processChange(ce);
        monitorURL(path);

	}

	private synchronized boolean isActive() {
		return active;
	}

	private synchronized void setActive(boolean active) {
		this.active = active;
	}

}
