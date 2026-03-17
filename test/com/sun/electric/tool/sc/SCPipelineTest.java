/* -*- tab-width: 4 -*-
 *
 * Electric(tm) VLSI Design System
 *
 * File: SCPipelineTest.java
 * End-to-end tests for the Silicon Compiler pipeline
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
package com.sun.electric.tool.sc;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import com.sun.electric.database.EditingPreferences;
import com.sun.electric.database.hierarchy.Cell;
import com.sun.electric.database.hierarchy.EDatabase;
import com.sun.electric.database.hierarchy.Library;
import com.sun.electric.lib.LibFile;
import com.sun.electric.technology.Technology;
import com.sun.electric.tool.io.FileType;
import com.sun.electric.tool.io.input.LibraryFiles;
import com.sun.electric.tool.io.output.GenerateVHDL;
import com.sun.electric.tool.user.CompileVHDL;
import com.sun.electric.tool.util.test.AbstractJunitBaseClass;

/**
 * End-to-end tests for the Silicon Compiler pipeline.
 * Tests the full flow: Schematic -> VHDL -> QUISC Netlist -> Place -> Route -> Layout
 */
public class SCPipelineTest extends AbstractJunitBaseClass
{
	private static final String NOT_LIB_FILE = "NOT.jelib";

	/**
	 * Task 12.7: End-to-end test: NOT gate schematic -> layout via SC.
	 * Runs the complete Silicon Compiler pipeline on a NOT gate schematic.
	 */
	@Test
	public void testNotGateSCPipeline() throws Exception
	{
		// Load the NOT gate library
		Library lib = loadLibrary("NOT", NOT_LIB_FILE, LoadLibraryType.fileSystem);
		Assert.assertNotNull("NOT library not loaded", lib);

		Cell schCell = lib.findNodeProto("NOT{sch}");
		Assert.assertNotNull("NOT{sch} cell not found", schCell);

		EditingPreferences ep = new EditingPreferences(true, EDatabase.serverDatabase().getTechPool());

		// Ensure MOCMOS technology is available
		Technology mocmos = Technology.getMocmosTechnology();
		Assert.assertNotNull("MOCMOS technology required", mocmos);

		// Load the standard cell library (sclib) required by the SC pipeline
		Library sclib = Library.findLibrary(SilComp.SCLIBNAME);
		if (sclib == null)
		{
			System.out.println("Loading standard cell library: " + SilComp.SCLIBNAME);
			URL fileURL = LibFile.getLibFile(SilComp.SCLIBNAME + ".jelib");
			Assert.assertNotNull("sclib.jelib URL not found", fileURL);
			LibraryFiles.readLibrary(ep, fileURL, null, FileType.JELIB, true);
			sclib = Library.findLibrary(SilComp.SCLIBNAME);
		}
		Assert.assertNotNull("Standard cell library must be loaded", sclib);

		// Step 1: Generate VHDL from schematic
		System.out.println("=== Step 1: Generate VHDL ===");
		GenerateVHDL.VHDLPreferences vhp = new GenerateVHDL.VHDLPreferences(false);
		List<String> vhdlStrings = GenerateVHDL.convertCell(schCell, vhp);
		Assert.assertNotNull("VHDL generation should produce output", vhdlStrings);
		Assert.assertFalse("VHDL should not be empty", vhdlStrings.isEmpty());

		System.out.println("VHDL output (" + vhdlStrings.size() + " lines):");
		for (String line : vhdlStrings)
			System.out.println("  " + line);

		// Step 2: Create VHDL cell and compile to QUISC netlist
		System.out.println("\n=== Step 2: Compile VHDL ===");
		String vhdlCellName = "NOT{vhdl}";
		Cell vhdlCell = lib.findNodeProto(vhdlCellName);
		if (vhdlCell == null)
			vhdlCell = Cell.makeInstance(ep, lib, vhdlCellName);
		Assert.assertNotNull("VHDL cell creation failed", vhdlCell);

		String[] vhdlArray = vhdlStrings.toArray(new String[0]);
		vhdlCell.setTextViewContents(vhdlArray, ep);

		CompileVHDL compiler = new CompileVHDL(vhdlCell);
		Assert.assertFalse("VHDL compilation should have no errors", compiler.hasErrors());

		List<String> netlistStrings = compiler.getQUISCNetlist(lib, false);
		Assert.assertNotNull("QUISC netlist should be produced", netlistStrings);
		Assert.assertFalse("QUISC netlist should not be empty", netlistStrings.isEmpty());

		System.out.println("QUISC netlist (" + netlistStrings.size() + " lines):");
		for (String line : netlistStrings)
			System.out.println("  " + line);

		// Step 2.5: Gate recognition (transistor-to-gate transform)
		System.out.println("\n=== Step 2.5: Gate Recognition ===");
		List<String> transformedNetlist = GateRecognizer.transform(netlistStrings);
		Assert.assertNotNull("Gate recognition should produce output", transformedNetlist);

		System.out.println("Transformed netlist (" + transformedNetlist.size() + " lines):");
		for (String line : transformedNetlist)
			System.out.println("  " + line);

		// Step 3: Create netlist cell
		System.out.println("\n=== Step 3: Store QUISC Netlist ===");
		String netCellName = "NOT{net.quisc}";
		Cell netCell = lib.findNodeProto(netCellName);
		if (netCell == null)
			netCell = Cell.makeInstance(ep, lib, netCellName);
		Assert.assertNotNull("Netlist cell creation failed", netCell);

		String[] netArray = transformedNetlist.toArray(new String[0]);
		netCell.setTextViewContents(netArray, ep);

		// Step 4: Parse netlist
		System.out.println("\n=== Step 4: Parse Netlist ===");
		GetNetlist gnl = new GetNetlist();
		boolean parseError = gnl.readNetCurCell(netCell);
		Assert.assertFalse("Netlist parsing should succeed", parseError);

		// Step 5: Place cells
		System.out.println("\n=== Step 5: Place Cells ===");
		SilComp.SilCompPrefs prefs = new SilComp.SilCompPrefs(false);
		Place place = new Place(prefs);
		String placeErr = place.placeCells(gnl);
		Assert.assertNull("Placement should succeed (no error): " + placeErr, placeErr);

		// Step 6: Route cells
		System.out.println("\n=== Step 6: Route Cells ===");
		Route route = new Route(prefs);
		String routeErr = route.routeCells(gnl);
		Assert.assertNull("Routing should succeed (no error): " + routeErr, routeErr);

		// Step 7: Generate layout
		System.out.println("\n=== Step 7: Generate Layout ===");
		Maker maker = new Maker(ep, prefs);
		Object result = maker.makeLayout(lib, gnl);

		if (result instanceof String)
		{
			System.out.println("Layout generation error: " + (String) result);
			Assert.fail("Layout generation failed: " + (String) result);
		}

		Assert.assertTrue("Result should be a Cell", result instanceof Cell);
		Cell layoutCell = (Cell) result;
		Assert.assertTrue("Layout cell should have nodes", layoutCell.getNumNodes() > 0);

		System.out.println("\n=== SC Pipeline Complete ===");
		System.out.println("Layout cell: " + layoutCell);
		System.out.println("Nodes: " + layoutCell.getNumNodes());
		System.out.println("Arcs: " + layoutCell.getNumArcs());
	}

	/**
	 * Task 12.8: End-to-end test: NAND gate through SC pipeline.
	 * Uses a manually constructed QUISC netlist for a NAND2 gate.
	 */
	@Test
	public void testNand2SCPipeline() throws Exception
	{
		Cell layoutCell = runSCFromQuisc("NAND2_TEST", new String[] {
			"create cell NAND2_TEST",
			"create instance nand2_0 nand2",
			"connect nand2_0 a1 nand2_0 a1",
			"connect nand2_0 a2 nand2_0 a2",
			"connect nand2_0 y nand2_0 y",
			"export nand2_0 a1 A input",
			"export nand2_0 a2 B input",
			"export nand2_0 y Y output",
			"extract"
		});

		Assert.assertNotNull("NAND2 SC layout should be produced", layoutCell);
		Assert.assertTrue("NAND2 layout should have nodes", layoutCell.getNumNodes() > 0);
		System.out.println("NAND2 layout: " + layoutCell.getNumNodes() + " nodes, " + layoutCell.getNumArcs() + " arcs");
	}

	/**
	 * Task 12.8: End-to-end test: NOR gate through SC pipeline.
	 */
	@Test
	public void testNor2SCPipeline() throws Exception
	{
		Cell layoutCell = runSCFromQuisc("NOR2_TEST", new String[] {
			"create cell NOR2_TEST",
			"create instance nor2_0 nor2",
			"connect nor2_0 a1 nor2_0 a1",
			"connect nor2_0 a2 nor2_0 a2",
			"connect nor2_0 y nor2_0 y",
			"export nor2_0 a1 A input",
			"export nor2_0 a2 B input",
			"export nor2_0 y Y output",
			"extract"
		});

		Assert.assertNotNull("NOR2 SC layout should be produced", layoutCell);
		Assert.assertTrue("NOR2 layout should have nodes", layoutCell.getNumNodes() > 0);
		System.out.println("NOR2 layout: " + layoutCell.getNumNodes() + " nodes, " + layoutCell.getNumArcs() + " arcs");
	}

	/**
	 * Task 12.8: End-to-end test: complex multi-gate circuit through SC pipeline.
	 * Tests a 2-input AND gate (NAND2 + INV) to exercise multi-cell placement and routing.
	 */
	@Test
	public void testMultiGateSCPipeline() throws Exception
	{
		Cell layoutCell = runSCFromQuisc("AND2_TEST", new String[] {
			"create cell AND2_TEST",
			"create instance nand2_0 nand2",
			"create instance inv_0 inverter",
			"connect nand2_0 a1 nand2_0 a1",
			"connect nand2_0 a2 nand2_0 a2",
			"connect nand2_0 y inv_0 a",
			"connect inv_0 y inv_0 y",
			"export nand2_0 a1 A input",
			"export nand2_0 a2 B input",
			"export inv_0 y Y output",
			"extract"
		});

		Assert.assertNotNull("AND2 SC layout should be produced", layoutCell);
		Assert.assertTrue("AND2 layout should have nodes", layoutCell.getNumNodes() > 0);
		System.out.println("AND2 (multi-gate) layout: " + layoutCell.getNumNodes() + " nodes, " + layoutCell.getNumArcs() + " arcs");
	}

	/**
	 * Task 13.6: End-to-end test: transistor NOT gate -> layout via SC.
	 * Tests the full pipeline from transistor-level QUISC through GateRecognizer
	 * to Place/Route/Maker.
	 */
	@Test
	public void testTransistorNotGateSCPipeline() throws Exception
	{
		// Transistor-level QUISC netlist for NOT gate
		String[] transistorNetlist = {
			"create cell TNOT",
			"create instance gnd_0 ground",
			"create instance pwr_0 power",
			"create instance nmos_0 nMOStran",
			"create instance pmos_0 PMOStran",
			"connect nmos_0 g pmos_0 g",
			"connect nmos_0 s pmos_0 d",
			"connect pmos_0 s power",
			"connect nmos_0 d ground",
			"export nmos_0 g IN_ input",
			"export nmos_0 s OUT_ output",
			"extract"
		};

		// Run GateRecognizer first
		List<String> transformed = GateRecognizer.transform(java.util.Arrays.asList(transistorNetlist));
		Assert.assertNotNull("GateRecognizer should produce output", transformed);

		// Verify it recognized an inverter
		boolean hasInverter = false;
		for (String line : transformed)
		{
			if (line.trim().toLowerCase().contains("create instance") &&
				line.trim().toLowerCase().contains("inverter"))
				hasInverter = true;
		}
		Assert.assertTrue("GateRecognizer should recognize NOT as inverter", hasInverter);

		// Now run through SC pipeline
		Cell layoutCell = runSCFromQuisc("TNOT", transformed.toArray(new String[0]));
		Assert.assertNotNull("Transistor NOT gate should produce layout via SC", layoutCell);
		Assert.assertTrue("Layout should have nodes", layoutCell.getNumNodes() > 0);

		System.out.println("Transistor NOT -> SC layout: " + layoutCell.getNumNodes() +
			" nodes, " + layoutCell.getNumArcs() + " arcs");
	}

	/**
	 * Task 13.7: End-to-end test: transistor NAND/NOR -> layout via SC.
	 */
	@Test
	public void testTransistorNandNorSCPipeline() throws Exception
	{
		// Transistor-level QUISC for NAND2
		String[] nandNetlist = {
			"create cell TNAND2",
			"create instance gnd_0 ground",
			"create instance pwr_0 power",
			"create instance n0 nMOStran",
			"create instance n1 nMOStran",
			"create instance p0 PMOStran",
			"create instance p1 PMOStran",
			"connect n0 d ground",
			"connect n0 s n1 d",
			"connect n1 s p0 d",
			"connect p0 s power",
			"connect p1 s power",
			"connect p1 d p0 d",
			"connect n0 g p0 g",
			"connect n1 g p1 g",
			"export n0 g A input",
			"export n1 g B input",
			"export p0 d Y output",
			"extract"
		};

		// GateRecognizer
		List<String> transformed = GateRecognizer.transform(java.util.Arrays.asList(nandNetlist));
		boolean hasNand = false;
		for (String line : transformed)
		{
			if (line.trim().toLowerCase().contains("nand2"))
				hasNand = true;
		}
		Assert.assertTrue("GateRecognizer should recognize NAND2", hasNand);

		// SC pipeline
		Cell nandLayout = runSCFromQuisc("TNAND2", transformed.toArray(new String[0]));
		Assert.assertNotNull("Transistor NAND2 should produce layout via SC", nandLayout);
		System.out.println("Transistor NAND2 -> SC layout: " + nandLayout.getNumNodes() +
			" nodes, " + nandLayout.getNumArcs() + " arcs");

		// Transistor-level QUISC for NOR2
		// NOR2: NMOS parallel (drain=out, source=GND), PMOS series (VDD-mid-out)
		String[] norNetlist = {
			"create cell TNOR2",
			"create instance gnd_0 ground",
			"create instance pwr_0 power",
			"create instance n0 nMOStran",
			"create instance n1 nMOStran",
			"create instance p0 PMOStran",
			"create instance p1 PMOStran",
			// NMOS parallel: both source to ground, both drain to output
			"connect n0 s ground",
			"connect n1 s ground",
			"connect n0 d n1 d",
			// PMOS series: p0(source=VDD, drain=mid), p1(source=mid, drain=out)
			"connect p0 s power",
			"connect p0 d p1 s",
			"connect p1 d n0 d",
			// Gates: n0/p0 share gate A, n1/p1 share gate B
			"connect n0 g p0 g",
			"connect n1 g p1 g",
			"export n0 g A input",
			"export n1 g B input",
			"export n0 d Y output",
			"extract"
		};

		List<String> norTransformed = GateRecognizer.transform(java.util.Arrays.asList(norNetlist));
		boolean hasNor = false;
		for (String line : norTransformed)
		{
			if (line.trim().toLowerCase().contains("nor2"))
				hasNor = true;
		}
		Assert.assertTrue("GateRecognizer should recognize NOR2", hasNor);

		Cell norLayout = runSCFromQuisc("TNOR2", norTransformed.toArray(new String[0]));
		Assert.assertNotNull("Transistor NOR2 should produce layout via SC", norLayout);
		System.out.println("Transistor NOR2 -> SC layout: " + norLayout.getNumNodes() +
			" nodes, " + norLayout.getNumArcs() + " arcs");
	}

	// ==================== HELPER ====================

	/**
	 * Helper: run the SC Place/Route/Maker pipeline from a QUISC netlist.
	 */
	private Cell runSCFromQuisc(String libName, String[] netlistLines) throws Exception
	{
		EditingPreferences ep = new EditingPreferences(true, EDatabase.serverDatabase().getTechPool());

		// Enter database changing mode
		EDatabase.serverDatabase().lowLevelBeginChanging(null);

		// Load sclib if needed
		Library sclib = Library.findLibrary(SilComp.SCLIBNAME);
		if (sclib == null)
		{
			URL fileURL = LibFile.getLibFile(SilComp.SCLIBNAME + ".jelib");
			LibraryFiles.readLibrary(ep, fileURL, null, FileType.JELIB, true);
		}

		// Create library for output
		Library lib = Library.findLibrary(libName);
		if (lib == null)
			lib = Library.newInstance(libName, null);

		// Create netlist cell
		String netCellName = libName + "{net.quisc}";
		Cell netCell = lib.findNodeProto(netCellName);
		if (netCell == null)
			netCell = Cell.makeInstance(ep, lib, netCellName);
		netCell.setTextViewContents(netlistLines, ep);

		// Parse netlist
		GetNetlist gnl = new GetNetlist();
		boolean parseError = gnl.readNetCurCell(netCell);
		Assert.assertFalse("Netlist parsing should succeed for " + libName, parseError);

		// Place
		SilComp.SilCompPrefs prefs = new SilComp.SilCompPrefs(false);
		Place place = new Place(prefs);
		String placeErr = place.placeCells(gnl);
		Assert.assertNull("Placement should succeed for " + libName + ": " + placeErr, placeErr);

		// Route
		Route route = new Route(prefs);
		String routeErr = route.routeCells(gnl);
		Assert.assertNull("Routing should succeed for " + libName + ": " + routeErr, routeErr);

		// Generate layout
		Maker maker = new Maker(ep, prefs);
		Object result = maker.makeLayout(lib, gnl);
		if (result instanceof String)
			Assert.fail("Layout generation failed for " + libName + ": " + (String) result);

		Assert.assertTrue("Result should be a Cell for " + libName, result instanceof Cell);
		return (Cell) result;
	}
}
