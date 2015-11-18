/*

Copyright 2015 Stefano Terna

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

*/

package org.iottly.openfire.plugin;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.lang.reflect.*;

import org.apache.commons.httpclient.*;
import org.apache.commons.httpclient.methods.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.Message;


public class HTTPPoster implements Runnable {
	private HttpClient client = new HttpClient();
	private BlockingQueue<ForwardingMessage> messageQueue;
	private Set<String> skipTargets = new HashSet<String>();
	private static final Logger Log = LoggerFactory.getLogger(HTTPPoster.class);
	private static final int MAX_RETRIES = 20;
	private static final int MAX_DELAY = 60 * 5; // 5 minutes
	
	public HTTPPoster(BlockingQueue<ForwardingMessage> messageQueue, String targetUrl) {
		this.messageQueue = messageQueue;
	}

	public int postTo(String targetUrl, Map<String, Object> parameters) {
		Log.debug("Do the post for " + parameters.get("msg"));
		PostMethod post = new PostMethod(targetUrl);
		
		int statusCode = -1;
        post.addParameters(mapToPairsList(parameters));
		try {
			statusCode = client.executeMethod(post);
		} catch (Exception e) {
			Log.debug("Something went wrong in the HTTP post:" + e.getMessage());
		} finally {
			post.releaseConnection();
		}
		return statusCode;
	}
	
	/**
	 * Extracts the domain name from {@code url}
	 * by means of String manipulation
	 * rather than using the {@link URI} or {@link URL} class.
	 *
	 * @param url is non-null.
	 * @return the domain name within {@code url}.
	 */
	private String getUrlDomainName(String url) {
	  String domainName = new String(url);

	  int index = domainName.indexOf("://");

	  if (index != -1) {
	    // keep everything after the "://"
	    domainName = domainName.substring(index + 3);
	  }

	  index = domainName.indexOf(':');

	  if (index != -1) {
	    // keep everything before the '/'
	    domainName = domainName.substring(0, index);
	  }

	  index = domainName.indexOf('/');

	  if (index != -1) {
	    // keep everything before the '/'
	    domainName = domainName.substring(0, index);
	  }

	  // check for and remove a preceding 'www'
	  // followed by any sequence of characters (non-greedy)
	  // followed by a '.'
	  // from the beginning of the string
	  domainName = domainName.replaceFirst("^www.*?\\.", "");

	  return domainName;
	}

	private NameValuePair[] mapToPairsList(Map<String, Object> map) {
		NameValuePair list[] = new NameValuePair[map.size()];
		int i = 0;
		for (Map.Entry<String, Object> entry : map.entrySet()) {
			String k = entry.getKey();
			Object v = entry.getValue();

			if (k == null || v == null) {
				throw new IllegalArgumentException("No null value can be passed as HTTP parameter");
			}
			list[i++] = new NameValuePair(k, v.toString());
		}
		return list;
	}

	@Override
	public void run() {
		Log.debug("HTTPForward Plugin: Message Forwarder thread started");
		while(!Thread.currentThread().isInterrupted()){
			try {
				ForwardingMessage m = messageQueue.take();
				Log.debug("Consuming a message...");
				this.consumeMessage(m);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				Log.info("Message Forwarder Thread interrupted");
			}
		}
	}

	public void consumeMessage(ForwardingMessage fwdMsg) throws InterruptedException {
		Message msg = fwdMsg.getMessage();

		// Setup post parameters
		Map<String, Object> params = new HashMap<>();
		params.put("msg", msg.getBody());
		params.put("from", msg.getFrom());
		params.put("to", msg.getTo());

		int delay = 1;
		int retries = 0;
		int statusCode = -1;
		while (retries < MAX_RETRIES && statusCode != 200 && !skipTargets.contains(fwdMsg.getTargetUrl())) {
			try {
				statusCode = this.postTo(fwdMsg.getTargetUrl(), params);
			} catch (Exception e) {
				Log.debug("Exception in postTo: " + e.getMessage());
				// Skip this message, it's not a response error
				break;
			}
			if (statusCode != 200) {
				Log.info("The message following message received a status code of ["+statusCode+"]:" + msg);
				// Exponential retry fallback
				delay = (int) Math.min(Math.pow(1.8, (double) retries), MAX_DELAY);
				Log.debug("Retrying in " + delay + " seconds ...");
				Thread.sleep(delay * 1000);
				retries++;
			}
		}
		if (retries == MAX_RETRIES) {
			Log.debug("HTTP Forward Plugin, Too many retries, send failed!");
		} else {
			Log.debug("HTTP Forward Plugin, message successfully sent!");
		}
	}

	public Set<String> getSkipTargets() {
		return skipTargets;
	}

	public void setSkipTargets(Set<String> skipTargets) {
		this.skipTargets = skipTargets;
	}
}
