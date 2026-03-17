/* -*- tab-width: 4 -*-
 *
 * Electric(tm) VLSI Design System
 *
 * File: WaveformWorkflowTest.java
 * End-to-end test for the waveform data loading and signal access workflow
 *
 * Copyright (c) 2026, Static Free Software. All rights reserved.
 *
 * Electric(tm) is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * Electric(tm) is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.sun.electric.tool.simulation;

import java.io.File;
import java.net.URL;
import java.util.Collection;
import java.util.Iterator;

import org.junit.Assert;
import org.junit.Test;

import com.sun.electric.database.hierarchy.Cell;
import com.sun.electric.database.hierarchy.Library;
import com.sun.electric.tool.io.input.SimulationData;
import com.sun.electric.tool.util.test.AbstractJunitBaseClass;

/**
 * Task 11.6: End-to-end test for the waveform data workflow.
 * Tests signal loading from .raw files, signal collection navigation,
 * data sample access, and signal metadata queries.
 */
public class WaveformWorkflowTest extends AbstractJunitBaseClass
{
	private static final String NOT_LIB_FILE = "NOT.jelib";
	private static final String NOT_RAW_FILE = "NOT.raw";

	/**
	 * Test loading simulation data from an existing .raw file and
	 * verifying signal collections, signal names, and data samples.
	 */
	@Test
	public void testLoadAndNavigateSignals() throws Exception
	{
		// Check if we have a .raw file from prior simulation
		File rawFile = new File(NOT_RAW_FILE);
		if (!rawFile.exists())
		{
			// Run ngspice to produce the .raw file
			File spiFile = new File("NOT.spi");
			if (!spiFile.exists() || !new File("/usr/bin/ngspice").exists())
			{
				System.out.println("NOT.raw and NOT.spi/ngspice not found, skipping waveform test");
				return;
			}

			System.out.println("Running ngspice to produce NOT.raw...");
			ProcessBuilder pb = new ProcessBuilder("ngspice", "-b", "-r", "NOT.raw", "NOT.spi");
			pb.redirectErrorStream(true);
			Process proc = pb.start();
			proc.getInputStream().readAllBytes();
			proc.waitFor();

			rawFile = new File("NOT.raw");
			if (!rawFile.exists())
			{
				System.out.println("ngspice did not produce NOT.raw, skipping test");
				return;
			}
		}

		// Load NOT gate library (for cell reference)
		Library lib = loadLibrary("NOT", NOT_LIB_FILE, LoadLibraryType.fileSystem);
		Assert.assertNotNull("NOT library not loaded", lib);

		Cell schCell = lib.findNodeProto("NOT{sch}");
		Assert.assertNotNull("NOT{sch} cell not found", schCell);

		// Step 1: Load simulation data from .raw file
		System.out.println("=== Step 1: Load simulation data ===");
		URL rawURL = rawFile.toURI().toURL();
		Stimuli sd = SimulationData.processInput(schCell, rawURL, null);
		Assert.assertNotNull("Stimuli should be loaded from .raw file", sd);

		// Step 2: Navigate signal collections
		System.out.println("\n=== Step 2: Navigate signal collections ===");
		Iterator<SignalCollection> scIt = sd.getSignalCollections();
		Assert.assertTrue("Should have at least 1 signal collection", scIt.hasNext());

		SignalCollection transientSC = null;
		while (scIt.hasNext())
		{
			SignalCollection sc = scIt.next();
			System.out.println("  Collection: " + sc.getName());
			if (transientSC == null) transientSC = sc; // use first collection
		}
		Assert.assertNotNull("Should have a signal collection", transientSC);

		// Step 3: Access individual signals
		System.out.println("\n=== Step 3: Access signals ===");
		Collection<Signal<?>> signals = transientSC.getSignals();
		Assert.assertFalse("Signal collection should have signals", signals.isEmpty());

		for (Signal<?> sig : signals)
		{
			System.out.println("  Signal: " + sig.getFullName() +
				" (digital=" + sig.isDigital() + ", empty=" + sig.isEmpty() + ")");
		}

		// Step 4: Verify expected NOT gate signals are present
		System.out.println("\n=== Step 4: Verify NOT gate signals ===");
		boolean hasInput = false, hasOutput = false, hasVdd = false;
		for (Signal<?> sig : signals)
		{
			String name = sig.getFullName().toLowerCase();
			if (name.contains("in")) hasInput = true;
			if (name.contains("out")) hasOutput = true;
			if (name.contains("vdd")) hasVdd = true;
		}
		Assert.assertTrue("Should have input signal", hasInput);
		Assert.assertTrue("Should have output signal", hasOutput);

		// Step 5: Read signal data samples
		System.out.println("\n=== Step 5: Read signal data samples ===");
		for (Signal<?> sig : signals)
		{
			if (sig.isEmpty()) continue;

			Signal.View<?> view = sig.getExactView();
			int numEvents = view.getNumEvents();
			Assert.assertTrue("Signal " + sig.getFullName() + " should have data points",
				numEvents > 0);

			// Get first and last time points
			double firstTime = view.getTime(0);
			double lastTime = view.getTime(numEvents - 1);

			System.out.println("  " + sig.getFullName() + ": " + numEvents +
				" samples, time [" + firstTime + " .. " + lastTime + "]");

			// Verify time is monotonically non-decreasing
			for (int i = 1; i < Math.min(numEvents, 100); i++)
			{
				Assert.assertTrue("Time should be non-decreasing at index " + i,
					view.getTime(i) >= view.getTime(i - 1));
			}
		}

		// Step 6: Test signal metadata queries
		System.out.println("\n=== Step 6: Signal metadata ===");
		for (Signal<?> sig : signals)
		{
			if (sig.isEmpty()) continue;

			String fullName = sig.getFullName();
			Assert.assertNotNull("Signal should have a full name", fullName);
			Assert.assertFalse("Signal name should not be empty", fullName.isEmpty());

			double minTime = sig.getMinTime();
			double maxTime = sig.getMaxTime();
			Assert.assertTrue("Max time should be >= min time",
				maxTime >= minTime);
		}

		// Step 7: Test programmatic signal creation
		System.out.println("\n=== Step 7: Programmatic signal creation ===");
		Stimuli testStimuli = new Stimuli();
		SignalCollection testSC = Stimuli.newSignalCollection(testStimuli, "TEST");
		Assert.assertNotNull("Should be able to create a signal collection", testSC);

		double[] times = {0.0, 1e-9, 2e-9, 3e-9, 4e-9};
		double[] values = {0.0, 3.3, 3.3, 0.0, 0.0};
		Signal<?> testSig = ScalarSample.createSignal(testSC, testStimuli, "testsig", null, times, values);
		Assert.assertNotNull("Should create a test signal", testSig);
		Assert.assertFalse("Test signal should not be empty", testSig.isEmpty());

		Signal.View<?> testView = testSig.getExactView();
		Assert.assertEquals("Test signal should have 5 samples", 5, testView.getNumEvents());
		Assert.assertEquals("First sample time should be 0", 0.0, testView.getTime(0), 1e-15);

		// Verify findSignal works (Signal constructor adds with canonical name)
		Signal<?> found = testSC.findSignal("testsig");
		Assert.assertNotNull("Should find testsig by name", found);

		Assert.assertEquals("Found signal should be the same object", testSig, found);

		System.out.println("  Created test signal: " + testSig.getFullName() +
			" with " + testView.getNumEvents() + " samples");

		System.out.println("\n=== Waveform Workflow Complete ===");
	}
}
