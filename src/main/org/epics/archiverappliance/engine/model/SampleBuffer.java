/*******************************************************************************
 * Copyright (c) 2010 Oak Ridge National Laboratory.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 ******************************************************************************/
package org.epics.archiverappliance.engine.model;

import org.apache.log4j.Logger;
import org.epics.archiverappliance.config.ArchDBRTypes;
import org.epics.archiverappliance.data.DBRTimeEvent;
import org.epics.archiverappliance.engine.membuf.ArrayListEventStream;
import org.epics.archiverappliance.engine.pv.PVMetrics;
import org.epics.archiverappliance.retrieval.RemotableEventStreamDesc;

/**
 * Buffer for the samples of one channel.
 * <p>
 * Assumes that one thread adds samples, while a different thread removes them.
 * When the queue size is reached, older samples get dropped.
 * 
 * @author Kay Kasemir
 * @version Initial version:CSS
 * @version 4-Jun-2012, Luofeng Li:added codes to support for the new archiver
 */
public class SampleBuffer {
	/**
	 * Name of channel that writes to this buffer. (we keep only the name, not
	 * the full channel, to decouple stuff).
	 */
	final private String channel_name;
     /**
      * current ArrayListEventStream
      */
	private ArrayListEventStream currentSamples;
	/**
	 * previous ArrayListEventStream
	 */
	private ArrayListEventStream previousSamples;
	/** Statistics */
	final private BufferStats stats = new BufferStats();

	/**
	 * Number of overruns when new string of overruns started, or
	 * <code>null</code>
	 */
	private Integer start_of_overruns;
	/**
	 * year listener for this buffer.
	 */
	private YearListener yearListener;
	/**
	 * pVMetrics kept for this sample buffer
	 */
	private PVMetrics pVMetrics;

	/** Logger for overrun messages */
	final private static ThrottledLogger overrun_msg = new ThrottledLogger(
			LogLevel.warning, 60); //$NON-NLS-1$

	/**
	 * Is the buffer in an error state because of RDB write errors? Note that
	 * this is global for all buffers, not per instance!
	 */
	private static volatile boolean error = false;
    /**
     * the buffer size
     */
	final private int capacity;
	/**
	 * the arch dbr type of the pv who has this sample buffer
	 */
	final private ArchDBRTypes archdbrtype;
	private short year;
	private static Logger logger = Logger.getLogger(SampleBuffer.class.getName());

	/** Create sample buffer of given capacity */
	public SampleBuffer(final String channel_name, final int capacity,
			ArchDBRTypes archdbrtype, PVMetrics pVMetrics) {
		this.channel_name = channel_name;
		this.archdbrtype = archdbrtype;
		this.pVMetrics = pVMetrics;
		RemotableEventStreamDesc desc = new RemotableEventStreamDesc(
				archdbrtype, channel_name, (short) 0);

		currentSamples = new ArrayListEventStream(capacity, desc);
		this.capacity = capacity;
	}

	/** @return channel name of this buffer */
	public String getChannelName() {
		return channel_name;
	}
    /**
     * get current ArrayListEventStream
     * @return ArrayListEventStream
     */
	public ArrayListEventStream getCurrentSamples() {
		return currentSamples;
	}
   /**
    * get the combined ArrayListEventStream of the previous and the current
    * @return ArrayListEventStream
    */
	public ArrayListEventStream getCombinedSamples() {
		RemotableEventStreamDesc desc = new RemotableEventStreamDesc(
				archdbrtype, channel_name, (short) 0);
		ArrayListEventStream combinedSamples = new ArrayListEventStream(
				capacity * 3, desc);

		if (previousSamples != null) {

			for (int mm = 0; mm < previousSamples.size(); mm++) {
				DBRTimeEvent timeEvent = (DBRTimeEvent) previousSamples.get(mm);
				combinedSamples.add(timeEvent);
			}

		}

		if (currentSamples != null) {
			for (int mm2 = 0; mm2 < currentSamples.size(); mm2++) {
				DBRTimeEvent timeEvent22 = (DBRTimeEvent) currentSamples
						.get(mm2);
				combinedSamples.add(timeEvent22);
			}
		}
		return combinedSamples;
	}

	public void resetSamples() {
		RemotableEventStreamDesc desc = new RemotableEventStreamDesc(
				archdbrtype, channel_name, this.year);

		synchronized (this) {
			previousSamples = currentSamples;
			currentSamples = new ArrayListEventStream(capacity, desc);

		}
	}
/**
 * get the previous ArrayListEventStream
 * @return ArrayListEventStream
 */
	public ArrayListEventStream getPreviousSamples() {
		return previousSamples;
	}

	/** @return Queue capacity, i.e. maximum queue size. */
	public int getCapacity() {

		return capacity;

	}

	/** @return Current queue size, i.e. number of samples in the queue. */
	public int getQueueSize() {

		return currentSamples.size();

	}

	/** @return <code>true</code> if currently experiencing write errors */
	public static boolean isInErrorState() {
		return error;
	}

	/** Set the error state. */
	public static void setErrorState(final boolean error) {
		SampleBuffer.error = error;
	}

	/**
	 * Add a sample to the queue, maybe dropping older samples
	 * 
	 * @return - true if we need to increment the event count.
	 * @throws Exception
	 */
	@SuppressWarnings("nls")
	public boolean add(final DBRTimeEvent value)  {
		boolean retval = true;
		
		if(this.archdbrtype != value.getDBRType()) { 
			pVMetrics.incrementInvalidTypeLostEventCount();
			return false;
		}
		
		@SuppressWarnings("deprecation")
		short yearTemp = (short) (value.getEventTimeStamp().getYear() + 1900);
		// value.getEventTimeStamp().
		if (currentSamples.getYear() == 0) {
			currentSamples.setYear(yearTemp);
			this.year = yearTemp;
		} else if (yearTemp != this.year) {
			this.year = yearTemp;
			yearListener.yearChanged(this);
		}
		try {
			{  
				synchronized (this) {
			int remainSize = capacity - currentSamples.size();

			if (remainSize < 1) {
				retval = false;
				// the queue is full
				// Note start of overruns, then drop older sample
				
				currentSamples.remove(0);
				//}
				pVMetrics.addSampleBufferFullLostEventCount();
				// }
				if (start_of_overruns == null)
					start_of_overruns = Integer.valueOf(stats.getOverruns());
				stats.addOverrun();
			} else if (start_of_overruns != null) { // Ending a string of
													// overruns. Maybe log it.
				final int overruns = stats.getOverruns() - start_of_overruns;
				overrun_msg.log(channel_name + ": " + overruns + " overruns");
				start_of_overruns = null;
			}
			
			
				currentSamples.add(value);
			}
				return retval;
			}
		} catch (Exception e) {
			//throw e;
			 logger.error(
                     "Exception when add data into sample buffer of pv: "+channel_name,
                     e);
			 return false;
		}
	}


	/** Update stats with current values */
	public void updateStats() {
		stats.updateSizes(getQueueSize());
	}

	/** @return Buffer statistics. */
	public BufferStats getBufferStats() {
		return stats;
	}

	/** Reset statistics */
	public void reset() {

		stats.reset();
	}

	@SuppressWarnings("nls")
	@Override
	public String toString() {
		return String
				.format("Sample buffer '%s': %d samples, %d samples max, %.1f samples average, %d overruns",
						channel_name, getQueueSize(), stats.getMaxSize(),
						stats.getAverageSize(), stats.getOverruns());
	}
/**
 * add the year listener to this buffer
 * @param yearListener the interface of yearListener
 */
	public void addYearListener(YearListener yearListener) {
		this.yearListener = yearListener;
	}

}