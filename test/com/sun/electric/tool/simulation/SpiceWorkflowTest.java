/* -*- tab-width: 4 -*-
 *
 * Electric(tm) VLSI Design System
 *
 * File: SpiceWorkflowTest.java
 * End-to-end test for the SPICE simulation workflow
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
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.Iterator;

import org.junit.Assert;
import org.junit.Test;

import com.sun.electric.database.hierarchy.Cell;
import com.sun.electric.database.hierarchy.Library;
import com.sun.electric.tool.io.input.SimulationData;
import com.sun.electric.tool.util.test.AbstractJunitBaseClass;

/**
 * Task 10.7: End-to-end test for the SPICE simulation workflow.
 * Tests: use existing netlist -> run ngspice -> read .raw results -> verify signals.
 */
public class SpiceWorkflowTest extends AbstractJunitBaseClass
{
	private static final String NOT_LIB_FILE = "NOT.jelib";
	private static final String NOT_SPI_FILE = "NOT.spi";

	/**
	 * Test the full SPICE simulation workflow on a NOT gate.
	 * Uses the existing NOT.spi netlist (which includes stimulus and .tran analysis),
	 * runs ngspice, reads back .raw results, and verifies signals are present.
	 */
	@Test
	public void testNotGateSimulation() throws Exception
	{
		// Check ngspice is available
		boolean ngspiceAvailable = new File("/usr/bin/ngspice").exists();
		if (!ngspiceAvailable)
		{
			System.out.println("ngspice not found, skipping simulation test");
			return;
		}

		// Load NOT gate schematic (for SimulationData.processInput cell reference)
		Library lib = loadLibrary("NOT", NOT_LIB_FILE, LoadLibraryType.fileSystem);
		Assert.assertNotNull("NOT library not loaded", lib);

		Cell schCell = lib.findNodeProto("NOT{sch}");
		Assert.assertNotNull("NOT{sch} cell not found", schCell);

		// Step 1: Verify SPICE netlist exists (written by Electric GUI previously)
		System.out.println("=== Step 1: Verify SPICE netlist ===");
		File spiFileObj = new File(NOT_SPI_FILE);
		Assert.assertTrue("NOT.spi should exist in project root", spiFileObj.exists());
		Assert.assertTrue("NOT.spi should not be empty", spiFileObj.length() > 0);
		System.out.println("  Netlist: " + spiFileObj.getAbsolutePath() + " (" + spiFileObj.length() + " bytes)");

		// Set up temp directory for simulation output
		File tempDir = new File(System.getProperty("java.io.tmpdir"), "electric_spice_test");
		tempDir.mkdirs();
		File tempSpi = new File(tempDir, "NOT.spi");
		File rawFile = new File(tempDir, "NOT.raw");

		try
		{
			// Copy SPICE netlist to temp dir
			Files.copy(spiFileObj.toPath(), tempSpi.toPath(), StandardCopyOption.REPLACE_EXISTING);

			// Step 2: Run ngspice
			System.out.println("\n=== Step 2: Run ngspice ===");
			ProcessBuilder pb = new ProcessBuilder(
				"ngspice", "-b", "-r", rawFile.getAbsolutePath(), tempSpi.getAbsolutePath());
			pb.directory(tempDir);
			pb.redirectErrorStream(true);
			Process proc = pb.start();

			byte[] output = proc.getInputStream().readAllBytes();
			int exitCode = proc.waitFor();
			String ngspiceOutput = new String(output);

			System.out.println("  ngspice exit code: " + exitCode);
			if (exitCode != 0)
			{
				// Print last 20 lines of output for diagnostics
				String[] lines = ngspiceOutput.split("\n");
				int start = Math.max(0, lines.length - 20);
				for (int i = start; i < lines.length; i++)
					System.out.println("  ngspice: " + lines[i]);
			}

			Assert.assertTrue("Raw output file should exist after simulation", rawFile.exists());
			Assert.assertTrue("Raw output file should not be empty", rawFile.length() > 0);
			System.out.println("  Raw file: " + rawFile.getAbsolutePath() + " (" + rawFile.length() + " bytes)");

			// Step 3: Read simulation results
			System.out.println("\n=== Step 3: Read simulation results ===");
			URL rawURL = rawFile.toURI().toURL();
			// Use the version that bypasses clientDatabase assertion
			Stimuli sd = SimulationData.processInput(schCell, rawURL, null);
			Assert.assertNotNull("Simulation data should be loaded", sd);

			// List all signal collections and signals
			Iterator<SignalCollection> scIt = sd.getSignalCollections();
			int collectionCount = 0;
			int totalSignals = 0;
			boolean hasTimeOrSweep = false;
			while (scIt.hasNext())
			{
				SignalCollection sc = scIt.next();
				collectionCount++;
				Collection<Signal<?>> signals = sc.getSignals();
				totalSignals += signals.size();
				System.out.println("  Collection: " + sc.getName() + " (" + signals.size() + " signals)");
				for (Signal<?> sig : signals)
				{
					String name = sig.getFullName();
					System.out.println("    Signal: " + name);
					if (name.toLowerCase().contains("time") || name.toLowerCase().contains("sweep"))
						hasTimeOrSweep = true;
				}
			}

			Assert.assertTrue("Should have at least 1 signal collection", collectionCount > 0);
			Assert.assertTrue("Should have signals in the simulation", totalSignals > 0);

			System.out.println("\n=== Simulation Workflow Complete ===");
			System.out.println("Collections: " + collectionCount + ", Total signals: " + totalSignals);
		}
		finally
		{
			// Cleanup temp files
			tempSpi.delete();
			rawFile.delete();
			tempDir.delete();
		}
	}
}
