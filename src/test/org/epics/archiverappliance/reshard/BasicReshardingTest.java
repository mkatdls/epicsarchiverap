package org.epics.archiverappliance.reshard;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.net.URLEncoder;
import java.sql.Timestamp;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.apache.log4j.Logger;
import org.epics.archiverappliance.Event;
import org.epics.archiverappliance.EventStream;
import org.epics.archiverappliance.SIOCSetup;
import org.epics.archiverappliance.StoragePlugin;
import org.epics.archiverappliance.TomcatSetup;
import org.epics.archiverappliance.common.BasicContext;
import org.epics.archiverappliance.common.TimeUtils;
import org.epics.archiverappliance.config.ArchDBRTypes;
import org.epics.archiverappliance.config.ConfigServiceForTests;
import org.epics.archiverappliance.config.PVTypeInfo;
import org.epics.archiverappliance.config.StoragePluginURLParser;
import org.epics.archiverappliance.data.ScalarValue;
import org.epics.archiverappliance.engine.membuf.ArrayListEventStream;
import org.epics.archiverappliance.retrieval.RemotableEventStreamDesc;
import org.epics.archiverappliance.retrieval.client.RawDataRetrievalAsEventStream;
import org.epics.archiverappliance.utils.simulation.SimulationEvent;
import org.epics.archiverappliance.utils.ui.GetUrlContent;
import org.epics.archiverappliance.utils.ui.JSONDecoder;
import org.epics.archiverappliance.utils.ui.JSONEncoder;
import org.json.simple.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.firefox.FirefoxDriver;

/**
 * Simple test to test resharding a PV from one appliance to another...
 * <ul>
 * <li>Bring up a cluster of two appliances.</li>
 * <li>Archive the PV and wait for it to connect etc.</li>
 * <li>Determine the appliance for the PV.</li>
 * <li>Generate data for a PV making sure we have more than one data source and more than one chunk.</li>
 * <li>Pause the PV.</li>
 * <li>Reshard to the other appliance. </li>
 * <li>Resume the PV.</li>
 * <li>Check for data loss and resumption of archiving etc,</li>
 * </ul>
 * 
 * This test will probably fail at the beginning of the year; we generate data into MTS and LTS and if there is an overlap we get an incorrect number of events.
 * 
 * @author mshankar
 *
 */
public class BasicReshardingTest {
	private static Logger logger = Logger.getLogger(BasicReshardingTest.class.getName());
	private String pvName = "UnitTestNoNamingConvention:sine";
	private ConfigServiceForTests configService;
	TomcatSetup tomcatSetup = new TomcatSetup();
	SIOCSetup siocSetup = new SIOCSetup();
	WebDriver driver;
	String folderSTS = ConfigServiceForTests.getDefaultShortTermFolder() + File.separator + "reshardSTS";
	String folderMTS = ConfigServiceForTests.getDefaultPBTestFolder() + File.separator + "reshardMTS";
	String folderLTS = ConfigServiceForTests.getDefaultPBTestFolder() + File.separator + "reshardLTS";

	@Before
	public void setUp() throws Exception {
		configService = new ConfigServiceForTests(new File("./bin"));

		System.getProperties().put("ARCHAPPL_SHORT_TERM_FOLDER", folderSTS);
		System.getProperties().put("ARCHAPPL_MEDIUM_TERM_FOLDER", folderMTS);
		System.getProperties().put("ARCHAPPL_LONG_TERM_FOLDER", folderLTS);
		
		FileUtils.deleteDirectory(new File(folderSTS));
		FileUtils.deleteDirectory(new File(folderMTS));
		FileUtils.deleteDirectory(new File(folderLTS));
		
		siocSetup.startSIOCWithDefaultDB();
		tomcatSetup.setUpClusterWithWebApps(this.getClass().getSimpleName(), 2);
		driver = new FirefoxDriver();
	}

	@After
	public void tearDown() throws Exception {
		driver.quit();
		tomcatSetup.tearDown();
		siocSetup.stopSIOC();

		FileUtils.deleteDirectory(new File(folderSTS));
		FileUtils.deleteDirectory(new File(folderMTS));
		FileUtils.deleteDirectory(new File(folderLTS));
	}
	
	
	@Test
	public void testReshardPV() throws Exception {
		// This section is straight from the ArchivePVTest
		// Let's archive the PV and wait for it to connect.
		driver.get("http://localhost:17665/mgmt/ui/index.html");
		WebElement pvstextarea = driver.findElement(By.id("archstatpVNames"));
		pvstextarea.sendKeys(pvName);
		WebElement archiveButton = driver.findElement(By.id("archstatArchive"));
		logger.debug("About to submit");
		archiveButton.click();
		// We have to wait for some time here as it does take a while for the workflow to complete.
		Thread.sleep(5*60*1000);
		WebElement checkStatusButton = driver.findElement(By.id("archstatCheckStatus"));
		checkStatusButton.click();
		Thread.sleep(2*1000);
		WebElement statusPVName = driver.findElement(By.cssSelector("#archstatsdiv_table tr:nth-child(1) td:nth-child(1)"));
		String pvNameObtainedFromTable = statusPVName.getText();
		assertTrue("PV Name is not " + pvName + "; instead we get " + pvNameObtainedFromTable, pvName.equals(pvNameObtainedFromTable));
		WebElement statusPVStatus = driver.findElement(By.cssSelector("#archstatsdiv_table tr:nth-child(1) td:nth-child(2)"));
		String pvArchiveStatusObtainedFromTable = statusPVStatus.getText();
		String expectedPVStatus = "Being archived";
		assertTrue("Expecting PV archive status to be " + expectedPVStatus + "; instead it is " + pvArchiveStatusObtainedFromTable, expectedPVStatus.equals(pvArchiveStatusObtainedFromTable));
		
		PVTypeInfo typeInfoBeforePausing = getPVTypeInfo();
		// We determine the appliance for the PV by getting it's typeInfo.
		String applianceIdentity = typeInfoBeforePausing.getApplianceIdentity();
		assertTrue("Cannot determine appliance identity for pv from typeinfo ", applianceIdentity != null);
		
		// We use the PV's PVTypeInfo creation date for moving data. This PVTypeInfo was just created. 
		// We need to fake this to an old value so that the data is moved correctly.
		// The LTS data spans 2 years, so we set a creation time of about 4 years ago.
		typeInfoBeforePausing.setCreationTime(TimeUtils.getStartOfYear(TimeUtils.getCurrentYear() - 4));
		String updatePVTypeInfoURL = "http://localhost:17665/mgmt/bpl/putPVTypeInfo?pv=" + URLEncoder.encode(pvName, "UTF-8");
		GetUrlContent.postObjectAndGetContentAsJSONObject(updatePVTypeInfoURL, JSONEncoder.getEncoder(PVTypeInfo.class).encode(typeInfoBeforePausing));
		
		// Generate some data into the MTS and LTS
		String[] dataStores = typeInfoBeforePausing.getDataStores();
		assertTrue("Data stores is null or empty for pv from typeinfo ", dataStores != null && dataStores.length > 1);
		for(String dataStore : dataStores) { 
			logger.info("Data store for pv " + dataStore);
			StoragePlugin plugin = StoragePluginURLParser.parseStoragePlugin(dataStore, configService);
			String name = plugin.getName();
			if(name.equals("MTS")) {
				// For the MTS we generate a couple of days worth of data
				Timestamp startOfMtsData = TimeUtils.minusDays(TimeUtils.now(), 3);
				long startOfMtsDataSecs = TimeUtils.convertToEpochSeconds(startOfMtsData);
				ArrayListEventStream strm = new ArrayListEventStream(0, new RemotableEventStreamDesc(ArchDBRTypes.DBR_SCALAR_DOUBLE, pvName, TimeUtils.convertToYearSecondTimestamp(startOfMtsDataSecs).getYear()));
				for(long offsetSecs = 0; offsetSecs < 2*24*60*60; offsetSecs += 60) { 
					strm.add(new SimulationEvent(TimeUtils.convertToYearSecondTimestamp(startOfMtsDataSecs + offsetSecs), ArchDBRTypes.DBR_SCALAR_DOUBLE, new ScalarValue<Double>((double)offsetSecs)));
				}
				try(BasicContext context = new BasicContext()) {
					plugin.appendData(context, pvName, strm);
				}
			} else if(name.equals("LTS")) {
				// For the LTS we generate a couple of years worth of data
				long startofLtsDataSecs = TimeUtils.getStartOfYearInSeconds(TimeUtils.getCurrentYear() - 2);
				ArrayListEventStream strm = new ArrayListEventStream(0, new RemotableEventStreamDesc(ArchDBRTypes.DBR_SCALAR_DOUBLE, pvName, TimeUtils.convertToYearSecondTimestamp(startofLtsDataSecs).getYear()));
				for(long offsetSecs = 0; offsetSecs < 2*365*24*60*60; offsetSecs += 24*60*60) { 
					strm.add(new SimulationEvent(TimeUtils.convertToYearSecondTimestamp(startofLtsDataSecs + offsetSecs), ArchDBRTypes.DBR_SCALAR_DOUBLE, new ScalarValue<Double>((double)offsetSecs)));
				}
				try(BasicContext context = new BasicContext()) {
					plugin.appendData(context, pvName, strm);
				}
			}
		}
		logger.info("Done generating data. Now making sure the setup is correct by fetching some data.");
		
		
		// Get the number of events before resharding...
		long eventCount = getNumberOfEvents();
		long expectedMinEventCount = 2*24*60 + 2*365;
		logger.info("Got " + eventCount + " events");
		assertTrue("Expecting at least " + expectedMinEventCount  + " got " + eventCount + " for ", eventCount >= expectedMinEventCount);

		String otherAppliance = "appliance1";
		if(applianceIdentity.equals(otherAppliance)) { 
			otherAppliance = "appliance0";
		}
		
		// Let's pause the PV.
		String pausePVURL = "http://localhost:17665/mgmt/bpl/pauseArchivingPV?pv=" + URLEncoder.encode(pvName, "UTF-8");
		JSONObject pauseStatus = GetUrlContent.getURLContentAsJSONObject(pausePVURL);
		assertTrue("Cannot pause PV", pauseStatus.containsKey("status") && pauseStatus.get("status").equals("ok"));
		Thread.sleep(5000);
		logger.info("Successfully paused the PV; other appliance is " + otherAppliance);
		
		driver.get("http://localhost:17665/mgmt/ui/pvdetails.html?pv=" + pvName);
		Thread.sleep(2*1000);
		WebElement reshardPVButton = driver.findElement(By.id("pvDetailsReshardPV"));
		logger.info("About to click on reshard button.");
		reshardPVButton.click();
		WebElement dialogOkButton = driver.findElement(By.id("pvReshardOk"));
		logger.info("About to click on reshard ok button");
		dialogOkButton.click();
		Thread.sleep(5*60*1000);
		WebElement pvDetailsTable = driver.findElement(By.id("pvDetailsTable"));
		List<WebElement> pvDetailsTableRows = pvDetailsTable.findElements(By.cssSelector("tbody tr"));
		for(WebElement pvDetailsTableRow : pvDetailsTableRows) {
			WebElement pvDetailsTableFirstCol = pvDetailsTableRow.findElement(By.cssSelector("td:nth-child(1)"));
			if(pvDetailsTableFirstCol.getText().contains("Instance archiving PV")) {
				WebElement pvDetailsTableSecondCol = pvDetailsTableRow.findElement(By.cssSelector("td:nth-child(2)"));
				String obtainedAppliance = pvDetailsTableSecondCol.getText();
				String expectedAppliance = otherAppliance;
				assertTrue("Expecting appliance to be " + expectedAppliance + "; instead it is " + obtainedAppliance, expectedAppliance.equals(obtainedAppliance));
				break;
			}
		}
		
		logger.info("Resharding UI is done.");


		PVTypeInfo typeInfoAfterResharding = getPVTypeInfo();
		String afterReshardingAppliance = typeInfoAfterResharding.getApplianceIdentity();
		assertTrue("Invalid appliance identity after resharding " + afterReshardingAppliance, afterReshardingAppliance != null && afterReshardingAppliance.equals(otherAppliance));
		
		// Let's resume the PV.
		String resumePVURL = "http://localhost:17665/mgmt/bpl/resumeArchivingPV?pv=" + URLEncoder.encode(pvName, "UTF-8");
		JSONObject resumeStatus = GetUrlContent.getURLContentAsJSONObject(resumePVURL);
		assertTrue("Cannot resume PV", resumeStatus.containsKey("status") && resumeStatus.get("status").equals("ok"));

		long postReshardEventCount = getNumberOfEvents();
		logger.info("After resharding, got " + postReshardEventCount + " events");
		assertTrue("Expecting at least " + expectedMinEventCount  + " got " + postReshardEventCount + " for ", postReshardEventCount >= expectedMinEventCount);		
	}
	
	private PVTypeInfo getPVTypeInfo() throws Exception { 
		String getPVTypeInfoURL = "http://localhost:17665/mgmt/bpl/getPVTypeInfo?pv=" + URLEncoder.encode(pvName, "UTF-8");
		JSONObject typeInfoJSON = GetUrlContent.getURLContentAsJSONObject(getPVTypeInfoURL);
		assertTrue("Cannot get typeinfo for pv using " + getPVTypeInfoURL, typeInfoJSON != null);
        PVTypeInfo unmarshalledTypeInfo = new PVTypeInfo();
        JSONDecoder<PVTypeInfo> typeInfoDecoder = JSONDecoder.getDecoder(PVTypeInfo.class);
        typeInfoDecoder.decode((JSONObject) typeInfoJSON, unmarshalledTypeInfo);
        return unmarshalledTypeInfo;
	}
	
	private long getNumberOfEvents() throws Exception { 
		Timestamp start = TimeUtils.convertFromEpochSeconds(TimeUtils.getStartOfYearInSeconds(TimeUtils.getCurrentYear() - 2), 0);
		Timestamp end = TimeUtils.now();
		RawDataRetrievalAsEventStream rawDataRetrieval = new RawDataRetrievalAsEventStream("http://localhost:" + ConfigServiceForTests.RETRIEVAL_TEST_PORT+ "/retrieval/data/getData.raw");
		Timestamp obtainedFirstSample = null;
		long eventCount = 0;
		try(EventStream stream = rawDataRetrieval.getDataForPVS(new String[] { pvName }, start, end, null)) {
			if(stream != null) {
				for(Event e : stream) {
					if(obtainedFirstSample == null) { 
						obtainedFirstSample = e.getEventTimeStamp();
					}
					logger.debug("Sample from " + TimeUtils.convertToHumanReadableString(e.getEventTimeStamp()));
					eventCount++;
				}
			} else { 
				fail("Stream is null when retrieving data.");
			}
		}
		return eventCount;
	}
}