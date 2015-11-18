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

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.container.Plugin;
import org.jivesoftware.openfire.container.PluginManager;
import org.jivesoftware.openfire.interceptor.InterceptorManager;
import org.jivesoftware.openfire.interceptor.PacketInterceptor;
import org.jivesoftware.openfire.interceptor.PacketRejectedException;
import org.jivesoftware.openfire.roster.Roster;
import org.jivesoftware.openfire.roster.RosterManager;
import org.jivesoftware.openfire.session.Session;
import org.jivesoftware.util.JiveGlobals;
import org.jivesoftware.util.PropertyEventDispatcher;
import org.jivesoftware.util.PropertyEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.JID;
import org.xmpp.packet.Message;
import org.xmpp.packet.Packet;

public class HTTPForwardPlugin implements Plugin, PacketInterceptor,
		PropertyEventListener {
	private static final Logger Log = LoggerFactory
			.getLogger(HTTPForwardPlugin.class);
	private static final String TARGET_URL_KEY = "plugin.httpforward.target";
	private static final String ALLOWED_RECEIPIENTS_KEY = "plugin.httpforward.allowedRecipients";
	private static final String RECIPIENT_ROUTES_KEY = "plugin.httpforward.recipientRoutes";
	private static final String FLUSH_TARGETS_KEY = "plugin.httpforward.skipTargets";

	private Set<JID> allowedRecipients;
	private Map<JID, String> recipientRoutes;
	private String targetUrl;
	private RosterManager rosterManager;
	private HTTPPoster forwarder;
	private BlockingQueue<ForwardingMessage> messageQueue;
	private Thread messageThread;

	public static void debug(String msg) {
		Log.debug("HTTPForwardPlugin: " + msg);
	}

	public HTTPForwardPlugin() {
		this.targetUrl = JiveGlobals.getProperty(TARGET_URL_KEY,
				"http://requestb.in/qbvkftqc");
		this.allowedRecipients = stringToSet(JiveGlobals.getProperty(ALLOWED_RECEIPIENTS_KEY, ""));
		this.recipientRoutes = stringToMap(JiveGlobals.getProperty(RECIPIENT_ROUTES_KEY, ""));
		this.messageQueue = new LinkedBlockingQueue<>(10000);
		this.forwarder = new HTTPPoster(this.messageQueue, this.targetUrl);
		debug("Creating HTTPForward Plugin");
	}

	@Override
	public void initializePlugin(PluginManager manager, File pluginDirectory) {
		debug("Initializing HTTPForward Plugin");
		rosterManager = XMPPServer.getInstance().getRosterManager();

		InterceptorManager.getInstance().addInterceptor(this);
		PropertyEventDispatcher.addListener(this);
		this.messageThread = new Thread(this.forwarder);
		this.messageThread.start();
	}

	@Override
	public void destroyPlugin() {
		debug("Destroying HTTPForward Plugin");

		this.messageThread.interrupt();
		try {
			debug("Waiting for thread to close");
			this.messageThread.join(500);
			debug("Post join");
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		this.messageThread = null;
		PropertyEventDispatcher.removeListener(this);
		InterceptorManager.getInstance().removeInterceptor(this);

		rosterManager = null;
	}

	private String getTargetUrlForMessage(Message m) {
		JID recipient = m.getTo();
		if (recipientRoutes.containsKey(recipient)) {
			return recipientRoutes.get(recipient);
		}
		return this.targetUrl;
	}

	@Override
	public void interceptPacket(Packet packet, Session session,
			boolean incoming, boolean processed) throws PacketRejectedException {
		if (packet instanceof Message) {
			Message msg = (Message) packet;
			
			// Only forward messages incoming to the server, not sent by the
			// server
			// Only forward messages before they have been processed (otherwise
			// it forwards twice)
			if (incoming && processed) {
				if (shouldForwardMessage(msg)) {
					boolean added = this.messageQueue.add(new ForwardingMessage(msg, getTargetUrlForMessage(msg)));
					if (!added){
						debug("Queue is full! Unable to add msg to queue!");
					}
					debug("HTTPForward plugin should forward " + msg.getBody());
				} else {
					debug("HTTPForward plugin should not forward " + msg.getBody());
				}
			}
		}
	}

	private boolean shouldForwardMessage(Message m) {
		String senderUsername = m.getFrom().getNode();
		if (allowedRecipients.size() > 0
				&& !allowedRecipients.contains(m.getTo())) {
			debug("HTTPForward Plugin - recipient not in allowedRecipients "
					+ allowedRecipients + " " + m.getTo().getNode());
			return false;
		}
		Roster senderRoster = null;
		try {
			senderRoster = rosterManager.getRoster(m.getFrom().getNode());
		} catch (Exception e) {
			Log.error("No roster found for " + senderUsername + ": "
					+ e.getMessage());
		}

		if (senderRoster == null || !senderRoster.isRosterItem(m.getTo())) {
			debug("HTTPForward Plugin - recipient is not in sender's roster "
					+ senderUsername);
			return false;
		}
		return true;
	}

	@Override
	public void propertySet(String property, Map<String, Object> params) {
		String value = (String) params.get("value");
		debug("Property Set in HTTPForward Plugin: " + property + " -> "
				+ value);
		if (property.equals(TARGET_URL_KEY)) {
			this.targetUrl = value;
		} else if (property.equals(ALLOWED_RECEIPIENTS_KEY)) {
			this.allowedRecipients = stringToSet(value);
		} else if (property.equals(RECIPIENT_ROUTES_KEY)) {
			this.recipientRoutes = stringToMap(value);
		} else if (property.equals(FLUSH_TARGETS_KEY)) {
			this.forwarder.setSkipTargets(new HashSet<String>(Arrays.asList(value.split(","))));
		}
	}

	@Override
	public void propertyDeleted(String property, Map<String, Object> params) {
		debug("Property deleted in HTTPForward Plugin: " + property);
		if (property.equals(ALLOWED_RECEIPIENTS_KEY)) {
			this.allowedRecipients.clear();
		} else if (property.equals(RECIPIENT_ROUTES_KEY)) {
			this.recipientRoutes.clear();
		} else if (property.equals(FLUSH_TARGETS_KEY)) {
			this.forwarder.setSkipTargets(new HashSet<String>());
		}
	}

	@Override
	public void xmlPropertySet(String property, Map<String, Object> params) {}

	@Override
	public void xmlPropertyDeleted(String property, Map<String, Object> params) {}

	private JID stringToJID(String value) {
		JID jid = null;
		if (!value.equals("")) {
			try {
				// See if this is a full JID or just a username.
				if (value.contains("@")) {
					jid = new JID(value);
				} else {
					jid = XMPPServer.getInstance().createJID(value, null);
				}
			} catch (Exception e){
				Log.warn("Failed to transform property to set of JIDS: " + e.getMessage());
			}
		}
		return jid;
	}

	private Map<JID, String> stringToMap(String usersToUrls) {
		Map<JID,String> routes = new HashMap<>();
		if (usersToUrls == null) {
			usersToUrls = "";
		}
		StringTokenizer tokens = new StringTokenizer(usersToUrls, ",");
		while (tokens.hasMoreTokens()) {
			String user = tokens.nextToken().trim();
			// The srting contains an odd number of tokens, a mismatched pair is discarded
			if (!tokens.hasMoreTokens())
				break;

			String target = tokens.nextToken().trim();
			JID userJID = stringToJID(user);
			if (userJID == null)
				continue;
			routes.put(userJID, target);
		}
		return routes;
	}

	private Set<JID> stringToSet(String users) {
		Set<JID> values = new HashSet<JID>();
		StringTokenizer tokens = new StringTokenizer(users, ",");
		while (tokens.hasMoreTokens()) {
			String value = tokens.nextToken().trim();
			JID userJID = stringToJID(value);
			if (userJID == null)
				continue;
			values.add(userJID);
		}
		return values;
	}

	@Override
	protected void finalize() throws Throwable {
		if (this.messageThread != null) {
			this.messageThread.interrupt();
			debug("In finalize, waiting 5 secs for thread to close");
			this.messageThread.join(5000);
			debug("In finalize, post join");
			this.messageThread = null;
		}
		super.finalize();
	}
}
