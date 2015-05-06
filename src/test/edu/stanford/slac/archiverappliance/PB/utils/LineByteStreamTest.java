/*******************************************************************************
 * Copyright (c) 2011 The Board of Trustees of the Leland Stanford Junior University
 * as Operator of the SLAC National Accelerator Laboratory.
 * Copyright (c) 2011 Brookhaven National Laboratory.
 * EPICS archiver appliance is distributed subject to a Software License Agreement found
 * in file LICENSE that is included with this distribution.
 *******************************************************************************/
package edu.stanford.slac.archiverappliance.PB.utils;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.text.DecimalFormat;

import org.epics.archiverappliance.config.ConfigServiceForTests;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import edu.stanford.slac.archiverappliance.PB.search.EvenNumberSampleFileGenerator;

/**
 * Test the LineByteStream
 * @author mshankar
 *
 */
public class LineByteStreamTest {

	@Before
	public void setUp() throws Exception {
	}

	@After
	public void tearDown() throws Exception {
	}

	@Test
	public void testLineByteStream() throws Exception {
		String fileName = ConfigServiceForTests.getDefaultPBTestFolder() + "/" + "LineByteStream.txt";
		File f = new File(fileName);
		if(f.exists()) {
			f.delete();
		}
		
		EvenNumberSampleFileGenerator.generateSampleFile(fileName);
		
		try(LineByteStream lis = new LineByteStream(f.toPath())) { 
			byte[] line = lis.readLine();
			int expectedNumber = 0;
			try {
				while(line != null) {
					if(line.length > 0) {
						int gotNumber = Integer.parseInt(new String(line, "UTF-8"));
						if(gotNumber != expectedNumber) {
							fail("Expected " + expectedNumber + " got " + gotNumber);
						}
					}
					expectedNumber=expectedNumber+2;
					line = lis.readLine();
				}
			} catch(Exception t) {
				System.err.println("Exception around " + expectedNumber + t.getMessage());
				t.printStackTrace(System.err);
				throw t;
			}

			lis.seekToBeforeLastLine();
			line = lis.readLine();
			int lastNumber = Integer.parseInt(new String(line, "UTF-8"));
			assertTrue("Testing last line number Got " + lastNumber, lastNumber == EvenNumberSampleFileGenerator.MAXSAMPLEINT);

			seekandCheck(f, 0x0, "0");  // Positioned at start of file
			seekandCheck(f, 0x1, "2");  // Boundary conditions
			seekandCheck(f, 0x2, "4");  // Boundary conditions
			seekandCheck(f, 0x3, "4");  // Boundary conditions
			seekandCheck(f, 0x30, "36"); // Positioned at newline
			seekandCheck(f, 0x40, "48"); // Positioned on a character
			seekandCheck(f, 0x50, "58"); // Positioned midway into the word
			//		This is what the test file looks like.
			//		0259df90  39 39 38 30 0a 39 39 39  39 39 38 32 0a 39 39 39  |9980.9999982.999|
			//		0259dfa0  39 39 38 34 0a 39 39 39  39 39 38 36 0a 39 39 39  |9984.9999986.999|
			//		0259dfb0  39 39 38 38 0a 39 39 39  39 39 39 30 0a 39 39 39  |9988.9999990.999|
			//		0259dfc0  39 39 39 32 0a 39 39 39  39 39 39 34 0a 39 39 39  |9992.9999994.999|
			//		0259dfd0  39 39 39 36 0a 39 39 39  39 39 39 38 0a 31 30 30  |9996.9999998.100|
			//		0259dfe0  30 30 30 30 30 0a                                 |00000.|
			//		0259dfe6
			seekandCheck(f, 0x0259dfd7, Integer.toString(EvenNumberSampleFileGenerator.MAXSAMPLEINT)); // Positioned midway into the word towards the end
			seekandCheck(f, 0x0259dfe5, null); // This is positioned at the end at the new line; we should not get a number; ideally we should get null
			seekandCheck(f, 0x0259dfe6, null); // This is positioned at the end; we should not get a number; ideally we should get null
			seekandCheck(f, 0x0269dfd6, null); // This is positioned way past the end of the file

			f.delete();
		}
	}
	
	private static void seekandCheck(File f, long position, String expectedNumberStr) throws IOException {
		try(LineByteStream lis = new LineByteStream(f.toPath(), position)) { 
			lis.seekToFirstNewLine();
			byte[] lineread = lis.readLine();
			String gotNumberStr = null;
			if(lineread != null) {
				gotNumberStr = new String(lineread, "UTF-8");
			}
			if(gotNumberStr != null && expectedNumberStr != null) {
				if(!gotNumberStr.equals(expectedNumberStr)) {
					fail("Expected " + expectedNumberStr + " got " + gotNumberStr);
				} else {
					// Success; do nothing.
				}
			} else {
				if(gotNumberStr == null && expectedNumberStr == null) {
					// Success; do nothing.
				} else {
					fail("Expected " + expectedNumberStr + " got " + gotNumberStr);
				}
			}
		}
	}
	
	
	@Test
	public void testSmallFileSeekToLastLine() throws Exception {
		String smallFileName = ConfigServiceForTests.getDefaultPBTestFolder() + "/" + "SmallLineByteStream.txt";
		File f = new File(smallFileName);
		if(f.exists()) {
			f.delete();
		}
		PrintStream fos = new PrintStream(new BufferedOutputStream(new FileOutputStream(f, false)));
		for(int i = 0; i < 10; i=i+2) {
			fos.print(i + LineEscaper.NEWLINE_CHAR_STR);
		}
		fos.close();	

		try(LineByteStream lis = new LineByteStream(f.toPath())) { 
			lis.seekToBeforeLastLine();
			byte[] line = lis.readLine();
			int lastNumber = Integer.parseInt(new String(line, "UTF-8"));
			assertTrue("Testing last line number Got " + lastNumber, lastNumber == 8);
			f.delete();
		}
	}
	
	@Test
	public void testEndPosition() throws Exception {
		String fileName = ConfigServiceForTests.getDefaultPBTestFolder() + "/" + "EndPositionLineByteStream.txt";
		File f = new File(fileName);
		if(f.exists()) {
			f.delete();
		}
		PrintStream fos = new PrintStream(new BufferedOutputStream(new FileOutputStream(f, false)));
		DecimalFormat myFormatter = new DecimalFormat("000000");
		for(int i = 0; i <= 100000; i=i+1) {
			fos.print(myFormatter.format(i) + LineEscaper.NEWLINE_CHAR_STR);
		}
		fos.close();	

		testEndPositionForStartEnd(f, 10, 100);
		testEndPositionForStartEnd(f, 20, 40000);
		testEndPositionForStartEnd(f, 0, 40000);
		testEndPositionForStartEnd(f, 1, 40000);
		testEndPositionForStartEnd(f, 1, 99999);
		testEndPositionForStartEnd(f, 1, 100000);
		testEndPositionForStartEnd(f, 0, 100000);
		
		testEndPositionForArbitraryStartEnd(f, 0x000000d0, 0x00000190, 30, 57);
		testEndPositionForArbitraryStartEnd(f, 0x00002a10, 0x000031f0, 1539, 1826);
 		f.delete();
	}
	
	private void testEndPositionForStartEnd(File f, int start, int end) throws Exception {
		try(LineByteStream lis = new LineByteStream(f.toPath(), 7*start, 7*end)) { 
			// Each line has 7 digits so 7*start should start at start and 7*end should end at end
			byte[] line = lis.readLine();
			int expectedNum = start;
			while(line != null) {
				int gotNum = Integer.parseInt(new String(line));
				assertTrue("Expected " + expectedNum + " obtained " + gotNum, gotNum == expectedNum);
				assertTrue("Maximum expected " + end + " obtained " + gotNum, gotNum <= end);
				expectedNum++;
				line = lis.readLine();
			}
		}
	}
	
	private void testEndPositionForArbitraryStartEnd(File f, long startPosn, long endPosn, int expectedStart, int expectedEnd) throws Exception {
		try(LineByteStream lis = new LineByteStream(f.toPath(), startPosn, endPosn)) { 
			lis.seekToFirstNewLine();
			byte[] line = lis.readLine();
			int expectedNum = expectedStart;
			while(line != null) {
				int gotNum = Integer.parseInt(new String(line));
				assertTrue("Expected " + expectedNum + " obtained " + gotNum, gotNum == expectedNum);
				assertTrue("Maximum expected " + expectedEnd + " obtained " + gotNum, gotNum <= expectedEnd);
				expectedNum++;
				line = lis.readLine();
			}
			assertTrue("Expected until " + expectedEnd + " obtained until " + expectedNum, expectedNum > expectedEnd);
		}
	}
	
	
	@Test
	public void testEmptyLines() throws Exception {
		String fileName = ConfigServiceForTests.getDefaultPBTestFolder() + "/" + "Empty1LineByteStream.txt";
		File f = new File(fileName);
		if(f.exists()) {
			f.delete();
		}
		PrintStream fos = new PrintStream(new BufferedOutputStream(new FileOutputStream(f, false)));
		DecimalFormat myFormatter = new DecimalFormat("000000");
		for(int i = 0; i <= 100000; i=i+1) {
			fos.print(myFormatter.format(i) + LineEscaper.NEWLINE_CHAR_STR);
			for(int j = 0; j < Math.min(i, 100); j++) {
				fos.print(LineEscaper.NEWLINE_CHAR_STR);
			}
		}
		fos.close();

		try(LineByteStream lis = new LineByteStream(f.toPath())) { 
			byte[] line = lis.readLine();
			int expectedNum = 0;
			while(line != null) {
				if(line.length <= 0) {
					line = lis.readLine();
					continue;
				}
				int gotNum = Integer.parseInt(new String(line));
				assertTrue("Expected " + expectedNum + " obtained " + gotNum, gotNum == expectedNum);
				assertTrue("Maximum expected " + 100000 + " obtained " + gotNum, gotNum <=100000);
				expectedNum++;
				line = lis.readLine();
			}
			f.delete();
		}
	}
	
	/**
	 * Generates a file with lines of increasing length and makes sure we can read em all.
	 * @throws Exception
	 */
	@Test
	public void testLargeLines() throws Exception {
		String fileName = ConfigServiceForTests.getDefaultPBTestFolder() + "/" + "LargeLineByteStream.txt";
		File f = new File(fileName);
		if(f.exists()) {
			f.delete();
		}
		BufferedOutputStream fos = new BufferedOutputStream(new FileOutputStream(f, false));
		for(int linesize=1; linesize < LineByteStream.MAX_LINE_SIZE*3; linesize++) {
			byte[] line = new byte[linesize];
			for(int i = 0; i < linesize; i++) {
				line[i] = '0';
			}
			fos.write(line);
			fos.write(LineEscaper.NEWLINE_CHAR);
		}
		fos.close();

		try(LineByteStream lis = new LineByteStream(f.toPath())) { 
			int expectedLineLength = 1;
			byte[] line = lis.readLine();
			while(line != null) {
				assertTrue("Expected line length " + expectedLineLength + " obtained " + line.length, expectedLineLength == line.length);
				line = lis.readLine();
				expectedLineLength++;
			}

			assertTrue("Expected line length " + expectedLineLength + " should be greater than  " + LineByteStream.MAX_LINE_SIZE, expectedLineLength > LineByteStream.MAX_LINE_SIZE);

			f.delete();
		}
	}

	/**
	 * Generates the same file with lines of increasing length and makes sure we can read the last line correctly.
	 * @throws Exception
	 */
	@Test
	public void testLargeLinesSeekToLastLine() throws Exception {
		for(int linesize=3; linesize < LineByteStream.MAX_LINE_SIZE*3; linesize++) {
			String fileName = ConfigServiceForTests.getDefaultPBTestFolder() + "/" + "LargeLineByteStreamSeekToLast.txt";
			File f = new File(fileName);
			if(f.exists()) {
				f.delete();
			}
			try(BufferedOutputStream fos = new BufferedOutputStream(new FileOutputStream(f, false))) {
				// This is the new line that will anchor the seekToBeforeLastLine
				fos.write(LineEscaper.NEWLINE_CHAR);
				byte[] line = new byte[linesize];
				for(int i = 0; i < linesize; i++) {
					line[i] = '0';
				}
				fos.write(line);
				fos.write(LineEscaper.NEWLINE_CHAR);
			}

			try(LineByteStream lis = new LineByteStream(f.toPath())) {
				lis.seekToBeforeLastLine();
				byte[] line = lis.readLine();
				assertTrue("Expected line length " + linesize + " obtained " + line.length, linesize == line.length);
			} catch (IOException ex) {
				fail("Exception " + ex.getMessage() + " when testing testLargeLinesSeekToLastLine for linesize " + linesize);
			}

			f.delete();
		}
	}
	
	
	
	@Test
	public void testSeekToPreviousLine() throws Exception {
		String fileName = ConfigServiceForTests.getDefaultPBTestFolder() + "/" + "LineByteStream.txt";
		File f = new File(fileName);
		if(f.exists()) {
			f.delete();
		}
		
		EvenNumberSampleFileGenerator.generateSampleFile(fileName);
		
		try(LineByteStream lis = new LineByteStream(f.toPath())) { 
			lis.seekToBeforeLastLine();
			long posn = lis.getCurrentPosition();
			byte[] line = lis.readLine();
			int linecount = 0;
			int expectedNumber = EvenNumberSampleFileGenerator.MAXSAMPLEINT;
			try {
				while(line != null) {
					if(line.length > 0) {
						int gotNumber = Integer.parseInt(new String(line, "UTF-8"));
						if(gotNumber != expectedNumber) {
							fail("Expected " + expectedNumber + " got " + gotNumber + " in count " + linecount + " lastReadPointer " + lis.lastReadPointer + " currentReadPosition " + lis.currentReadPosition);
						}
					} else { 
						fail("We got an empty line for linecount " + linecount);
					}
					expectedNumber=expectedNumber-2;
					lis.seekToBeforePreviousLine(posn);
					posn = lis.getCurrentPosition();
					line = lis.readLine();
					linecount++;
				}
			} catch(Exception t) {
				System.err.println("Exception around " + expectedNumber + t.getMessage());
				t.printStackTrace(System.err);
				throw t;
			}
		}
	}		
}
