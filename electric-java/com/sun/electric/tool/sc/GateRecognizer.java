/* -*- tab-width: 4 -*-
 *
 * Electric(tm) VLSI Design System
 *
 * File: GateRecognizer.java
 * Silicon compiler tool: recognize CMOS gate patterns from transistor-level netlists
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

import java.util.*;

/**
 * Recognizes CMOS gate patterns from transistor-level QUISC netlists and
 * transforms them into gate-level netlists using standard cells from sclib.
 *
 * Supported patterns: INV, NAND2-4, NOR2-4, AND2-4, OR2-4
 *
 * The recognition works by:
 * 1. Parsing QUISC netlist into a graph of transistor instances and nets
 * 2. Identifying NMOS pull-down and PMOS pull-up trees
 * 3. Matching topology against known CMOS gate patterns
 * 4. Replacing transistor groups with equivalent standard cell instances
 */
public class GateRecognizer
{
	private static final Set<String> NMOS_TYPES = new HashSet<String>(Arrays.asList(
		"nmostran", "nmostranweak"));
	private static final Set<String> PMOS_TYPES = new HashSet<String>(Arrays.asList(
		"pmostran", "pmostranweak"));
	private static final Set<String> TRANSISTOR_TYPES = new HashSet<String>();
	static {
		TRANSISTOR_TYPES.addAll(NMOS_TYPES);
		TRANSISTOR_TYPES.addAll(PMOS_TYPES);
	}

	// Parsed QUISC data structures
	private static class QInstance
	{
		String name;
		String type;        // lowercase
		boolean isNmos;
		boolean isPmos;
		String gateNet;     // net connected to gate port (g)
		String port1Net;    // net connected to source port (s)
		String port2Net;    // net connected to drain port (d)
	}

	private static class QCell
	{
		String name;
		List<QInstance> instances = new ArrayList<QInstance>();
		// net name -> list of (instName, portName) connections
		Map<String, List<String[]>> nets = new LinkedHashMap<String, List<String[]>>();
		List<String[]> exports = new ArrayList<String[]>(); // [instName, portName, exportName, direction]
		List<String> preLines = new ArrayList<String>();   // lines before first "create cell"
		List<String> setLines = new ArrayList<String>();   // "set" commands after extract
	}

	/**
	 * Transform a QUISC netlist by recognizing transistor-level gate patterns
	 * and replacing them with standard cell instances.
	 *
	 * @param netlist the original QUISC netlist lines
	 * @return transformed netlist, or original if no transistors found
	 */
	public static List<String> transform(List<String> netlist)
	{
		// Quick check: does this netlist contain transistor instances?
		boolean hasTransistors = false;
		for (String line : netlist)
		{
			String lower = line.trim().toLowerCase();
			if (lower.startsWith("create instance"))
			{
				String[] parts = splitLine(line.trim());
				if (parts.length >= 4)
				{
					String type = parts[3].toLowerCase();
					if (TRANSISTOR_TYPES.contains(type) ||
						type.equals("power") || type.equals("ground"))
					{
						hasTransistors = true;
						break;
					}
				}
			}
		}
		if (!hasTransistors) return netlist;

		System.out.println("  Gate Recognition: transistor-level design detected, analyzing...");
		System.out.println("  Gate Recognition: original netlist:");
		for (String line : netlist)
			System.out.println("    " + line);

		// Parse the netlist into structured form
		List<QCell> cells = parseNetlist(netlist);

		// Transform each cell
		List<String> result = new ArrayList<String>();
		for (QCell cell : cells)
		{
			result.addAll(cell.preLines);
			transformCell(cell, result);
		}

		// Add closing line
		for (String line : netlist)
		{
			if (line.trim().startsWith("!********* End"))
			{
				result.add(line);
				break;
			}
		}

		System.out.println("  Gate Recognition: transformed netlist:");
		for (String line : result)
			System.out.println("    " + line);

		return result;
	}

	private static List<QCell> parseNetlist(List<String> netlist)
	{
		List<QCell> cells = new ArrayList<QCell>();
		QCell currentCell = null;
		boolean afterExtract = false;
		List<String> preLines = new ArrayList<String>();

		for (String line : netlist)
		{
			String trimmed = line.trim();
			if (trimmed.length() == 0 || trimmed.startsWith("!"))
			{
				if (currentCell == null)
					preLines.add(line);
				continue;
			}

			String[] parts = splitLine(trimmed);
			if (parts.length == 0) continue;

			if (parts[0].equalsIgnoreCase("create") && parts.length >= 3 &&
				parts[1].equalsIgnoreCase("cell"))
			{
				currentCell = new QCell();
				currentCell.name = parts[2];
				currentCell.preLines.addAll(preLines);
				preLines.clear();
				cells.add(currentCell);
				afterExtract = false;
			}
			else if (currentCell != null && parts[0].equalsIgnoreCase("create") &&
				parts.length >= 4 && parts[1].equalsIgnoreCase("instance"))
			{
				QInstance inst = new QInstance();
				inst.name = parts[2];
				inst.type = parts[3].toLowerCase();
				inst.isNmos = NMOS_TYPES.contains(inst.type);
				inst.isPmos = PMOS_TYPES.contains(inst.type);
				currentCell.instances.add(inst);
			}
			else if (currentCell != null && parts[0].equalsIgnoreCase("connect"))
			{
				parseConnect(currentCell, parts);
			}
			else if (currentCell != null && parts[0].equalsIgnoreCase("export"))
			{
				if (parts.length >= 4)
				{
					String dir = parts.length >= 5 ? parts[4] : "";
					currentCell.exports.add(new String[]{parts[1], parts[2], parts[3], dir});
				}
			}
			else if (currentCell != null && parts[0].equalsIgnoreCase("extract"))
			{
				afterExtract = true;
			}
			else if (currentCell != null && afterExtract && parts[0].equalsIgnoreCase("set"))
			{
				currentCell.setLines.add(trimmed);
			}
		}
		return cells;
	}

	private static void parseConnect(QCell cell, String[] parts)
	{
		if (parts.length == 4)
		{
			// connect inst port power/ground
			String instName = parts[1];
			String portName = parts[2];
			String target = parts[3]; // "power" or "ground"

			// Create a net name for power/ground
			String netName = "__" + target + "__";
			addToNet(cell, netName, instName, portName);
		}
		else if (parts.length >= 5)
		{
			// connect inst1 port1 inst2 port2
			String inst1 = parts[1], port1 = parts[2];
			String inst2 = parts[3], port2 = parts[4];

			// Find or create a shared net
			String net1 = findNet(cell, inst1, port1);
			String net2 = findNet(cell, inst2, port2);

			if (net1 != null && net2 != null && !net1.equals(net2))
			{
				// Merge nets
				mergeNets(cell, net1, net2);
			}
			else if (net1 != null)
			{
				addToNet(cell, net1, inst2, port2);
			}
			else if (net2 != null)
			{
				addToNet(cell, net2, inst1, port1);
			}
			else
			{
				// Create new net
				String netName = "net_" + cell.nets.size();
				addToNet(cell, netName, inst1, port1);
				addToNet(cell, netName, inst2, port2);
			}
		}
	}

	private static String findNet(QCell cell, String instName, String portName)
	{
		for (Map.Entry<String, List<String[]>> entry : cell.nets.entrySet())
		{
			for (String[] conn : entry.getValue())
			{
				if (conn[0].equals(instName) && conn[1].equals(portName))
					return entry.getKey();
			}
		}
		return null;
	}

	private static void addToNet(QCell cell, String netName, String instName, String portName)
	{
		List<String[]> conns = cell.nets.get(netName);
		if (conns == null)
		{
			conns = new ArrayList<String[]>();
			cell.nets.put(netName, conns);
		}
		conns.add(new String[]{instName, portName});
	}

	private static void mergeNets(QCell cell, String keepNet, String removeNet)
	{
		List<String[]> removeConns = cell.nets.remove(removeNet);
		if (removeConns == null) return;
		List<String[]> keepConns = cell.nets.get(keepNet);
		if (keepConns == null)
		{
			cell.nets.put(keepNet, removeConns);
		}
		else
		{
			keepConns.addAll(removeConns);
		}
	}

	private static void transformCell(QCell cell, List<String> result)
	{
		// Resolve transistor port nets
		for (QInstance inst : cell.instances)
		{
			if (inst.isNmos || inst.isPmos)
			{
				inst.gateNet = findNet(cell, inst.name, "g");
				inst.port1Net = findNet(cell, inst.name, "s");
				inst.port2Net = findNet(cell, inst.name, "d");
			}
		}

			// Separate transistors from non-transistor instances
		List<QInstance> transistors = new ArrayList<QInstance>();
		List<QInstance> otherInsts = new ArrayList<QInstance>();
		Set<String> removedInsts = new HashSet<String>(); // transistors + power/ground instances to remove
		for (QInstance inst : cell.instances)
		{
			if (inst.isNmos || inst.isPmos)
			{
				transistors.add(inst);
				removedInsts.add(inst.name);
			}
			else if (inst.type.equals("power") || inst.type.equals("ground"))
			{
				removedInsts.add(inst.name);
			}
			else
			{
				otherInsts.add(inst);
			}
		}

		if (transistors.isEmpty())
		{
			// No transistors, output as-is
			outputCellAsIs(cell, result);
			return;
		}

		// Group transistors into gates by analyzing connectivity
		List<RecognizedGate> gates = recognizeGates(transistors, cell);

		// Find transistors that weren't recognized
		Set<String> recognizedInsts = new HashSet<String>();
		for (RecognizedGate gate : gates)
		{
			for (QInstance t : gate.transistors)
				recognizedInsts.add(t.name);
		}
		List<QInstance> unrecognized = new ArrayList<QInstance>();
		for (QInstance t : transistors)
		{
			if (!recognizedInsts.contains(t.name))
				unrecognized.add(t);
		}

		if (!unrecognized.isEmpty())
		{
			System.out.println("  Gate Recognition WARNING: " + unrecognized.size() +
				" transistor(s) not recognized as standard gates");
			for (QInstance t : unrecognized)
				System.out.println("    Unrecognized: " + t.name + " (" + t.type + ")");
		}

		// Report what was recognized
		for (RecognizedGate gate : gates)
		{
			System.out.println("  Gate Recognition: " + gate.transistors.size() +
				" transistors -> " + gate.cellType +
				" (inputs: " + gate.inputNets.size() + ", output: " + gate.outputNet + ")");
		}

		// Generate transformed QUISC netlist
		result.add("create cell " + cell.name);

		// Write non-transistor instances
		for (QInstance inst : otherInsts)
			result.add("create instance " + inst.name + " " + inst.type);

		// Write recognized gates as standard cell instances
		int gateNum = 0;
		Map<String, String[]> netRemap = new HashMap<String, String[]>(); // old net -> [new inst, new port]
		for (RecognizedGate gate : gates)
		{
			String instName = gate.cellType + "_" + gateNum++;
			result.add("create instance " + instName + " " + gate.cellType);

			// Map gate nets to standard cell ports
			if (gate.inputNets.size() == 1)
			{
				// Single input gate (inverter): port "a"
				remapNet(netRemap, gate.inputNets.get(0), instName, "a", gate.transistors);
			}
			else
			{
				// Multi-input gate: ports "a1", "a2", etc.
				for (int i = 0; i < gate.inputNets.size(); i++)
				{
					remapNet(netRemap, gate.inputNets.get(i), instName, "a" + (i + 1), gate.transistors);
				}
			}
			// Output port: "y"
			remapNet(netRemap, gate.outputNet, instName, "y", gate.transistors);

			// Power/ground: handled automatically by SC extract phase
			// (leaf cell vdd/gnd exports are stored in ntp.power/ntp.ground)
		}

		// Generate connect commands
		Set<String> connectedPairs = new HashSet<String>();
		for (Map.Entry<String, List<String[]>> entry : cell.nets.entrySet())
		{
			String netName = entry.getKey();
			List<String[]> conns = entry.getValue();

			// Replace transistor connections with gate connections
			List<String[]> newConns = new ArrayList<String[]>();
			for (String[] conn : conns)
			{
				if (recognizedInsts.contains(conn[0]))
				{
					// This was a transistor port - check if remapped
					String[] remap = netRemap.get(netName + ":" + conn[0]);
					if (remap != null)
					{
						newConns.add(remap);
					}
					// else: internal transistor connection, skip
				}
				else
				{
					newConns.add(conn);
				}
			}

			// Also add any gate ports mapped to this net
			for (Map.Entry<String, String[]> rEntry : netRemap.entrySet())
			{
				if (rEntry.getKey().startsWith(netName + ":"))
				{
					String[] remap = rEntry.getValue();
					boolean found = false;
					for (String[] c : newConns)
					{
						if (c[0].equals(remap[0]) && c[1].equals(remap[1])) { found = true; break; }
					}
					if (!found) newConns.add(remap);
				}
			}

			// Remove duplicates and generate connects
			if (netName.equals("__power__") || netName.equals("__ground__"))
			{
				// Power/ground ports on leaf cells (vdd, gnd) are automatically
				// connected to the cell's power/ground rails during the extract phase.
				// Do NOT emit connect commands for them — GetNetlist stores them
				// in ntp.power/ntp.ground, not ntp.ports, so findPp() can't find them.
			}
			else if (newConns.size() >= 2)
			{
				String[] first = newConns.get(0);
				for (int i = 1; i < newConns.size(); i++)
				{
					String[] other = newConns.get(i);
					String key = first[0] + ":" + first[1] + ":" + other[0] + ":" + other[1];
					String keyRev = other[0] + ":" + other[1] + ":" + first[0] + ":" + first[1];
					if (connectedPairs.add(key))
					{
						connectedPairs.add(keyRev);
						result.add("connect " + first[0] + " " + first[1] + " " + other[0] + " " + other[1]);
					}
				}
			}
		}

		// Generate export commands
		for (String[] exp : cell.exports)
		{
			String instName = exp[0];
			String portName = exp[1];
			String exportName = exp[2];
			String direction = exp[3];

			if (recognizedInsts.contains(instName))
			{
				// Find which gate replaced this transistor
				String netOfPort = findNet(cell, instName, portName);
				if (netOfPort != null)
				{
					String[] remap = netRemap.get(netOfPort + ":" + instName);
					if (remap != null)
					{
						instName = remap[0];
						portName = remap[1];
					}
				}
			}

			String exportLine = "export " + instName + " " + portName + " " + exportName;
			if (direction.length() > 0) exportLine += " " + direction;
			result.add(exportLine);
		}

		result.add("extract");

		// Set commands — filter out references to removed instances
		for (String setLine : cell.setLines)
		{
			String[] setParts = splitLine(setLine);
			if (setParts.length >= 3 && setParts[0].equalsIgnoreCase("set") &&
				setParts[1].equalsIgnoreCase("node-name"))
			{
				String instRef = setParts[2];
				if (removedInsts.contains(instRef)) continue; // skip references to removed transistors/power/ground
			}
			result.add(setLine);
		}

		result.add("");
	}

	private static void remapNet(Map<String, String[]> netRemap, String netName,
		String newInst, String newPort, List<QInstance> transistors)
	{
		if (netName == null) return;
		for (QInstance t : transistors)
		{
			netRemap.put(netName + ":" + t.name, new String[]{newInst, newPort});
		}
	}

	// ==================== GATE PATTERN RECOGNITION ====================

	private static class RecognizedGate
	{
		String cellType;           // "inverter", "nand2", "nor2", etc.
		List<String> inputNets;    // gate input net names
		String outputNet;          // gate output net name
		List<QInstance> transistors; // transistors that form this gate
	}

	private static List<RecognizedGate> recognizeGates(List<QInstance> transistors, QCell cell)
	{
		List<RecognizedGate> result = new ArrayList<RecognizedGate>();
		Set<String> used = new HashSet<String>();

		// Separate into NMOS and PMOS
		List<QInstance> nmos = new ArrayList<QInstance>();
		List<QInstance> pmos = new ArrayList<QInstance>();
		for (QInstance t : transistors)
		{
			if (t.isNmos) nmos.add(t);
			else if (t.isPmos) pmos.add(t);
		}

		// Try to match inverters first (1 nMOS + 1 pMOS, same gate, same drain)
		for (QInstance n : nmos)
		{
			if (used.contains(n.name)) continue;
			for (QInstance p : pmos)
			{
				if (used.contains(p.name)) continue;
				if (n.gateNet != null && n.gateNet.equals(p.gateNet) &&
					n.port1Net != null && n.port1Net.equals(p.port1Net) &&
					isGroundNet(n.port2Net) && isPowerNet(p.port2Net))
				{
					RecognizedGate gate = new RecognizedGate();
					gate.cellType = "inverter";
					gate.inputNets = Arrays.asList(n.gateNet);
					gate.outputNet = n.port1Net;
					gate.transistors = Arrays.asList(n, p);
					result.add(gate);
					used.add(n.name);
					used.add(p.name);
					break;
				}
				// Also check swapped drain/source
				if (n.gateNet != null && n.gateNet.equals(p.gateNet) &&
					n.port2Net != null && n.port2Net.equals(p.port2Net) &&
					isGroundNet(n.port1Net) && isPowerNet(p.port1Net))
				{
					RecognizedGate gate = new RecognizedGate();
					gate.cellType = "inverter";
					gate.inputNets = Arrays.asList(n.gateNet);
					gate.outputNet = n.port2Net;
					gate.transistors = Arrays.asList(n, p);
					result.add(gate);
					used.add(n.name);
					used.add(p.name);
					break;
				}
				// Check all drain/source permutations
				RecognizedGate inv = tryMatchInverter(n, p);
				if (inv != null)
				{
					result.add(inv);
					used.add(n.name);
					used.add(p.name);
					break;
				}
			}
		}

		// Try NAND gates: NMOS in series (shared drain-source chain), PMOS in parallel (same output)
		List<QInstance> unusedNmos = new ArrayList<QInstance>();
		List<QInstance> unusedPmos = new ArrayList<QInstance>();
		for (QInstance t : nmos) if (!used.contains(t.name)) unusedNmos.add(t);
		for (QInstance t : pmos) if (!used.contains(t.name)) unusedPmos.add(t);

		RecognizedGate nandGate = tryMatchNand(unusedNmos, unusedPmos);
		if (nandGate != null)
		{
			result.add(nandGate);
			for (QInstance t : nandGate.transistors) used.add(t.name);
			unusedNmos.clear(); unusedPmos.clear();
			for (QInstance t : nmos) if (!used.contains(t.name)) unusedNmos.add(t);
			for (QInstance t : pmos) if (!used.contains(t.name)) unusedPmos.add(t);
		}

		// Try NOR gates: NMOS in parallel (same output), PMOS in series
		RecognizedGate norGate = tryMatchNor(unusedNmos, unusedPmos);
		if (norGate != null)
		{
			result.add(norGate);
			for (QInstance t : norGate.transistors) used.add(t.name);
		}

		return result;
	}

	private static RecognizedGate tryMatchInverter(QInstance n, QInstance p)
	{
		if (n.gateNet == null || p.gateNet == null || !n.gateNet.equals(p.gateNet))
			return null;

		// Try all combinations of drain/source for output
		String nDrain = n.port1Net, nSource = n.port2Net;
		String pDrain = p.port1Net, pSource = p.port2Net;

		// Find output: the net shared between nMOS and pMOS that's not power/ground
		String outputNet = null;
		if (nDrain != null && nDrain.equals(pDrain) && !isPowerNet(nDrain) && !isGroundNet(nDrain))
		{
			if ((isGroundNet(nSource) || isGroundNet(nDrain)) && (isPowerNet(pSource) || isPowerNet(pDrain)))
				outputNet = nDrain;
		}
		if (outputNet == null && nDrain != null && nDrain.equals(pSource) && !isPowerNet(nDrain) && !isGroundNet(nDrain))
		{
			if (isGroundNet(nSource) && isPowerNet(pDrain))
				outputNet = nDrain;
		}
		if (outputNet == null && nSource != null && nSource.equals(pDrain) && !isPowerNet(nSource) && !isGroundNet(nSource))
		{
			if (isGroundNet(nDrain) && isPowerNet(pSource))
				outputNet = nSource;
		}
		if (outputNet == null && nSource != null && nSource.equals(pSource) && !isPowerNet(nSource) && !isGroundNet(nSource))
		{
			if (isGroundNet(nDrain) && isPowerNet(pDrain))
				outputNet = nSource;
		}

		if (outputNet == null) return null;

		RecognizedGate gate = new RecognizedGate();
		gate.cellType = "inverter";
		gate.inputNets = Arrays.asList(n.gateNet);
		gate.outputNet = outputNet;
		gate.transistors = Arrays.asList(n, p);
		return gate;
	}

	private static RecognizedGate tryMatchNand(List<QInstance> nmosList, List<QInstance> pmosList)
	{
		int n = nmosList.size();
		if (n < 2 || n > 4 || pmosList.size() != n) return null;

		// NAND: all PMOS in parallel (same output, each source to VDD)
		// all NMOS in series (chain from ground to output)

		// Check PMOS: all should have same output net, source to power, different gates
		String pOutput = null;
		Set<String> pGates = new LinkedHashSet<String>();
		for (QInstance p : pmosList)
		{
			String out = getNonPowerPort(p);
			if (out == null) return null;
			if (pOutput == null) pOutput = out;
			else if (!pOutput.equals(out)) return null;
			if (p.gateNet == null) return null;
			pGates.add(p.gateNet);
		}
		if (pGates.size() != n) return null;

		// Check NMOS: should form a series chain from ground to output
		// Each NMOS has a unique gate, and they chain drain-source
		String nOutput = findSeriesChainOutput(nmosList);
		if (nOutput == null || !nOutput.equals(pOutput)) return null;

		Set<String> nGates = new LinkedHashSet<String>();
		for (QInstance nm : nmosList) nGates.add(nm.gateNet);
		if (!nGates.equals(pGates)) return null;

		RecognizedGate gate = new RecognizedGate();
		gate.cellType = "nand" + n;
		gate.inputNets = new ArrayList<String>(pGates);
		gate.outputNet = pOutput;
		gate.transistors = new ArrayList<QInstance>();
		gate.transistors.addAll(nmosList);
		gate.transistors.addAll(pmosList);
		System.out.println("  Gate Recognition: found NAND" + n + " (inputs: " + pGates + ")");
		return gate;
	}

	private static RecognizedGate tryMatchNor(List<QInstance> nmosList, List<QInstance> pmosList)
	{
		int n = nmosList.size();
		if (n < 2 || n > 4 || pmosList.size() != n) return null;

		// NOR: all NMOS in parallel (same output, each source to GND)
		// all PMOS in series (chain from power to output)

		// Check NMOS: all should have same output net, source to ground, different gates
		String nOutput = null;
		Set<String> nGates = new LinkedHashSet<String>();
		for (QInstance nm : nmosList)
		{
			String out = getNonGroundPort(nm);
			if (out == null) return null;
			if (nOutput == null) nOutput = out;
			else if (!nOutput.equals(out)) return null;
			if (nm.gateNet == null) return null;
			nGates.add(nm.gateNet);
		}
		if (nGates.size() != n) return null;

		// Check PMOS: should form a series chain from power to output
		String pOutput = findSeriesChainOutput(pmosList);
		if (pOutput == null || !pOutput.equals(nOutput)) return null;

		Set<String> pGates = new LinkedHashSet<String>();
		for (QInstance p : pmosList) pGates.add(p.gateNet);
		if (!pGates.equals(nGates)) return null;

		RecognizedGate gate = new RecognizedGate();
		gate.cellType = "nor" + n;
		gate.inputNets = new ArrayList<String>(nGates);
		gate.outputNet = nOutput;
		gate.transistors = new ArrayList<QInstance>();
		gate.transistors.addAll(nmosList);
		gate.transistors.addAll(pmosList);
		System.out.println("  Gate Recognition: found NOR" + n + " (inputs: " + nGates + ")");
		return gate;
	}

	/**
	 * For a series chain of transistors, find the output net.
	 * The chain goes from power/ground at one end to the output at the other.
	 * Internal nodes connect adjacent transistors.
	 */
	private static String findSeriesChainOutput(List<QInstance> chain)
	{
		if (chain.isEmpty()) return null;

		// Build a graph of drain-source connections
		// Each transistor has two terminals (port1, port2).
		// Find the terminal that connects to power/ground (chain start)
		// and the terminal at the other end (output).

		// Collect all terminal nets
		Map<String, Integer> netCount = new HashMap<String, Integer>();
		for (QInstance t : chain)
		{
			countNet(netCount, t.port1Net);
			countNet(netCount, t.port2Net);
		}

		// The output net appears only once and is not power/ground
		for (Map.Entry<String, Integer> entry : netCount.entrySet())
		{
			String net = entry.getKey();
			if (entry.getValue() == 1 && !isPowerNet(net) && !isGroundNet(net))
				return net;
		}
		return null;
	}

	private static void countNet(Map<String, Integer> counts, String net)
	{
		if (net == null || isPowerNet(net) || isGroundNet(net)) return;
		Integer count = counts.get(net);
		counts.put(net, count == null ? 1 : count + 1);
	}

	private static String getNonPowerPort(QInstance p)
	{
		if (isPowerNet(p.port2Net) && !isPowerNet(p.port1Net) && !isGroundNet(p.port1Net))
			return p.port1Net;
		if (isPowerNet(p.port1Net) && !isPowerNet(p.port2Net) && !isGroundNet(p.port2Net))
			return p.port2Net;
		return null;
	}

	private static String getNonGroundPort(QInstance n)
	{
		if (isGroundNet(n.port2Net) && !isGroundNet(n.port1Net) && !isPowerNet(n.port1Net))
			return n.port1Net;
		if (isGroundNet(n.port1Net) && !isGroundNet(n.port2Net) && !isPowerNet(n.port2Net))
			return n.port2Net;
		return null;
	}

	private static boolean isPowerNet(String net)
	{
		return net != null && net.equals("__power__");
	}

	private static boolean isGroundNet(String net)
	{
		return net != null && net.equals("__ground__");
	}

	private static void outputCellAsIs(QCell cell, List<String> result)
	{
		result.add("create cell " + cell.name);
		for (QInstance inst : cell.instances)
			result.add("create instance " + inst.name + " " + inst.type);
		// Reconstruct connects from nets
		for (Map.Entry<String, List<String[]>> entry : cell.nets.entrySet())
		{
			String netName = entry.getKey();
			List<String[]> conns = entry.getValue();
			if (netName.equals("__power__") || netName.equals("__ground__"))
			{
				String target = netName.equals("__power__") ? "power" : "ground";
				for (String[] conn : conns)
					result.add("connect " + conn[0] + " " + conn[1] + " " + target);
			}
			else if (conns.size() >= 2)
			{
				for (int i = 1; i < conns.size(); i++)
					result.add("connect " + conns.get(0)[0] + " " + conns.get(0)[1] + " " +
						conns.get(i)[0] + " " + conns.get(i)[1]);
			}
		}
		for (String[] exp : cell.exports)
		{
			String line = "export " + exp[0] + " " + exp[1] + " " + exp[2];
			if (exp[3].length() > 0) line += " " + exp[3];
			result.add(line);
		}
		result.add("extract");
		for (String s : cell.setLines) result.add(s);
		result.add("");
	}

	private static String[] splitLine(String line)
	{
		List<String> parts = new ArrayList<String>();
		int i = 0;
		while (i < line.length())
		{
			while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) i++;
			if (i >= line.length()) break;
			if (line.charAt(i) == '"')
			{
				i++;
				int end = line.indexOf('"', i);
				if (end < 0) end = line.length();
				parts.add(line.substring(i, end));
				i = end + 1;
			}
			else
			{
				int start = i;
				while (i < line.length() && line.charAt(i) != ' ' && line.charAt(i) != '\t') i++;
				parts.add(line.substring(start, i));
			}
		}
		return parts.toArray(new String[0]);
	}
}
