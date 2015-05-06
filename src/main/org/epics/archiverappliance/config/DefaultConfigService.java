/*******************************************************************************
 * Copyright (c) 2011 The Board of Trustees of the Leland Stanford Junior University
 * as Operator of the SLAC National Accelerator Laboratory.
 * Copyright (c) 2011 Brookhaven National Laboratory.
 * EPICS archiver appliance is distributed subject to a Software License Agreement found
 * in file LICENSE that is included with this distribution.
 *******************************************************************************/
package org.epics.archiverappliance.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.PlatformLoggingMXBean;
import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletContext;

import org.apache.commons.validator.routines.InetAddressValidator;
import org.apache.log4j.Logger;
import org.epics.archiverappliance.StoragePlugin;
import org.epics.archiverappliance.common.ProcessMetrics;
import org.epics.archiverappliance.common.TimeUtils;
import org.epics.archiverappliance.config.PVTypeInfoEvent.ChangeType;
import org.epics.archiverappliance.config.exception.AlreadyRegisteredException;
import org.epics.archiverappliance.config.exception.ConfigException;
import org.epics.archiverappliance.config.persistence.MySQLPersistence;
import org.epics.archiverappliance.config.pubsub.PubSubEvent;
import org.epics.archiverappliance.engine.ArchiveEngine;
import org.epics.archiverappliance.engine.pv.EngineContext;
import org.epics.archiverappliance.engine.pv.EPICSV4.ArchiveEngine_EPICSV4;
import org.epics.archiverappliance.etl.common.PBThreeTierETLPVLookup;
import org.epics.archiverappliance.mgmt.MgmtPostStartup;
import org.epics.archiverappliance.mgmt.MgmtRuntimeState;
import org.epics.archiverappliance.mgmt.NonMgmtPostStartup;
import org.epics.archiverappliance.mgmt.bpl.cahdlers.NamesHandler;
import org.epics.archiverappliance.mgmt.policy.ExecutePolicy;
import org.epics.archiverappliance.mgmt.policy.PolicyConfig;
import org.epics.archiverappliance.mgmt.policy.PolicyConfig.SamplingMethod;
import org.epics.archiverappliance.retrieval.RetrievalState;
import org.epics.archiverappliance.retrieval.channelarchiver.XMLRPCClient;
import org.epics.archiverappliance.utils.ui.GetUrlContent;
import org.epics.archiverappliance.utils.ui.JSONDecoder;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.xml.sax.SAXException;

import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.config.Config;
import com.hazelcast.config.XmlConfigBuilder;
import com.hazelcast.core.Cluster;
import com.hazelcast.core.EntryEvent;
import com.hazelcast.core.EntryListener;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.IMap;
import com.hazelcast.core.ITopic;
import com.hazelcast.core.MapEvent;
import com.hazelcast.core.Member;
import com.hazelcast.core.MemberAttributeEvent;
import com.hazelcast.core.MembershipEvent;
import com.hazelcast.core.MembershipListener;
import com.hazelcast.core.Message;
import com.hazelcast.core.MessageListener;

import edu.stanford.slac.archiverappliance.PB.data.PBTypeSystem;



/**
 * This is the default config service for the archiver appliance.
 * There is a subclass that is used in the junit tests.
 * 
 * @author mshankar
 *
 */
public class DefaultConfigService implements ConfigService {
	private static Logger logger = Logger.getLogger(DefaultConfigService.class.getName());
	private static Logger configlogger = Logger.getLogger("config." + DefaultConfigService.class.getName());
	private static Logger clusterLogger = Logger.getLogger("cluster." + DefaultConfigService.class.getName());
	
	public static final String SITE_FOR_UNIT_TESTS_NAME = "org.epics.archiverappliance.config.site";
	public static final String SITE_FOR_UNIT_TESTS_VALUE = "tests";
	
	/**
	 * Add a property in archappl.properties under this key to identify the class that implements the PVNameToKeyMapping for this installation.
	 */
	public static final String ARCHAPPL_PVNAME_TO_KEY_MAPPING_CLASSNAME = "org.epics.archiverappliance.config.DefaultConfigService.PVName2KeyMappingClassName";

	
	// Configuration state begins here.
	protected String myIdentity;
	protected ApplianceInfo myApplianceInfo = null;
	protected Map<String, ApplianceInfo> appliances = null;
	// Persisted state begins here.
	protected Map<String, PVTypeInfo> typeInfos = null;
	protected Map<String, UserSpecifiedSamplingParams> archivePVRequests = null;
	protected Map<String, String> channelArchiverDataServers = null;
	protected Map<String, String> aliasNamesToRealNames = null;
	// These are not persisted but derived from other info
	protected Map<String, ApplianceInfo> pv2appliancemapping = null;
	protected Map<String, String> clusterInet2ApplianceIdentity = null;
	protected Map<String, List<ChannelArchiverDataServerPVInfo>> pv2ChannelArchiverDataServer = null;
	protected ITopic<PubSubEvent> pubSub = null;
	// Configuration state ends here.
	
	// Runtime state begins here 
	protected LinkedList<Runnable> shutdownHooks = new LinkedList<Runnable>();
	protected PBThreeTierETLPVLookup etlPVLookup = null;
	protected RetrievalState retrievalState = null;
	protected MgmtRuntimeState mgmtRuntime = null;;
	protected EngineContext engineContext = null;
	protected ConcurrentSkipListSet<String> appliancesInCluster = new ConcurrentSkipListSet<String>();
	// Runtime state ends here 

	// This is an optimization; we cache a copy of PVs that are registered for this appliance.
	protected ConcurrentSkipListSet<String> pvsForThisAppliance = null;
	protected ConcurrentSkipListSet<String> pausedPVsForThisAppliance = null;
	protected ApplianceAggregateInfo applianceAggregateInfo = new ApplianceAggregateInfo();
	protected EventBus eventBus = new EventBus();
	protected Properties archapplproperties = new Properties();
	protected PVNameToKeyMapping pvName2KeyConverter = null;
	protected ConfigPersistence persistanceLayer;

	// State local to DefaultConfigService.
	protected WAR_FILE warFile = WAR_FILE.MGMT;
	protected STARTUP_SEQUENCE startupState = STARTUP_SEQUENCE.ZEROTH_STATE;
	protected ScheduledExecutorService startupExecutor = null;
	protected ProcessMetrics processMetrics = new ProcessMetrics();
	private HashSet<String> runTimeFields = new HashSet<String>();

	private ServletContext servletContext;

	protected DefaultConfigService() {
		// Only the unit tests config service uses this constructor.
	}

	@Override
	public void initialize(ServletContext sce) throws ConfigException {
		this.servletContext = sce;
		String contextPath = sce.getContextPath();
		logger.info("DefaultConfigService was created with a servlet context " + contextPath);
		try {
			// We first try Java system properties for this appliance's identity
			// If a property is not defined, then we check the environment.
			// This gives us the ability to cater to unit tests as well as running using buildAndDeploy scripts without touching the server.xml file.
			// Probably not the most standard way but suited to this need.
			// Finally, we use the local machine's hostname as the myidentity.
			myIdentity = System.getProperty(ARCHAPPL_MYIDENTITY);
			if(myIdentity == null) {
				myIdentity = System.getenv(ARCHAPPL_MYIDENTITY);
				if(myIdentity != null) { 
					logger.info("Obtained my identity from environment variable " + myIdentity);
				} else { 
					logger.info("Using the local machine's hostname " + myIdentity + " as my identity");
					myIdentity = InetAddress.getLocalHost().getCanonicalHostName();
				}
				if(myIdentity == null) {
					throw new ConfigException("Unable to determine identity of this appliance");
				}
			} else {
				logger.info("Obtained my identity from Java system properties " + myIdentity);
			}

			logger.info("My identity is " + myIdentity);
		} catch(Exception ex) {
			String msg = "Cannot determine this appliance's identity using either the environment variable " + ARCHAPPL_MYIDENTITY + " or the java system property " + ARCHAPPL_MYIDENTITY;
			configlogger.fatal(msg); 
			throw new ConfigException(msg, ex);
		}
		// Appliances should be local and come straight from persistence.
		try {
			appliances = AppliancesList.loadAppliancesXML(servletContext);
		} catch(Exception ex) {
			throw new ConfigException("Exception loading appliances.xml", ex);
		}
		
		myApplianceInfo = appliances.get(myIdentity);
		if(myApplianceInfo == null) throw new ConfigException("Unable to determine applianceinfo using identity " + myIdentity);
		configlogger.info("My identity is " + myApplianceInfo.getIdentity() + " and my mgmt URL is " + myApplianceInfo.getMgmtURL());
		
		// To make sure we are not starting multiple appliance with the same identity, we make sure that the hostnames match
		try { 
			String machineHostName = InetAddress.getLocalHost().getCanonicalHostName();
			String[] myAddrParts = myApplianceInfo.getClusterInetPort().split(":");
			String myHostNameFromInfo = myAddrParts[0];
			if(myHostNameFromInfo.equals("localhost")) { 
				logger.debug("Using localhost for the cluster inet port. If you are indeed running a cluster, the cluster members will not join the cluster.");
			} else if (myHostNameFromInfo.equals(machineHostName)) { 
				logger.debug("Hostname from config and hostname from InetAddress match exactly; we are correctly configured " + machineHostName);				
			} else if(InetAddressValidator.getInstance().isValid(myHostNameFromInfo)) {
				logger.debug("Using ipAddress for cluster config " + myHostNameFromInfo);				
			} else { 
				String msg = "The hostname from appliances.xml is " + myHostNameFromInfo + " and from a call to InetAddress.getLocalHost().getCanonicalHostName() (typially FQDN) is " + machineHostName 
						+ ". These are not identical. They are probably equivalent but to prevent multiple appliances binding to the same identity we enforce this equality.";
				configlogger.fatal(msg);
				throw new ConfigException(msg);
			}
		} catch(UnknownHostException ex) { 
			configlogger.error("Got an UnknownHostException when trying to determine the hostname. This happens when DNS is not set correctly on this machine (for example, when using VM's. See the documentation for InetAddress.getLocalHost().getCanonicalHostName()");
		}

		try { 
			String archApplPropertiesFileName = System.getProperty(ARCHAPPL_PROPERTIES_FILENAME);
			if(archApplPropertiesFileName == null) {
				archApplPropertiesFileName = System.getenv(ARCHAPPL_PROPERTIES_FILENAME);
			} 
			if(archApplPropertiesFileName == null) {
				archApplPropertiesFileName = new URL(this.getClass().getClassLoader().getResource(DEFAULT_ARCHAPPL_PROPERTIES_FILENAME).toString()).getPath();
				configlogger.info("Loading archappl.properties from the webapp classpath " + archApplPropertiesFileName);
			} else { 
				configlogger.info("Loading archappl.properties using the environment/JVM property from " + archApplPropertiesFileName);
			}
			try(InputStream is = new FileInputStream(new File(archApplPropertiesFileName))) {
				archapplproperties.load(is);
				configlogger.info("Done loading installation specific properties file from " + archApplPropertiesFileName);
			} catch(Exception ex) {
				throw new ConfigException("Exception loading installation specific properties file " + archApplPropertiesFileName, ex);
			}
		} catch(ConfigException cex) { 
			throw cex;
		} catch(Exception ex) { 
			configlogger.fatal("Exception loading the appliance properties file", ex);
		}
		
		switch(contextPath) {
		case "/mgmt":
			warFile = WAR_FILE.MGMT;
			this.mgmtRuntime = new MgmtRuntimeState(this);
			break;
		case "/engine":
			warFile = WAR_FILE.ENGINE;
			this.engineContext=new EngineContext(this);
			break;
		case "/retrieval":
			warFile = WAR_FILE.RETRIEVAL;
			this.retrievalState = new RetrievalState(this);
			break;
		case "/etl":
			this.etlPVLookup = new PBThreeTierETLPVLookup(this);
			warFile = WAR_FILE.ETL;
			break;
		}
		
		

		String pvName2KeyMappingClass = this.getInstallationProperties().getProperty(ARCHAPPL_PVNAME_TO_KEY_MAPPING_CLASSNAME);
		if(pvName2KeyMappingClass == null || pvName2KeyMappingClass.equals("") || pvName2KeyMappingClass.length() < 1) {
			logger.info("Using the default key mapping class");
			pvName2KeyConverter = new ConvertPVNameToKey();
			pvName2KeyConverter.initialize(this);
		} else { 
			try { 
				logger.info("Using " + pvName2KeyMappingClass + " as the name to key mapping class");
				pvName2KeyConverter = (PVNameToKeyMapping) Class.forName(pvName2KeyMappingClass).newInstance();
				pvName2KeyConverter.initialize(this);
			} catch(Exception ex) { 
				logger.fatal("Cannot initialize pv name to key mapping class " + pvName2KeyMappingClass, ex);
				throw new ConfigException("Cannot initialize pv name to key mapping class " + pvName2KeyMappingClass, ex);			
			}
		}

		String runtimeFieldsListStr = this.getInstallationProperties().getProperty("org.epics.archiverappliance.config.RuntimeKeys");
		if(runtimeFieldsListStr != null && !runtimeFieldsListStr.isEmpty()) { 
			logger.debug("Got runtime fields from the properties file " + runtimeFieldsListStr);
			String[] runTimeFieldsArr = runtimeFieldsListStr.split(",");
			for(String rf : runTimeFieldsArr) {
				this.runTimeFields.add(rf.trim());
			}
		}
				
		
		startupExecutor = Executors.newScheduledThreadPool(1, new ThreadFactory() {
			@Override
			public Thread newThread(Runnable r) {
				Thread t = new Thread(r);
				t.setName("Startup executor");
				return t;
			}
		});
		
		this.addShutdownHook(new Runnable() {
			@Override
			public void run() {
				logger.info("Shutting down startup scheduled executor...");
				startupExecutor.shutdown();
			}
		});
		
		this.startupState = STARTUP_SEQUENCE.READY_TO_JOIN_APPLIANCE;
		if(this.warFile == WAR_FILE.MGMT) {
			logger.info("Scheduling webappReady's for the mgmt webapp ");
			MgmtPostStartup mgmtPostStartup = new MgmtPostStartup(this);
			ScheduledFuture<?> postStartupFuture = startupExecutor.scheduleAtFixedRate(mgmtPostStartup, 10, 20, TimeUnit.SECONDS);
			mgmtPostStartup.setCancellingFuture(postStartupFuture);
		} else {
			logger.info("Scheduling webappReady's for the non-mgmt webapp " + this.warFile.toString());
			NonMgmtPostStartup nonMgmtPostStartup = new NonMgmtPostStartup(this, this.warFile.toString());
			ScheduledFuture<?> postStartupFuture = startupExecutor.scheduleAtFixedRate(nonMgmtPostStartup, 10, 20, TimeUnit.SECONDS);
			nonMgmtPostStartup.setCancellingFuture(postStartupFuture);
		}
		
		// Measure some JMX metrics once a minute
		startupExecutor.scheduleAtFixedRate(new Runnable() {
			@Override
			public void run() {
				processMetrics.takeMeasurement();
			}
		}, 60, 60, TimeUnit.SECONDS);
	}
	
	
	/* (non-Javadoc)
	 * @see org.epics.archiverappliance.config.ConfigService#postStartup()
	 */
	@Override
	public void postStartup() throws ConfigException {
		if(this.startupState != STARTUP_SEQUENCE.READY_TO_JOIN_APPLIANCE) {
			configlogger.info("Webapp is not in correct state for postStartup " + this.getWarFile().toString() + ". It is in " + this.startupState.toString());
			return;
		}
		
		this.startupState = STARTUP_SEQUENCE.POST_STARTUP_RUNNING;
		configlogger.info("Post startup for " + this.getWarFile().toString());

		// Inherit logging from log4j configuration.
		try { 
			PlatformLoggingMXBean logging = ManagementFactory.getPlatformMXBean(PlatformLoggingMXBean.class);
			if(logging != null) {
				java.util.logging.Logger.getLogger("com.hazelcast");
				if(clusterLogger.isDebugEnabled()) { 
					logging.setLoggerLevel("com.hazelcast", java.util.logging.Level.FINE.toString());
				} else if(clusterLogger.isInfoEnabled()) { 
					logging.setLoggerLevel("com.hazelcast", java.util.logging.Level.INFO.toString());
				} else { 
					logger.info("Setting clustering logging based on log levels for cluster." + getClass().getName());
					logging.setLoggerLevel("com.hazelcast", java.util.logging.Level.SEVERE.toString());
				}
			}
		} catch(Exception ex) { 
			logger.error("Exception setting logging JVM levels ", ex);
		}

		HazelcastInstance hzinstance = null;
		
		if(this.warFile == WAR_FILE.MGMT) {
			// The management webapps are the head honchos in the cluster. We set them up differently

			configlogger.debug("Initializing the MGMT webapp's clustering");
			// If we have a hazelcast.xml in the servlet classpath, the XmlConfigBuilder picks that up.
			// If not we use the default config found in hazelcast.jar
			// We then alter this config to suit our purposes.
			Config config = new XmlConfigBuilder().build();
			try {
				if(this.getClass().getResource("hazelcast.xml") == null) {
					logger.info("We override the default cluster config by disabling multicast discovery etc.");
					// We do not use multicast as it is not supported on all networks.
					config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
					// We use TCPIP to discover the members in the cluster.
					// This is part of the config that comes from appliance.xml
					config.getNetworkConfig().getJoin().getTcpIpConfig().setEnabled(true);
					// Clear any tcpip config that comes from the default config
					// This gets rid of the localhost in the default that prevents clusters from forming..
					// If we need localhost, we'll add it back later.
					config.getNetworkConfig().getJoin().getTcpIpConfig().clear();
					// Enable interfaces; we seem to need this after 2.4 for clients to work correctly in a multi-homed environment.
					// We'll add the actual interface later below
					config.getNetworkConfig().getInterfaces().setEnabled(true);
					config.getNetworkConfig().getInterfaces().clear();
					
					// We don't really use the authentication provided by the tool; however, we set it to some default
					config.getGroupConfig().setName("archappl");
					config.getGroupConfig().setPassword("archappl");

					// Backup count is 1 by default; we set it explicitly however...
					config.getMapConfig("default").setBackupCount(1);
					
					config.setProperty("hazelcast.logging.type", "none");
				} else {
					logger.debug("There is a hazelcast.xml in the classpath; skipping default configuration in the code.");
				}
			} catch(Exception ex) {
				throw new ConfigException("Exception configuring cluster", ex);
			}

			config.setInstanceName(myIdentity);
			try {
				String[] myAddrParts = myApplianceInfo.getClusterInetPort().split(":");
				String myHostName = myAddrParts[0];
				InetAddress myInetAddr = InetAddress.getByName(myHostName);
				if(!myHostName.equals("localhost") && myInetAddr.isLoopbackAddress()) { 
					logger.info("Address for this appliance -- " + myInetAddr.toString() + " is a loopback address. Changing this to 127.0.0.1 to clustering happy");
					myInetAddr = InetAddress.getByName("127.0.0.1");
				}
				int myClusterPort = Integer.parseInt(myAddrParts[1]);
				
				logger.debug("We do not let the port auto increment for the MGMT webap");
				config.getNetworkConfig().setPortAutoIncrement(false);				

				config.getNetworkConfig().setPort(myClusterPort);
				config.getNetworkConfig().getInterfaces().addInterface(myInetAddr.getHostAddress());
				configlogger.info("Setting my cluster port base to " + myClusterPort + " and using interface " + myInetAddr.getHostAddress());
				
				for(ApplianceInfo applInfo : appliances.values()) {
					if(applInfo.getIdentity().equals(myIdentity) && this.warFile == WAR_FILE.MGMT) { 
						logger.debug("Not adding myself to the discovery process when I am the mgmt webapp");
					} else { 
						String[] addressparts = applInfo.getClusterInetPort().split(":");
						String inetaddrpart = addressparts[0];
						InetAddress inetaddr = InetAddress.getByName(inetaddrpart);
						if(!inetaddrpart.equals("localhost") && inetaddr.isLoopbackAddress()) { 
							logger.info("Address for appliance " + applInfo.getIdentity() + " -  "+ inetaddr.toString() + " is a loopback address. Changing this to 127.0.0.1 to clustering happy");
							inetaddr = InetAddress.getByName("127.0.0.1");
						}
						int clusterPort = Integer.parseInt(addressparts[1]);
						logger.info("Adding " + applInfo.getIdentity() + " from appliances.xml to the cluster discovery using cluster inetport " + inetaddr.toString() + ":" + clusterPort);
						config.getNetworkConfig().getJoin().getTcpIpConfig().addMember(inetaddr.getHostAddress() + ":" + clusterPort);
					}
				}
				hzinstance = Hazelcast.newHazelcastInstance(config);
			} catch(Exception ex) {
				throw new ConfigException("Exception adding member to cluster", ex);
			}
		} else {
			// All other webapps are "native" clients.
			try { 
				configlogger.debug("Initializing a non-mgmt webapp's clustering");
				ClientConfig clientConfig = new ClientConfig();
				clientConfig.getGroupConfig().setName("archappl");
				clientConfig.getGroupConfig().setPassword("archappl");
				// Non mgmt client can only connect to their MGMT webapp.
				String[] myAddrParts = myApplianceInfo.getClusterInetPort().split(":");
				String myHostName = myAddrParts[0];
				InetAddress myInetAddr = InetAddress.getByName(myHostName);
				if(!myHostName.equals("localhost") && myInetAddr.isLoopbackAddress()) { 
					logger.info("Address for this appliance -- " + myInetAddr.toString() + " is a loopback address. Changing this to 127.0.0.1 to clustering happy");
					myInetAddr = InetAddress.getByName("127.0.0.1");
				}
				int myClusterPort = Integer.parseInt(myAddrParts[1]);
	
				configlogger.debug(this.warFile + " connecting as a native client to " + myInetAddr.getHostAddress() + ":" + myClusterPort);
				clientConfig.getNetworkConfig().addAddress(myInetAddr.getHostAddress() + ":" + myClusterPort);
				hzinstance = HazelcastClient.newHazelcastClient(clientConfig);
			} catch(Exception ex) {
				throw new ConfigException("Exception adding client to cluster", ex);
			}
		}
		
		
		
		pv2appliancemapping = hzinstance.getMap("pv2appliancemapping");
		typeInfos = hzinstance.getMap("typeinfo");
		archivePVRequests = hzinstance.getMap("archivePVRequests");
		channelArchiverDataServers = hzinstance.getMap("channelArchiverDataServers");
		clusterInet2ApplianceIdentity = hzinstance.getMap("clusterInet2ApplianceIdentity");
		aliasNamesToRealNames = hzinstance.getMap("aliasNamesToRealNames");
		pv2ChannelArchiverDataServer = hzinstance.getMap("pv2ChannelArchiverDataServer");
		pubSub = hzinstance.getTopic("pubSub");
		
		final HazelcastInstance shutdownHzInstance = hzinstance;
		shutdownHooks.add(0, new Runnable() {
			@Override
			public void run() {
				configlogger.info("Shutting down clustering instance in webapp " + warFile.toString());
				shutdownHzInstance.shutdown();
			}
		});

		if(this.warFile == WAR_FILE.MGMT) {
			Cluster cluster = hzinstance.getCluster();
			String localInetPort = getMemberKey(cluster.getLocalMember());
			clusterInet2ApplianceIdentity.put(localInetPort, myIdentity);
			logger.debug("Adding myself " + myIdentity + " as having inetport " + localInetPort);
			hzinstance.getMap("clusterInet2ApplianceIdentity").addEntryListener(new EntryListener<Object, Object>() {
				@Override
				public void entryUpdated(EntryEvent<Object, Object> event) {
				}
				
				@Override
				public void entryRemoved(EntryEvent<Object, Object> event) {
					String appliden = (String) event.getValue();
					appliancesInCluster.remove(appliden);
					logger.info("Removing appliance " + appliden + " from the list of active appliancesas inetport " + ((String) event.getKey()));
				}
				
				@Override
				public void entryEvicted(EntryEvent<Object, Object> event) {
				}
				
				@Override
				public void entryAdded(EntryEvent<Object, Object> event) {
					String appliden = (String) event.getValue();
					appliancesInCluster.add(appliden);
					logger.info("Adding appliance " + appliden + " to the list of active appliances as inetport " + ((String) event.getKey()));
				}

				@Override
				public void mapCleared(MapEvent arg0) {
					logger.debug("Ignoring mapClearedEvent");
				}

				@Override
				public void mapEvicted(MapEvent arg0) {
					logger.debug("Ignoring mapEvictedEvent");
				}
			}, true);
			
			logger.debug("Establishing a cluster membership listener to detect when appliances drop off the cluster");
			cluster.addMembershipListener(new MembershipListener(){
				public void memberAdded(MembershipEvent membersipEvent) {
					Member member = membersipEvent.getMember();
					String inetPort = getMemberKey(member);
					if(clusterInet2ApplianceIdentity.containsKey(inetPort)) {
						String appliden = clusterInet2ApplianceIdentity.get(inetPort);
						appliancesInCluster.add(appliden);
						configlogger.info("Adding newly started appliance " + appliden + " to the list of active appliances for inetport " + inetPort);
					} else {
						logger.debug("Skipping adding appliance using inetport " + inetPort + " to the list of active instances as we do not have a mapping to its identity");
					}
				}
				public void memberRemoved(MembershipEvent membersipEvent) {
					Member member = membersipEvent.getMember();
					String inetPort = getMemberKey(member);
					if(clusterInet2ApplianceIdentity.containsKey(inetPort)) {
						String appliden = clusterInet2ApplianceIdentity.get(inetPort);
						appliancesInCluster.remove(appliden);
						configlogger.info("Removing appliance " + appliden + " from the list of active appliances");
					} else {
						configlogger.debug("Received member removed event for " + inetPort);
					}
				}
				@Override
				public void memberAttributeChanged(MemberAttributeEvent membersipEvent) {
					Member member = membersipEvent.getMember();
					String inetPort = getMemberKey(member);
					configlogger.debug("Received membership attribute changed event for " + inetPort);
				}
			});

			logger.debug("Adding the current members in the cluster after establishing the cluster membership listener");
			for (Member member : cluster.getMembers()) {
				String mbrInetPort = getMemberKey(member);
				logger.debug("Found member " + mbrInetPort);
				if(clusterInet2ApplianceIdentity.containsKey(mbrInetPort)) {
					String appliden = clusterInet2ApplianceIdentity.get(mbrInetPort);
					appliancesInCluster.add(appliden);
					logger.info("Adding appliance " + appliden + " to the list of active appliances for inetport " + mbrInetPort);
				} else {
					logger.debug("Skipping adding appliance using inetport " + mbrInetPort + " to the list of active instances as we do not have a mapping to its identity");
				}
			}
			logger.info("Established subscription(s) for appliance availability");
		}
		
		if(this.warFile == WAR_FILE.ENGINE) {
			// It can take a while for the engine to start up.
			// We probably want to do this in the background so that the appliance as a whole starts up quickly and we get retrieval up and running quickly.
			this.startupExecutor.schedule(new Runnable() {
				@Override
				public void run() {
					try { 
						logger.debug("Starting up the engine's channels on startup.");
						archivePVSonStartup();
						logger.debug("Done starting up the engine's channels in startup.");
					} catch(Throwable t) { 
						configlogger.fatal("Exception starting up the engine channels on startup", t);
					}
				}
			}, 15, TimeUnit.SECONDS); 
		} else if(this.warFile == WAR_FILE.ETL) {
			this.etlPVLookup.postStartup();
		} else if(this.warFile == WAR_FILE.MGMT) {
			pvsForThisAppliance = new ConcurrentSkipListSet<String>();
			pausedPVsForThisAppliance = new ConcurrentSkipListSet<String>();
			
			initializePersistenceLayer();

			loadTypeInfosFromPersistence();
			
			loadAliasesFromPersistence();

			loadArchiveRequestsFromPersistence();
			
			loadExternalServersFromPersistence();
			
			registerForNewExternalServers(hzinstance.getMap("channelArchiverDataServers"));

			// Cache the aggregate of all the PVs that are registered to this appliance.
			logger.debug("Building a local aggregate of PV infos that are registered to this appliance");
			for(String pvName : getPVsForThisAppliance()) {
				if(!pvsForThisAppliance.contains(pvName)) {
					applianceAggregateInfo.addInfoForPV(pvName, this.getTypeInfoForPV(pvName), this);
				}
			}
		}
		
		// Register for changes to the typeinfo map.
		logger.info("Registering for changes to typeinfos");
		hzinstance.getMap("typeinfo").addEntryListener(new EntryListener<Object, Object>() {

			@Override
			public void entryUpdated(EntryEvent<Object, Object> entryEvent) {
				PVTypeInfo typeInfo =(PVTypeInfo) entryEvent.getValue();
				String pvName = typeInfo.getPvName();
				eventBus.post(new PVTypeInfoEvent(pvName, typeInfo, ChangeType.TYPEINFO_MODIFIED));
				logger.debug("Received entryUpdated for pvTypeInfo");
				if(persistanceLayer != null) { 
					try { 
						persistanceLayer.putTypeInfo(pvName, typeInfo);
					} catch(Exception ex) { 
						logger.error("Exception persisting pvTypeInfo for pv " + pvName, ex);
					}
				}
			}

			@Override
			public void entryRemoved(EntryEvent<Object, Object> entryEvent) {
				logger.debug("Received entryRemoved for pvTypeInfo");
				PVTypeInfo typeInfo =(PVTypeInfo) entryEvent.getValue();
				String pvName = typeInfo.getPvName();
				eventBus.post(new PVTypeInfoEvent(pvName, typeInfo, ChangeType.TYPEINFO_DELETED));
				if(persistanceLayer != null) { 
					try { 
						persistanceLayer.deleteTypeInfo(pvName);
					} catch(Exception ex) { 
						logger.error("Exception deleting pvTypeInfo for pv " + pvName, ex);
					}
				}
			}

			@Override
			public void entryEvicted(EntryEvent<Object, Object> entryEvent) {
				logger.debug("Not processing the evicted event");
			}

			@Override
			public void entryAdded(EntryEvent<Object, Object> entryEvent) {
				logger.debug("Received entryAdded for pvTypeInfo");
				PVTypeInfo typeInfo = (PVTypeInfo) entryEvent.getValue();
				String pvName = typeInfo.getPvName();
				eventBus.post(new PVTypeInfoEvent(pvName, typeInfo, ChangeType.TYPEINFO_ADDED));
				if(persistanceLayer != null) { 
					try { 
						persistanceLayer.putTypeInfo(pvName, typeInfo);
					} catch(Exception ex) { 
						logger.error("Exception persisting pvTypeInfo for pv " + pvName, ex);
					}
				}
			}

			@Override
			public void mapCleared(MapEvent arg0) {
				logger.debug("Ignoring mapClearedEvent");
			}

			@Override
			public void mapEvicted(MapEvent arg0) {
				logger.debug("Ignoring mapEvictedEvent");
			}
		}, true);
		
		
		eventBus.register(this);
		
		pubSub.addMessageListener(new MessageListener<PubSubEvent>() {
			@Override
			public void onMessage(Message<PubSubEvent> pubSubEventMsg) {
				PubSubEvent pubSubEvent = pubSubEventMsg.getMessageObject();
				if(pubSubEvent.getDestination() != null) {
					if(pubSubEvent.getDestination().equals("ALL") || pubSubEvent.getDestination().equals(myIdentity)) {
						logger.debug("Publishing event from other appliances into this JVM " + pubSubEvent.generateEventDescription());
						pubSubEvent.setLocalOrigin(false);
						eventBus.post(pubSubEvent);
					} else { 
						logger.debug("Skipping publishing event from other appliances into this JVM " + pubSubEvent.generateEventDescription() + " as destination is not me " + pubSubEvent.getDestination());
					}
				} else {
					logger.debug("Skipping publishing event with null destination");
				}
			}
		});
		
		logger.info("Done registering for changes to typeinfos");

		
		this.startupState = STARTUP_SEQUENCE.STARTUP_COMPLETE;
		configlogger.info("Start complete for webapp " + this.warFile);
	}

	@Override
	public STARTUP_SEQUENCE getStartupState() {
		return this.startupState;
	}

	@Subscribe public void updatePVSForThisAppliance(PVTypeInfoEvent event) {
		if(logger.isDebugEnabled()) logger.debug("Received pvTypeInfo change event for pv " + event.getPvName());
		PVTypeInfo typeInfo = event.getTypeInfo();
		String pvName = typeInfo.getPvName();
		if(typeInfo.getApplianceIdentity().equals(myApplianceInfo.getIdentity())) {
			if(event.getChangeType() == ChangeType.TYPEINFO_DELETED) {
				if(pvsForThisAppliance != null) {
					if(pvsForThisAppliance.contains(pvName)) {
						logger.debug("Removing pv " + pvName + " from the locally cached copy of pvs for this appliance");
						pvsForThisAppliance.remove(pvName);
						pausedPVsForThisAppliance.remove(pvName);
						// For now, we do not anticipate many PVs being deleted from the cache to worry about keeping applianceAggregateInfo upto date...
						// This may change later... 
					}
				}
			} else {
				if(pvsForThisAppliance != null) {
					if(!pvsForThisAppliance.contains(pvName)) {
						logger.debug("Adding pv " + pvName + " to the locally cached copy of pvs for this appliance");
						pvsForThisAppliance.add(pvName);
						if(typeInfo.isPaused()) { 
							pausedPVsForThisAppliance.add(typeInfo.getPvName());
						}
						applianceAggregateInfo.addInfoForPV(pvName, typeInfo, this);
					} else { 
						if(typeInfo.isPaused()) { 
							pausedPVsForThisAppliance.add(typeInfo.getPvName());
						} else { 
							pausedPVsForThisAppliance.remove(typeInfo.getPvName());
						}
					}
				}
			}
		}
	}
	
	@Subscribe public void publishEventIntoCluster(PubSubEvent pubSubEvent) {
		String src = myIdentity;
		if(pubSubEvent.isLocalOrigin()) {
			pubSubEvent.setSource(src);
			logger.debug(this.warFile + " - Publishing event from local event bus onto cluster " + pubSubEvent.generateEventDescription());
			pubSub.publish(pubSubEvent);
		} else {
			logger.debug(this.warFile + " - Skipping non local event from event bus onto cluster " + pubSubEvent.generateEventDescription());
		}
	}

	/**
	 * Get the PVs that belong to this appliance and start archiving them
	 * Needless to day, this gets done only in the engine.
	 */
	private void archivePVSonStartup() {
		configlogger.debug("Start archiving PVs from persistence.");
		int secondsToBuffer = PVTypeInfo.getSecondsToBuffer(this);
		// To prevent broadcast storms, we pause for pausePerGroup seconds for every pausePerGroup PVs
		int currentPVCount = 0;
		int pausePerGroupPVCount = 2000;
		int pausePerGroupPauseTimeInSeconds = 2;
		for(String pvName : this.getPVsForThisAppliance()) {
			try {
				PVTypeInfo typeInfo = typeInfos.get(pvName);
				if(typeInfo == null) {
					logger.error("On restart, cannot find typeinfo for pv " + pvName + ". Not archiving");
					continue;
				}

				if(typeInfo.isPaused()) { 
					logger.debug("Skipping archiving paused PV " + pvName + " on startup");
					continue;
				}
				
				ArchDBRTypes dbrType = typeInfo.getDBRType();
				float samplingPeriod = typeInfo.getSamplingPeriod();
				SamplingMethod samplingMethod = typeInfo.getSamplingMethod();
				StoragePlugin firstDest = StoragePluginURLParser.parseStoragePlugin(typeInfo.getDataStores()[0], this);

				Timestamp lastKnownTimestamp = typeInfo.determineLastKnownEventFromStores(this);
				if(logger.isDebugEnabled()) logger.debug("Last known timestamp from ETL stores is for pv " + pvName + " is "+ TimeUtils.convertToHumanReadableString(lastKnownTimestamp));

				if(!dbrType.isV3Type()) {
					ArchiveEngine_EPICSV4.archivePV(pvName, samplingPeriod, samplingMethod, secondsToBuffer, firstDest, this, dbrType);
				} else {
					ArchiveEngine.archivePV(pvName, samplingPeriod, samplingMethod, secondsToBuffer, firstDest, this, dbrType,lastKnownTimestamp, typeInfo.getControllingPV(), typeInfo.getArchiveFields(), typeInfo.getHostName()); 
				}
				currentPVCount++;
				if(currentPVCount % pausePerGroupPVCount == 0) {
					logger.debug("Sleeping for " + pausePerGroupPauseTimeInSeconds + " to prevent CA search storms");
					Thread.sleep(pausePerGroupPauseTimeInSeconds*1000);
				}
			} catch(Throwable t) {
				logger.error("Exception starting up archiving of PV " + pvName + ". Moving on to the next pv.", t);
			}
		}
		configlogger.debug("Started " + currentPVCount + " PVs from persistence.");
	}

	@Override
	public boolean isStartupComplete() {
		return startupState == STARTUP_SEQUENCE.STARTUP_COMPLETE;
	}
	

	@Override
	public Properties getInstallationProperties() {
		return archapplproperties;
	}

	@Override
	public Collection<ApplianceInfo> getAppliancesInCluster() {
		ArrayList<ApplianceInfo> sortedAppliances = new ArrayList<ApplianceInfo>();
		for(ApplianceInfo info : appliances.values()) {
			if(appliancesInCluster.contains(info.getIdentity())) {
				sortedAppliances.add(info);
			} else {
				logger.debug("Skipping appliance that is in the persistence but not in the cluster" + info.getIdentity());
			}
		}
		
		Collections.sort(sortedAppliances, new Comparator<ApplianceInfo>() {
			@Override
			public int compare(ApplianceInfo o1, ApplianceInfo o2) {
				return o1.getIdentity().compareTo(o2.getIdentity());
			}
		});
		return sortedAppliances;
	}

	@Override
	public ApplianceInfo getMyApplianceInfo() {
		return myApplianceInfo;
	}

	@Override
	public ApplianceInfo getAppliance(String identity) {
		return appliances.get(identity);
	}

	
	@Override
	public Collection<String> getAllPVs() {
		List<PVApplianceCombo> sortedCombos = getSortedPVApplianceCombo();
		ArrayList<String> allPVs = new ArrayList<String>();
		for(PVApplianceCombo combo : sortedCombos) {
			allPVs.add(combo.pvName);
		}
		return allPVs;
	}

	@Override
	public ApplianceInfo getApplianceForPV(String pvName) {
		ApplianceInfo applianceInfo = pv2appliancemapping.get(pvName);
		if(applianceInfo == null && this.persistanceLayer != null) { 
			try { 
				PVTypeInfo typeInfo = this.persistanceLayer.getTypeInfo(pvName);
				if(typeInfo != null) { 
					applianceInfo = this.getAppliance(typeInfo.getApplianceIdentity());
				}
			} catch(IOException ex) { 
				logger.error("Exception lookin up appliance for pv in persistence", ex);
			}
		}
		return applianceInfo;
	}
	
	@Override
	public Iterable<String> getPVsForAppliance(ApplianceInfo info) {
		String identity = info.getIdentity();
		List<PVApplianceCombo> sortedCombos = getSortedPVApplianceCombo();
		ArrayList<String> pvsForAppliance = new ArrayList<String>();
		for(PVApplianceCombo combo : sortedCombos) {
			if(combo.applianceIdentity.equals(identity)) {
				pvsForAppliance.add(combo.pvName);
			}
		}
		return pvsForAppliance;
	}
	
	@Override
	public Iterable<String> getPVsForThisAppliance() {
		if(pvsForThisAppliance != null) {
			logger.debug("Returning the locally cached copy of the pvs for this appliance");
			return pvsForThisAppliance;
		} else {
			logger.debug("Fetching the list of PVs for this appliance from the mgmt app");
			JSONArray pvs = GetUrlContent.getURLContentAsJSONArray(myApplianceInfo.getMgmtURL() + "/getPVsForThisAppliance");
			LinkedList<String> retval = new LinkedList<String>();
			for(Object pv : pvs) {
				retval.add((String) pv);
			}
			return retval;
		}
	}
	

	@Override
	public ApplianceAggregateInfo getAggregatedApplianceInfo(ApplianceInfo applianceInfo) throws IOException {
		if(applianceInfo.getIdentity().equals(myApplianceInfo.getIdentity()) && this.warFile == WAR_FILE.MGMT) {
			logger.debug("Returning local copy of appliance info for " + applianceInfo.getIdentity());
			return applianceAggregateInfo;
		} else {
			try {
				JSONObject aggregateInfo = GetUrlContent.getURLContentAsJSONObject(applianceInfo.getMgmtURL() + "/getAggregatedApplianceInfo", false);
				JSONDecoder<ApplianceAggregateInfo> jsonDecoder = JSONDecoder.getDecoder(ApplianceAggregateInfo.class);
				ApplianceAggregateInfo retval = new ApplianceAggregateInfo();
				jsonDecoder.decode(aggregateInfo, retval);
				return retval;
			} catch(Exception ex) {
				throw new IOException(ex);
			}
		}
	}


	@Override
	public void registerPVToAppliance(String pvName, ApplianceInfo applianceInfo) throws AlreadyRegisteredException {
		ApplianceInfo info = pv2appliancemapping.get(pvName);
		if(info != null) throw new AlreadyRegisteredException(info);
		pv2appliancemapping.put(pvName, applianceInfo);
	}
	
	
	@Override
	public PVTypeInfo getTypeInfoForPV(String pvName) {
		if(typeInfos.containsKey(pvName))  {
			logger.debug("Retrieving typeinfo from cache for pv " + pvName);
			return typeInfos.get(pvName);
		}
		
		return null;
	}
	
	
	@Override
	public void updateTypeInfoForPV(String pvName, PVTypeInfo typeInfo) {
		logger.debug("Updating typeinfo for " + pvName);
		if(!typeInfo.keyAlreadyGenerated()) { 
			// This call should also typically set the chunk key in the type info.
			this.pvName2KeyConverter.convertPVNameToKey(pvName);
		}
		
		typeInfos.put(pvName, typeInfo);
	}

	@Override
	public void removePVFromCluster(String pvName) {
		logger.info("Removing PV from cluster.." + pvName);
		pv2appliancemapping.remove(pvName);
		pvsForThisAppliance.remove(pvName);
		typeInfos.remove(pvName);
		pausedPVsForThisAppliance.remove(pvName);
	}

	private class PVApplianceCombo implements Comparable<PVApplianceCombo> {
		String applianceIdentity;
		String pvName;
		public PVApplianceCombo(String applianceIdentity, String pvName) {
			this.applianceIdentity = applianceIdentity;
			this.pvName = pvName;
		}
		@Override
		public int compareTo(PVApplianceCombo other) {
			if(this.applianceIdentity.equals(other.applianceIdentity)) {
				return this.pvName.compareTo(other.pvName);
			} else {
				return this.applianceIdentity.compareTo(other.applianceIdentity);
			}
		}
	}
	
	private List<PVApplianceCombo> getSortedPVApplianceCombo() {
		ArrayList<PVApplianceCombo> sortedCombos = new ArrayList<PVApplianceCombo>();
		for(Map.Entry<String, ApplianceInfo> entry : pv2appliancemapping.entrySet()) {
			sortedCombos.add(new PVApplianceCombo(entry.getValue().getIdentity(), entry.getKey()));
		}
		Collections.sort(sortedCombos);
		return sortedCombos;
	}
	
	
	@Override
	public void addToArchiveRequests(String pvName, UserSpecifiedSamplingParams userSpecifiedSamplingParams) {
		archivePVRequests.put(pvName, userSpecifiedSamplingParams);
		try { 
			persistanceLayer.putArchivePVRequest(pvName, userSpecifiedSamplingParams);
		} catch(IOException ex) {
			logger.error("Exception adding request to persistence", ex);
		}
	}

	@Override
	public void updateArchiveRequest(String pvName, UserSpecifiedSamplingParams userSpecifiedSamplingParams) { 
		try { 
			if(persistanceLayer.getArchivePVRequest(pvName) != null) { 
				archivePVRequests.put(pvName, userSpecifiedSamplingParams);
				persistanceLayer.putArchivePVRequest(pvName, userSpecifiedSamplingParams);
			} else { 
				logger.error("Do not have user specified params for pv " + pvName + " in this appliance. Not updating.");
			}
		} catch(IOException ex) {
			logger.error("Exception updating request in persistence", ex);
		}
	}

	

	@Override
	public Set<String> getArchiveRequestsCurrentlyInWorkflow() {
		return new HashSet<String>(archivePVRequests.keySet());
	}
	
	@Override
	public boolean doesPVHaveArchiveRequestInWorkflow(String pvname) {
		return archivePVRequests.containsKey(pvname);
	}
	
	@Override
	public UserSpecifiedSamplingParams getUserSpecifiedSamplingParams(String pvName) {
		return archivePVRequests.get(pvName);
	}

	@Override
	public void archiveRequestWorkflowCompleted(String pvName) {
		archivePVRequests.remove(pvName);
		try { 
			persistanceLayer.removeArchivePVRequest(pvName);
		} catch(IOException ex) {
			logger.error("Exception removing request from persistence", ex);
		}
	}

	
	
	@Override
	public void addAlias(String aliasName, String realName) {
		aliasNamesToRealNames.put(aliasName, realName);
		try { 
			persistanceLayer.putAliasNamesToRealName(aliasName, realName);
		} catch(IOException ex) {
			logger.error("Exception adding alias name to persistence " + aliasName, ex);
		}
	}
	
	
	@Override
	public void removeAlias(String aliasName, String realName) {
		aliasNamesToRealNames.remove(aliasName);
		try { 
			persistanceLayer.removeAliasName(aliasName, realName);
		} catch(IOException ex) {
			logger.error("Exception removing alias name from persistence " + aliasName, ex);
		}
	}

	

	@Override
	public String getRealNameForAlias(String aliasName) {
		return aliasNamesToRealNames.get(aliasName);
	}
	
	@Override
	public List<String> getAllAliases() {
		return new ArrayList<String>(aliasNamesToRealNames.keySet());
	}



	private final String[] extraFields = new String[] {"MDEL","ADEL", "SCAN","RTYP", "NAME"};
	@Override
	public String[] getExtraFields() {
		return extraFields;
	}
	
	
	@Override
	public Set<String> getRuntimeFields() {
		return runTimeFields;
	}


	
	@Override
	public PBThreeTierETLPVLookup getETLLookup() {
		return etlPVLookup;
	}
	
	
	@Override
	public RetrievalState getRetrievalRuntimeState() {
		return retrievalState;
	}

	
	@Override
	public boolean isShuttingDown() {
		return startupExecutor.isShutdown();
	}
	
	@Override
	public void addShutdownHook(Runnable runnable) {
		shutdownHooks.add(runnable);
		
	}
	
	private void runShutDownHooksAndCleanup() { 
		LinkedList<Runnable> shutDnHooks = new LinkedList<Runnable>(this.shutdownHooks);
		Collections.reverse(shutDnHooks);
		for(Runnable shutdownHook : shutDnHooks) {
			try {
				shutdownHook.run();
			} catch(Throwable t) {
				logger.warn("Exception shutting down service using shutdown hook " + shutdownHook.toString(), t);
			}
		}
	}

	@Override
	public void shutdownNow() {
		if(this.warFile == WAR_FILE.MGMT) {
			this.startupExecutor.scheduleAtFixedRate(new Runnable() {
				
				@Override
				public void run() {
					// Make sure that the other components have shutdown before shutting the mgmt webapp
					configlogger.info("Checking to see if the other webapps have completed their shutdown");
					DefaultConfigService.this.runShutDownHooksAndCleanup();
				}
			}, 15, 60, TimeUnit.SECONDS);
		} else { 
			this.runShutDownHooksAndCleanup();
		}
	}

	

	
	
	@Override
	public Map<String, String> getChannelArchiverDataServers() {
		return channelArchiverDataServers;
	}

	@Override
	public void addChannelArchiverDataServer(String serverURL, String archivesCSV) throws IOException {
		String[] archives = archivesCSV.split(",");
		boolean loadCAPVs = false;
		if(!this.getChannelArchiverDataServers().containsKey(serverURL)) { 
			this.getChannelArchiverDataServers().put(serverURL, archivesCSV);
			loadCAPVs = true;
		} else { 
			logger.info(serverURL + " already exists in the map. So, skipping loading PVs from the external server.");
		}
		
		
		// We always add to persistence; whether this is from the UI or from the other appliances in the cluster.
		if(this.persistanceLayer != null) { 
			persistanceLayer.putExternalDataServer(serverURL, archivesCSV);
		}
		
		try {
			// We load PVs from the external server only if this is the first server starting up...
			if(loadCAPVs) { 
				for(int i = 0; i < archives.length; i++) {
					String archive = archives[i];
					loadChannelArchiverPVs(serverURL, archive);
				}
			}
		} catch(Exception ex) {
			logger.error("Exception adding Channel Archiver archives " + serverURL + " - " + archivesCSV, ex);
			throw new IOException(ex);
		}
	}

	/**
	 * Given a Channel Archiver data server URL and an archive; this adds the PVs in the Channel Archiver so that they can be proxied.
	 * @param serverURL
	 * @param archive
	 * @throws IOException
	 * @throws SAXException
	 */
	private void loadChannelArchiverPVs(String serverURL, String archive) throws IOException, SAXException {
		ChannelArchiverDataServerInfo serverInfo = new ChannelArchiverDataServerInfo(serverURL, archive);
		NamesHandler handler = new NamesHandler();
		logger.debug("Getting list of PV's from Channel Archiver Server at " + serverURL + " using index " + archive);
		XMLRPCClient.archiverNames(serverURL, archive, handler);
		HashMap<String, List<ChannelArchiverDataServerPVInfo>> tempPVNames = new HashMap<String, List<ChannelArchiverDataServerPVInfo>>();
		long totalPVsProxied = 0;
		for(NamesHandler.ChannelDescription pvChannelDesc : handler.getChannels()) {
			String pvName = PVNames.normalizePVName(pvChannelDesc.getName());
			if(this.pv2ChannelArchiverDataServer.containsKey(pvName)) { 
				List<ChannelArchiverDataServerPVInfo> alreadyExistingServers = this.pv2ChannelArchiverDataServer.get(pvName);
				logger.debug("Adding new server to already existing ChannelArchiver server for " + pvName);
				addExternalCAServerToExistingList(alreadyExistingServers, serverInfo, pvChannelDesc);
				tempPVNames.put(pvName, alreadyExistingServers);
			} else if(tempPVNames.containsKey(pvName)) { 
				List<ChannelArchiverDataServerPVInfo> alreadyExistingServers = tempPVNames.get(pvName);
				logger.debug("Adding new server to already existing ChannelArchiver server (in tempspace) for " + pvName);
				addExternalCAServerToExistingList(alreadyExistingServers, serverInfo, pvChannelDesc);
				tempPVNames.put(pvName, alreadyExistingServers);
			} else { 
				List<ChannelArchiverDataServerPVInfo> caServersForPV = new ArrayList<ChannelArchiverDataServerPVInfo>();
				caServersForPV.add(new ChannelArchiverDataServerPVInfo(serverInfo, pvChannelDesc.getStartSec(), pvChannelDesc.getEndSec()));
				tempPVNames.put(pvName, caServersForPV);
			}
			
			
			if(tempPVNames.size() > 1000) { 
				this.pv2ChannelArchiverDataServer.putAll(tempPVNames);
				totalPVsProxied += tempPVNames.size();
				tempPVNames.clear();
			}
		}
		if(!tempPVNames.isEmpty()) { 
			this.pv2ChannelArchiverDataServer.putAll(tempPVNames);
			totalPVsProxied += tempPVNames.size();
			tempPVNames.clear();
		}
		if(logger.isDebugEnabled()) logger.debug("Proxied a total of " + totalPVsProxied + " from server " + serverURL + " using archive " + archive);
		
	}
	
	private static void addExternalCAServerToExistingList(List<ChannelArchiverDataServerPVInfo> alreadyExistingServers, ChannelArchiverDataServerInfo serverInfo, NamesHandler.ChannelDescription pvChannelDesc) {
		List<ChannelArchiverDataServerPVInfo> copyOfAlreadyExistingServers = new LinkedList<ChannelArchiverDataServerPVInfo>();
		for(ChannelArchiverDataServerPVInfo alreadyExistingServer : alreadyExistingServers) { 
			if(alreadyExistingServer.getServerInfo().equals(serverInfo)) { 
				logger.debug("Removing a channel archiver server that already exists " + alreadyExistingServer.toString());
			} else { 
				copyOfAlreadyExistingServers.add(alreadyExistingServer);
			}
		}
		
		int beforeCount = alreadyExistingServers.size();
		alreadyExistingServers.clear();
		alreadyExistingServers.addAll(copyOfAlreadyExistingServers);
		
		// Readd the CA server - this should take into account any updated start times, end times and so on.
		alreadyExistingServers.add(new ChannelArchiverDataServerPVInfo(serverInfo, pvChannelDesc.getStartSec(), pvChannelDesc.getEndSec()));
		
		int afterCount = alreadyExistingServers.size();
		logger.debug("We had " + beforeCount + " and now we have " + afterCount + " when adding external ChannelArchiver server");

		// Sort the servers by ascending time stamps before adding it back.
		ChannelArchiverDataServerPVInfo.sortServersBasedOnStartAndEndSecs(alreadyExistingServers);
	}
	
	
	@Override
	public List<ChannelArchiverDataServerPVInfo> getChannelArchiverDataServers(String pvName) {
		String normalizedPVName = PVNames.normalizePVName(pvName);
		logger.debug("Looking for CA sever for pv " + normalizedPVName);
		return pv2ChannelArchiverDataServer.get(normalizedPVName);
	}

	@Override
	public PolicyConfig computePolicyForPV(String pvName, MetaInfo metaInfo, UserSpecifiedSamplingParams userSpecParams) throws IOException {
		try(InputStream is = this.getPolicyText()) {
			logger.debug("Computing policy for pvName");
			HashMap<String, Object> pvInfo = new HashMap<String, Object>();
			pvInfo.put("dbrtype", metaInfo.getArchDBRTypes().toString());
			pvInfo.put("elementCount", metaInfo.getCount());
			pvInfo.put("eventRate", metaInfo.getEventRate());
			pvInfo.put("storageRate", metaInfo.getStorageRate());
			pvInfo.put("aliasName", metaInfo.getAliasName());
			if(userSpecParams != null && userSpecParams.getPolicyName() != null) {
				logger.debug("Passing user override of policy " + userSpecParams.getPolicyName() + " as the dict entry policyName");
				pvInfo.put("policyName", userSpecParams.getPolicyName());
			}

			
			HashMap<String,String> otherMetaInfo = metaInfo.getOtherMetaInfo();
			for(String otherMetaInfoKey : this.getExtraFields()) {
				if(otherMetaInfo.containsKey(otherMetaInfoKey)) pvInfo.put(otherMetaInfoKey,otherMetaInfo.get(otherMetaInfoKey));
			}

			if(logger.isDebugEnabled()) {
				StringBuilder buf = new StringBuilder();
				buf.append("Before computing policy for");
				buf.append(pvName);
				buf.append(" pvInfo is \n");
				for(String key : pvInfo.keySet()) {
					buf.append(key);
					buf.append("=");
					buf.append(pvInfo.get(key));
					buf.append("\n");
				}
				logger.debug(buf.toString());
			}
			
			
			PolicyConfig policyConfig = ExecutePolicy.computePolicyForPV(is, pvName, pvInfo);
			return policyConfig;
		}
	}
	
	
	
	@Override
	public HashMap<String, String> getPoliciesInInstallation() throws IOException {
		try(InputStream is = this.getPolicyText()) {
			return ExecutePolicy.getPolicyList(is);
		}
	}
	
	
	@Override
	public List<String> getFieldsArchivedAsPartOfStream() throws IOException {
		try(InputStream is = this.getPolicyText()) {
			return ExecutePolicy.getFieldsArchivedAsPartOfStream(is);
		}
	}

	@Override
	public TypeSystem getArchiverTypeSystem() {
		return new PBTypeSystem();
	}


	private boolean finishedLoggingPolicyLocation = false;
	@Override
	public InputStream getPolicyText() throws IOException {
		String policiesPyFile = System.getProperty(ARCHAPPL_POLICIES);
		if(policiesPyFile == null) {
			policiesPyFile = System.getenv(ARCHAPPL_POLICIES);
			if(policiesPyFile != null) { 
				if(!finishedLoggingPolicyLocation) { configlogger.info("Obtained policies location from environment " + policiesPyFile); finishedLoggingPolicyLocation = true; } 
				return new FileInputStream(new File(policiesPyFile));
			} else { 
				logger.info("Looking for /WEB-INF/classes/policies.py in classpath");
				if(servletContext != null) {
					if(!finishedLoggingPolicyLocation) { configlogger.info("Using policies file /WEB-INF/classes/policies.py found in classpath"); finishedLoggingPolicyLocation = true; } 
					return servletContext.getResourceAsStream("/WEB-INF/classes/policies.py");
				} else {
					throw new IOException("Cannot determine location of policies file as both servlet context and webInfClassesFolder are null");
				}
			}
		} else { 
			if(!finishedLoggingPolicyLocation) { configlogger.info("Obtained policies location from system property " + policiesPyFile); finishedLoggingPolicyLocation = true; } 
			return new FileInputStream(new File(policiesPyFile));
		}
	}
	
	

	@Override
	public EngineContext getEngineContext() {
		return engineContext;
	}

	@Override
	public MgmtRuntimeState getMgmtRuntimeState() {
		return mgmtRuntime;
	}

	@Override
	public WAR_FILE getWarFile() {
		return warFile;
	}
		
	/**
	 * Return a string representation of the member.
	 * @param member
	 * @return
	 */
	private String getMemberKey(Member member) {
		// We use deprecated versions of the methods as the non-deprecated versions do not work as of 2.0x?
		return member.getSocketAddress().toString();
	}

	@Override
	public EventBus getEventBus() {
		return eventBus;
	}

	@Override
	public PVNameToKeyMapping getPVNameToKeyConverter() {
		return pvName2KeyConverter;
	}
	
	/**
	 * Load typeInfos into the cluster hashmaps from the persistence layer on startup.
	 * To avoid overwhelming the cluster, we batch the loads 
	 */
	private void loadTypeInfosFromPersistence() {
		try { 
			configlogger.info("Loading PVTypeInfo from persistence");
			List<String> upgradedPVs = new LinkedList<String>();
			List<String> pvNamesFromPersistence = persistanceLayer.getTypeInfoKeys();
			HashMap<String, PVTypeInfo> newTypeInfos = new HashMap<String, PVTypeInfo>();
			HashMap<String, ApplianceInfo> newPVMappings = new HashMap<String, ApplianceInfo>();
			int objectCount = 0;
			int batch = 0;
			int clusterPVCount = 0;
			for(String pvNameFromPersistence : pvNamesFromPersistence) {
				PVTypeInfo typeInfo = persistanceLayer.getTypeInfo(pvNameFromPersistence);
				if(typeInfo.getApplianceIdentity().equals(myIdentity)) {
					// Here's where we put schema update logic
					upgradeTypeInfo(typeInfo, upgradedPVs);
					
					newTypeInfos.put(typeInfo.getPvName(), typeInfo);
					newPVMappings.put(typeInfo.getPvName(), appliances.get(typeInfo.getApplianceIdentity()));
					pvsForThisAppliance.add(typeInfo.getPvName());
					if(typeInfo.isPaused()) { 
						pausedPVsForThisAppliance.add(typeInfo.getPvName());
					}
					objectCount++;
				}
				// Add in batch sizes of 1000 or so...
				if(objectCount > 1000) {
					this.typeInfos.putAll(newTypeInfos);
					this.pv2appliancemapping.putAll(newPVMappings);
					for(String pvName : newTypeInfos.keySet()) {
						applianceAggregateInfo.addInfoForPV(pvName, newTypeInfos.get(pvName), this);
					}
					clusterPVCount += newTypeInfos.size();
					newTypeInfos = new HashMap<String, PVTypeInfo>();
					newPVMappings = new HashMap<String, ApplianceInfo>();
					objectCount = 0;
					logger.debug("Adding next batch of PVs " + batch++);
				}
			}

			if(newTypeInfos.size() > 0) {
				logger.debug("Adding final batch of PVs from persistence");
				this.typeInfos.putAll(newTypeInfos);
				this.pv2appliancemapping.putAll(newPVMappings);
				for(String pvName : newTypeInfos.keySet()) {
					applianceAggregateInfo.addInfoForPV(pvName, newTypeInfos.get(pvName), this);
				}
				clusterPVCount += newTypeInfos.size();
			}

			configlogger.info("Done loading " + + clusterPVCount + " PVs from persistence into cluster");
			
			for(String upgradedPVName : upgradedPVs) { 
				logger.debug("PV " + upgradedPVName + "'s schema was upgraded");
				persistanceLayer.putTypeInfo(upgradedPVName, getTypeInfoForPV(upgradedPVName));
				logger.debug("Done persisting upgraded PV's " + upgradedPVName + "'s typeInfo");				
			}
		} catch(Exception ex) {
			configlogger.error("Exception loading PVs from persistence", ex);
		}
	}

	/**
	 * The occasional upgrade to PVTypeInfo schema is handed here.
	 * @param typeInfo - Typeinfo to be upgraded
	 * @param upgradedPVs - Add the pvName here if we actually did an upgrade to the typeInfo.
	 */
	private void upgradeTypeInfo(PVTypeInfo typeInfo, List<String> upgradedPVs) {
		// We added the chunkKey to typeInfo to permanently remember the key mapping to accomodate slowly changing key mappings. 
		// This could be a possibility after talking to SPEAR folks.
		if(!typeInfo.keyAlreadyGenerated()) { 
			typeInfo.setChunkKey(this.pvName2KeyConverter.convertPVNameToKey(typeInfo.getPvName()));
			upgradedPVs.add(typeInfo.getPvName());
		}
	}
	
	/**
	 * Load alias mappings from persistence on startup in batches
	 */
	private void loadAliasesFromPersistence() {
		try { 
			configlogger.info("Loading aliases from persistence");
			List<String> pvNamesFromPersistence = persistanceLayer.getAliasNamesToRealNamesKeys();
			HashMap<String, String> newAliases = new HashMap<String, String>();
			int objectCount = 0;
			int batch = 0;
			int clusterPVCount = 0;
			for(String pvNameFromPersistence : pvNamesFromPersistence) {
				String realName = persistanceLayer.getAliasNamesToRealName(pvNameFromPersistence);
				if(this.pvsForThisAppliance.contains(realName)) {
					newAliases.put(pvNameFromPersistence, realName);
				}
				// Add in batch sizes of 1000 or so...
				if(objectCount > 1000) {
					this.aliasNamesToRealNames.putAll(newAliases);
					clusterPVCount += newAliases.size();
					newAliases = new HashMap<String, String>();
					objectCount = 0;
					logger.debug("Adding next batch of aliases " + batch++);
				}
			}

			if(newAliases.size() > 0) {
				logger.debug("Adding final batch of aliases from persistence");
				this.aliasNamesToRealNames.putAll(newAliases);
				clusterPVCount += newAliases.size();
			}

			configlogger.info("Done loading " + clusterPVCount + " aliases from persistence into cluster ");
		} catch(Exception ex) {
			configlogger.error("Exception loading aliases from persistence", ex);
		}
	}
	
	
	/**
	 * Load any pending archive requests that have not been fulfilled yet on startup
	 * Also, start their workflows..
	 * @throws ConfigException
	 */
	private void loadArchiveRequestsFromPersistence() throws ConfigException {
		try { 
			configlogger.info("Loading archive requests from persistence");
			List<String> pvNamesFromPersistence = persistanceLayer.getArchivePVRequestsKeys();
			HashMap<String, UserSpecifiedSamplingParams> newArchiveRequests = new HashMap<String, UserSpecifiedSamplingParams>();
			int objectCount = 0;
			int batch = 0;
			int clusterPVCount = 0;
			for(String pvNameFromPersistence : pvNamesFromPersistence) {
				UserSpecifiedSamplingParams userSpecifiedParams = persistanceLayer.getArchivePVRequest(pvNameFromPersistence);
				// We should not need to add an appliance check here.. However, if after production deployment, we determine we need to do so; this is the right place.
				newArchiveRequests.put(pvNameFromPersistence, userSpecifiedParams);
				// Add in batch sizes of 1000 or so...
				if(objectCount > 1000) {
					this.archivePVRequests.putAll(newArchiveRequests);
					clusterPVCount += newArchiveRequests.size();
					newArchiveRequests = new HashMap<String, UserSpecifiedSamplingParams>();
					objectCount = 0;
					logger.debug("Adding next batch of archive pv requests " + batch++);
				}
			}

			if(newArchiveRequests.size() > 0) {
				logger.debug("Adding final batch of archive pv requests from persistence");
				this.archivePVRequests.putAll(newArchiveRequests);
				clusterPVCount += newArchiveRequests.size();
			}

			configlogger.info("Done loading " + clusterPVCount + " archive pv requests from persistence into cluster ");
		} catch(Exception ex) {
			configlogger.error("Exception loading archive pv requests from persistence", ex);
		}
	}


	/**
	 * Initialize the persistenceLayer using environment/system property.
	 * By default, initialize the MySQLPersistence
	 * @throws ConfigException
	 */
	private void initializePersistenceLayer() throws ConfigException {
		String persistenceFromEnv = System.getenv(ARCHAPPL_PERSISTENCE_LAYER);
		if(persistenceFromEnv == null || persistenceFromEnv.equals("")) {
			persistenceFromEnv = System.getProperty(ARCHAPPL_PERSISTENCE_LAYER);
		}
		if(persistenceFromEnv == null || persistenceFromEnv.equals("")) {
			logger.info("Using MYSQL for persistence; we expect to find a JNDI connection pool called jdbc/archappl");
			persistanceLayer = new MySQLPersistence();
		} else {
			try { 
				logger.info("Using persistence provided by class " + persistenceFromEnv);
				persistanceLayer = (ConfigPersistence) getClass().getClassLoader().loadClass(persistenceFromEnv).newInstance();
			} catch(Exception ex) {
				throw new ConfigException("Exception initializing persistence layer using " + persistenceFromEnv, ex);
			}
		}
	}

	public ProcessMetrics getProcessMetrics() {
		return processMetrics;
	}
	
	
	@Override
	public String getWebInfFolder() {
		return servletContext.getRealPath("WEB-INF/");
	}
	
	
	private void loadExternalServersFromPersistence() throws ConfigException {
		try { 
			configlogger.info("Loading external servers from persistence");
			List<String> externalServerKeys = persistanceLayer.getExternalDataServersKeys();
			for(String serverUrl : externalServerKeys) {
				String archivesCSV = persistanceLayer.getExternalDataServer(serverUrl);
				if(this.getChannelArchiverDataServers().containsKey(serverUrl)) { 
					configlogger.info("Skipping adding " + serverUrl + " on this appliance as another appliance has already added it");
				} else { 
					this.getChannelArchiverDataServers().put(serverUrl, archivesCSV);
					String[] archives = archivesCSV.split(",");

					try {
						for(int i = 0; i < archives.length; i++) {
							String archive = archives[i];
							loadChannelArchiverPVs(serverUrl, archive);
						}
					} catch(Exception ex) {
						logger.error("Exception adding Channel Archiver archives " + serverUrl + " - " + archivesCSV, ex);
						throw new IOException(ex);
					}
				}
			}
			configlogger.info("Done loading external servers from persistence ");
		} catch(Exception ex) {
			configlogger.error("Exception loading external servers from persistence", ex);
		}
	}
	
	
	private void registerForNewExternalServers(IMap<Object, Object> dataServerMap) throws ConfigException {
		dataServerMap.addEntryListener(new EntryListener<Object, Object>() {
			
			@Override
			public void entryUpdated(EntryEvent<Object, Object> arg0) {
			}
			
			@Override
			public void entryRemoved(EntryEvent<Object, Object> arg0) {
			}
			
			@Override
			public void entryEvicted(EntryEvent<Object, Object> arg0) {
			}
			
			@Override
			public void entryAdded(EntryEvent<Object, Object> arg0) {
				String url = (String) arg0.getKey();
				String archivesCSV = (String) arg0.getValue();
				try { 
					addChannelArchiverDataServer(url, archivesCSV);
				} catch(Exception ex) { 
					logger.error("Exception syncing external data server " + url + archivesCSV, ex);
				}
			}

			@Override
			public void mapCleared(MapEvent arg0) {
				// TODO Auto-generated method stub
				
			}

			@Override
			public void mapEvicted(MapEvent arg0) {
				// TODO Auto-generated method stub
				
			}
		}, true);
	}

	@Override
	public Set<String> getPausedPVsInThisAppliance() {
		if(pausedPVsForThisAppliance != null) { 
			logger.debug("Returning the locally cached copy of the paused pvs for this appliance");
			return pausedPVsForThisAppliance;
		} else {
			logger.debug("Fetching the list of paused PVs for this appliance from the mgmt app");
			JSONArray pvs = GetUrlContent.getURLContentAsJSONArray(myApplianceInfo.getMgmtURL() + "/getPausedPVsForThisAppliance");
			HashSet<String> retval = new HashSet<String>();
			for(Object pv : pvs) {
				retval.add((String) pv);
			}
			return retval;
		}
	}

	@Override
	public void refreshPVDataFromChannelArchiverDataServers() {
		Map<String, String> existingCAServers = this.getChannelArchiverDataServers();
		for(String serverURL : existingCAServers.keySet()) { 
			String archivesCSV = existingCAServers.get(serverURL);
			String[] archives = archivesCSV.split(",");

			try {
				for(int i = 0; i < archives.length; i++) {
					String archive = archives[i];
					loadChannelArchiverPVs(serverURL, archive);
				}
			} catch(Throwable ex) {
				logger.error("Exception adding Channel Archiver archives " + serverURL + " - " + archivesCSV, ex);
			}
		}
	}
}