package org.epics.archiverappliance.retrieval;

import java.io.IOException;
import java.net.URLEncoder;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentSkipListSet;

import javax.servlet.http.HttpServletRequest;

import org.apache.log4j.Logger;
import org.epics.archiverappliance.StoragePlugin;
import org.epics.archiverappliance.common.TimeUtils;
import org.epics.archiverappliance.config.ApplianceInfo;
import org.epics.archiverappliance.config.ChannelArchiverDataServerPVInfo;
import org.epics.archiverappliance.config.ConfigService;
import org.epics.archiverappliance.config.PVTypeInfo;
import org.epics.archiverappliance.config.StoragePluginURLParser;
import org.epics.archiverappliance.mgmt.policy.PolicyConfig.SamplingMethod;

public class RetrievalState {
	private static Logger logger = Logger.getLogger(RetrievalState.class.getName());
	private ConfigService configService;
	private int engineWriteThreadInSeconds = 60;
	
	public RetrievalState(ConfigService configService) {
		this.configService = configService;
		this.engineWriteThreadInSeconds = Integer.parseInt(configService.getInstallationProperties().getProperty("org.epics.archiverappliance.config.PVTypeInfo.secondsToBuffer", "60")); 
	}

	/**
	 * Get the data sources for a PV in the order of their lifetime id...
	 * @param pvName
	 * @return
	 * @throws IOException
	 */
	public List<DataSourceforPV> getDataSources(String pvName, PVTypeInfo typeInfo, Timestamp start, Timestamp end, HttpServletRequest req) throws IOException {
		if(typeInfo == null) {
			List<ChannelArchiverDataServerPVInfo> caServers = this.configService.getChannelArchiverDataServers(pvName);
			if(caServers != null) {
				ArrayList<DataSourceforPV> dataSourcesForPV =  new ArrayList<DataSourceforPV>();
				for(ChannelArchiverDataServerPVInfo caServer : caServers) { 
					int count = determineCount(req);
					String howStr = determineHowStr(req);
					int lifetimeid = 1;
					logger.debug("Adding Channel Archiver server for " + pvName + " " + caServer.toString());
					dataSourcesForPV.add(new DataSourceforPV(pvName, caServer.getServerInfo().getPlugin(count, howStr), lifetimeid++, null, null));
					return dataSourcesForPV;
				}
			}

			return null;
		}
		try {
			ConcurrentSkipListSet<DataSourceforPV> dataSourcesForPV =  new ConcurrentSkipListSet<DataSourceforPV>();

			// Add the engine.
			// We only add the engine if the end time justifies us going to the engine.
			// And also we skip if the typeinfo is a manufactured one based on the Sampling Method
			long currentEpochSeconds = TimeUtils.getCurrentEpochSeconds();
			long endEpochSeconds = TimeUtils.convertToEpochSeconds(end);
			if((endEpochSeconds >= currentEpochSeconds) || ((currentEpochSeconds - endEpochSeconds) <  2 * engineWriteThreadInSeconds)) {
				if(typeInfo.getSamplingMethod() == SamplingMethod.DONT_ARCHIVE) {
					logger.debug("Skipping going to the engine for something we are not sampling for pv " + pvName);
				} else { 
					ApplianceInfo applianceInfo = configService.getAppliance(typeInfo.getApplianceIdentity());
					String engineRawURL = URLEncoder.encode(applianceInfo.getEngineURL() + "/getData.raw", "UTF-8");
					StoragePlugin engineStoragePlugin = StoragePluginURLParser.parseStoragePlugin("pbraw://localhost?rawURL=" + engineRawURL + "&name=engine", configService);
					dataSourcesForPV.add(new DataSourceforPV(pvName, engineStoragePlugin, 0, null, null));
				}
			} else { 
				logger.debug("Skipping going to the engine for data " + TimeUtils.convertToISO8601String(currentEpochSeconds) + "/" + TimeUtils.convertToISO8601String(endEpochSeconds));
			}
			
			// Add the various storage plugins
			int lifetimeid = 1;
			for(String store : typeInfo.getDataStores()) {
				StoragePlugin storagePlugin = StoragePluginURLParser.parseStoragePlugin(store, configService);
				dataSourcesForPV.add(new DataSourceforPV(pvName, storagePlugin, lifetimeid++, null, null));
			}
			
			// Add any external servers if any only if the creation time for this type info is after the start time of the request.
			Timestamp creationTime = typeInfo.getCreationTime();
			if(creationTime == null || start.before(creationTime)) { 
				List<ChannelArchiverDataServerPVInfo> caServers = this.configService.getChannelArchiverDataServers(pvName);
				if(caServers != null) {
					for(ChannelArchiverDataServerPVInfo caServer : caServers) { 
						int count = determineCount(req);
						String howStr = determineHowStr(req);
						logger.debug("Adding Channel Archiver server for " + pvName + " " + caServer.toString() 
								+ " and asking for data from " + TimeUtils.convertToHumanReadableString(start)
								+ " and " + TimeUtils.convertToHumanReadableString(creationTime));
						dataSourcesForPV.add(new DataSourceforPV(pvName, caServer.getServerInfo().getPlugin(count, howStr), lifetimeid++, start, creationTime));
					}
				}
			} else { 
				logger.debug("Start time " 
						+ TimeUtils.convertToHumanReadableString(start) 
						+ " is on or after creation time stamp "
						+ TimeUtils.convertToHumanReadableString(creationTime) 
						+ ". Skipping adding any external data sources...");
			}

			return new ArrayList<DataSourceforPV>(dataSourcesForPV);
		} catch(Exception ex) {
			logger.error("Exception generating data sources for pv " + pvName, ex);
			return null;
		}
	}

	private String determineHowStr(HttpServletRequest req) {
		String howStr = "0";// By default, we ask for raw data...
		try {
			String caHowStr = req.getParameter("ca_how");
			if(caHowStr != null) {
				// We try to parse the how to make sure it is an int.
				Integer.parseInt(caHowStr);
			}
			
		} catch(Exception ex) {
			logger.warn("Exception parsing ca_how", ex);
		}
		return howStr;
	}

	private int determineCount(HttpServletRequest req) {
		String countStr = req.getParameter("ca_count");
		int count = Integer.MAX_VALUE;
		if(countStr != null) {
			count = Integer.parseInt(countStr);
		}
		return count;
	}
}