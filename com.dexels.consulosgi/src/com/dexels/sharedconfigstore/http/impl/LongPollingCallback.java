package com.dexels.sharedconfigstore.http.impl;

import java.io.IOException;

import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.concurrent.FutureCallback;
import org.codehaus.jackson.JsonGenerator;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.JsonProcessingException;
import org.codehaus.jackson.map.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dexels.sharedconfigstore.consul.LongPollingScheduler;

public class LongPollingCallback implements FutureCallback<HttpResponse> {

	
	private final static Logger logger = LoggerFactory.getLogger(LongPollingCallback.class);
	private final String key;
	private final HttpRequestBase request;
	private final ObjectMapper mapper;
	private final LongPollingScheduler scheduler;
	
	public LongPollingCallback(HttpRequestBase request, String key, LongPollingScheduler scheduler) {
		this.key = key;
		this.request = request;
		this.scheduler = scheduler;
		mapper = new ObjectMapper().configure(JsonGenerator.Feature.AUTO_CLOSE_TARGET, false);;
	}
	
	public void completed(final HttpResponse response) {
        int responseCode = response.getStatusLine().getStatusCode();
        if (responseCode >= 300) {
            // No data yet or something is broken. Lets sleep for a little while to prevent flooding the server
            logger.warn("Got responsecode {}: {} for url: {}", responseCode, response.getStatusLine().getReasonPhrase(),key);
            scheduler.callFailed(key, responseCode);
        }
        try {
			JsonNode reply = mapper.readTree(response.getEntity().getContent());
		    Integer index = getConsulIndex(response);
		    scheduler.valueChanged(key, reply,index);
		} catch (JsonProcessingException e) {
			logger.error("Error: ", e);
		} catch (IOException e) {
			logger.error("Error: ", e);
		}
    }
	
	private Integer getConsulIndex(HttpResponse response) {
        Header consulIndex = response.getFirstHeader("X-Consul-Index");
        if(consulIndex!=null) {
        	return Integer.parseInt(consulIndex.getValue());
        }
        return null;
	}

    public void failed(final Exception ex) {
    	scheduler.callFailed(key, -1);
    }

    public void cancelled() {
    	logger.warn("Call cancelled: "+request.getURI());
    	scheduler.callFailed(key, -1);
    }

    public void cancel() {
    	this.request.abort();
    }
}
