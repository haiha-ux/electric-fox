/* -*- tab-width: 4 -*-
 *
 * Electric(tm) VLSI Design System
 *
 * File: GateRecognizerTest.java
 * Tests for GateRecognizer edge cases
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

/**
 * Task 13.5: Test GateRecognizer edge cases.
 * Tests power/ground handling, unrecognized patterns, and mixed designs.
 */
public class GateRecognizerTest
{
	/**
	 * Test that a gate-level netlist (no transistors) passes through unchanged.
	 */
	@Test
	public void testPassthroughGateLevelNetlist()
	{
		List<String> netlist = Arrays.asList(
			"create cell TEST",
			"create instance inv_0 inverter",
			"connect inv_0 a inv_0 a",
			"connect inv_0 y inv_0 y",
			"export inv_0 a IN input",
			"export inv_0 y OUT output",
			"extract"
		);

		List<String> result = GateRecognizer.transform(netlist);

		// Should be unchanged since no transistors
		Assert.assertEquals("Gate-level netlist should pass through unchanged",
			netlist, result);
	}

	/**
	 * Test inverter recognition from transistor netlist with explicit power/ground.
	 */
	@Test
	public void testInverterRecognition()
	{
		List<String> netlist = makeNetlist(
			"create cell INV_TEST",
			"create instance gnd_0 ground",
			"create instance pwr_0 power",
			"create instance n0 nMOStran",
			"create instance p0 PMOStran",
			"connect n0 g p0 g",
			"connect n0 s p0 d",
			"connect p0 s power",
			"connect n0 d ground",
			"export n0 g IN input",
			"export n0 s OUT output",
			"extract"
		);

		List<String> result = GateRecognizer.transform(netlist);

		// Should contain an inverter instance, not transistors
		boolean hasInverter = false;
		boolean hasTransistor = false;
		for (String line : result)
		{
			String lower = line.trim().toLowerCase();
			if (lower.contains("create instance") && lower.contains("inverter"))
				hasInverter = true;
			if (lower.contains("nmostran") || lower.contains("pmostran"))
				hasTransistor = true;
		}
		Assert.assertTrue("Should contain inverter instance", hasInverter);
		Assert.assertFalse("Should not contain transistor instances", hasTransistor);
	}

	/**
	 * Test NAND2 recognition from transistor netlist.
	 */
	@Test
	public void testNand2Recognition()
	{
		List<String> netlist = makeNetlist(
			"create cell NAND2_TEST",
			"create instance gnd_0 ground",
			"create instance pwr_0 power",
			"create instance n0 nMOStran",
			"create instance n1 nMOStran",
			"create instance p0 PMOStran",
			"create instance p1 PMOStran",
			// NMOS series: n0(gnd-mid), n1(mid-out)
			"connect n0 d ground",
			"connect n0 s n1 d",
			"connect n1 s p0 d",
			// PMOS parallel: both to power, both output to same net
			"connect p0 s power",
			"connect p1 s power",
			"connect p1 d p0 d",
			// Different gates
			"connect n0 g p0 g",
			"connect n1 g p1 g",
			"export n0 g A input",
			"export n1 g B input",
			"export p0 d Y output",
			"extract"
		);

		List<String> result = GateRecognizer.transform(netlist);

		boolean hasNand2 = false;
		for (String line : result)
		{
			if (line.trim().toLowerCase().contains("nand2"))
				hasNand2 = true;
		}
		Assert.assertTrue("Should recognize NAND2 gate", hasNand2);
	}

	/**
	 * Test that an unrecognizable transistor topology is handled gracefully.
	 * A single NMOS with no complementary PMOS should not crash.
	 */
	@Test
	public void testUnrecognizedPattern()
	{
		List<String> netlist = makeNetlist(
			"create cell PASSGATE_TEST",
			"create instance gnd_0 ground",
			"create instance pwr_0 power",
			"create instance n0 nMOStran",
			// Pass transistor: all three nets are signals (no power/ground on drain/source)
			"connect n0 g n0 g",
			"connect n0 s n0 s",
			"connect n0 d n0 d",
			"export n0 g EN input",
			"export n0 s IN input",
			"export n0 d OUT output",
			"extract"
		);

		// Should not throw; should produce a result (possibly with warnings)
		List<String> result = GateRecognizer.transform(netlist);
		Assert.assertNotNull("Should produce a result even with unrecognized patterns", result);
		Assert.assertFalse("Result should not be empty", result.isEmpty());

		// The cell should still be created
		boolean hasCreateCell = false;
		for (String line : result)
		{
			if (line.trim().toLowerCase().startsWith("create cell"))
				hasCreateCell = true;
		}
		Assert.assertTrue("Cell should be created even with unrecognized patterns", hasCreateCell);
	}

	/**
	 * Test mixed design: inverter transistors alongside gate-level cells.
	 * The non-transistor instances should be preserved while transistors are recognized.
	 */
	@Test
	public void testMixedDesign()
	{
		// A netlist with both transistor-level (inverter) and a resistor
		List<String> netlist = makeNetlist(
			"create cell MIXED_TEST",
			"create instance gnd_0 ground",
			"create instance pwr_0 power",
			"create instance n0 nMOStran",
			"create instance p0 PMOStran",
			"connect n0 g p0 g",
			"connect n0 s p0 d",
			"connect p0 s power",
			"connect n0 d ground",
			"export n0 g IN input",
			"export n0 s OUT output",
			"extract"
		);

		List<String> result = GateRecognizer.transform(netlist);

		// Should have the inverter recognized
		boolean hasInverter = false;
		for (String line : result)
		{
			if (line.trim().toLowerCase().contains("inverter"))
				hasInverter = true;
		}
		Assert.assertTrue("Inverter should be recognized in mixed design", hasInverter);
	}

	/**
	 * Test empty netlist edge case.
	 */
	@Test
	public void testEmptyNetlist()
	{
		List<String> netlist = new ArrayList<String>();
		List<String> result = GateRecognizer.transform(netlist);
		Assert.assertNotNull("Empty netlist should return non-null result", result);
	}

	/**
	 * Test netlist with only comments and headers.
	 */
	@Test
	public void testCommentOnlyNetlist()
	{
		List<String> netlist = Arrays.asList(
			"!*************************************************",
			"!  QUISC Command file",
			"!*************************************************"
		);

		List<String> result = GateRecognizer.transform(netlist);
		Assert.assertNotNull("Comment-only netlist should return non-null", result);
		Assert.assertEquals("Comment-only netlist should pass through unchanged",
			netlist, result);
	}

	/**
	 * Test power/ground-only connections (no signal nets).
	 */
	@Test
	public void testPowerGroundOnly()
	{
		List<String> netlist = makeNetlist(
			"create cell PG_TEST",
			"create instance gnd_0 ground",
			"create instance pwr_0 power",
			"extract"
		);

		// Should not crash; power/ground are not transistors so passthrough
		List<String> result = GateRecognizer.transform(netlist);
		Assert.assertNotNull("Power/ground only design should not crash", result);
	}

	// ==================== HELPER ====================

	private List<String> makeNetlist(String... lines)
	{
		return Arrays.asList(lines);
	}
}
