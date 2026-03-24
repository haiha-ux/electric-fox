/* -*- tab-width: 4 -*-
 *
 * Electric(tm) VLSI Design System
 *
 * File: AnalogLayoutEngine.java
 * Analog Layout Synthesis Engine v2: row-based analog layout generator
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
package com.sun.electric.tool.sc.analog;

import com.sun.electric.database.EditingPreferences;
import com.sun.electric.database.geometry.EPoint;
import com.sun.electric.database.hierarchy.Cell;
import com.sun.electric.database.variable.VarContext;
import com.sun.electric.database.hierarchy.Export;
import com.sun.electric.database.hierarchy.Library;
import com.sun.electric.database.prototype.PortCharacteristic;
import com.sun.electric.database.topology.ArcInst;
import com.sun.electric.database.topology.NodeInst;
import com.sun.electric.database.topology.PortInst;
import com.sun.electric.technology.*;
import com.sun.electric.technology.TransistorSize;
import com.sun.electric.tool.routing.seaOfGates.SeaOfGatesEngine;

import java.util.*;

/**
 * Analog Layout Synthesis Engine (ALSE) v2 — row-based analog layout.
 *
 * <p>Layout structure:
 * <pre>
 *  [N-Well contacts — VDD tap row]
 *  [PMOS device row]
 *  ~~~ VDD bus (Metal-1 horizontal) ~~~
 *  ~~~ GND bus (Metal-1 horizontal) ~~~
 *  [NMOS device row]
 *  [P-Well contacts — GND tap row]
 * </pre>
 */
public class AnalogLayoutEngine
{
	private final Technology tech;
	private final EditingPreferences ep;
	private CircuitGraph graph;
	private ConstraintExtractor constraints;
	private DeviceGenerator devGen;
	private TechRules rules;
	private Map<CircuitGraph.Device, double[]> placement;
	private List<AnalogRouter.RoutePath> routes;

	// Technology primitives
	private PrimitiveNode nTransistor, pTransistor;
	private PrimitiveNode metal1Pin, metal2Pin, metal3Pin;
	private PrimitiveNode nActiveCon, pActiveCon, polyCon;
	private PrimitiveNode nwellCon, pwellCon;
	private PrimitiveNode m1m2Con;  // Metal-1 to Metal-2 via
	private PrimitiveNode m2m3Con;  // Metal-2 to Metal-3 via
	private PrimitiveNode nwellNode; // pure N-Well layer for fill
	private PrimitiveNode pwellNode; // pure P-Well layer for fill
	private ArcProto metal1Arc, metal2Arc, metal3Arc, polyArc;

	// Routing channel bounds (set during layout generation)
	private double channelBotY, channelTopY;

	public AnalogLayoutEngine(Technology tech, EditingPreferences ep)
	{
		this.tech = tech;
		this.ep = ep;
	}

	public Cell synthesizeFromCell(Cell schematicCell, Library destLib)
	{
		System.out.println("=== ALSE: Analog Layout Synthesis Engine v3 ===");
		System.out.println("  Input: " + schematicCell.describe(false));
		graph = CircuitGraphBuilder.fromCell(schematicCell);
		if (graph == null) { System.out.println("ALSE ERROR: Failed to build circuit graph"); return null; }

		// Hierarchical: flatten sub-cells into parent for global optimization
		if (graph.isHierarchical())
		{
			System.out.println("  Hierarchical design: " + graph.getSubCircuits().size() +
				" sub-cells, " + graph.getTotalDeviceCount() + " total devices");
			graph.flatten();
		}

		return runPipeline(schematicCell.getName(), destLib);
	}

	/**
	 * Recursively synthesize layouts for all sub-circuits, bottom-up.
	 * Each sub-cell gets its own layout cell, which can then be instantiated
	 * in the parent layout.
	 */
	private void synthesizeSubCells(CircuitGraph parentGraph, Library destLib)
	{
		for (CircuitGraph.SubCircuit sc : parentGraph.getSubCircuits())
		{
			CircuitGraph childGraph = sc.getChildGraph();
			String childName = sc.getCellName().replaceAll("[^a-zA-Z0-9_]", "_");

			// Check if layout already exists (avoid re-synthesizing shared cells)
			Cell existing = destLib.findNodeProto(childName + "{lay}");
			if (existing != null)
			{
				System.out.println("  SubCell " + sc.getInstanceName() + " (" + childName +
					"): layout already exists, reusing");
				sc.setLayoutWidth(existing.getDefWidth());
				sc.setLayoutHeight(existing.getDefHeight());
				continue;
			}

			// Recursively synthesize children first
			if (childGraph.isHierarchical())
				synthesizeSubCells(childGraph, destLib);

			// Synthesize this sub-cell's layout
			System.out.println("  Synthesizing sub-cell layout: " + childName);
			AnalogLayoutEngine subEngine = new AnalogLayoutEngine(tech, ep);
			subEngine.graph = childGraph;
			Cell subLayout = subEngine.runPipeline(childName, destLib);
			if (subLayout != null)
			{
				sc.setLayoutWidth(subLayout.getDefWidth());
				sc.setLayoutHeight(subLayout.getDefHeight());
				System.out.println("  SubCell " + childName + " layout: " +
					fmt(subLayout.getDefWidth()) + " x " + fmt(subLayout.getDefHeight()));
			}
		}
	}

	public Cell synthesizeFromSpice(String spiceFile, String subcktName, Library destLib)
	{
		System.out.println("=== ALSE: Analog Layout Synthesis Engine v2 ===");
		System.out.println("  Input: " + spiceFile + (subcktName != null ? " (" + subcktName + ")" : ""));
		graph = CircuitGraphBuilder.fromSpice(spiceFile, subcktName);
		if (graph == null) { System.out.println("ALSE ERROR: Failed to parse SPICE netlist"); return null; }
		return runPipeline(subcktName != null ? subcktName : "analog_layout", destLib);
	}

	private Cell runPipeline(String cellName, Library destLib)
	{
		rules = new TechRules(tech, ep);
		rules.printRuleSummary();
		initTechPrimitives();
		graph.printStats();

		constraints = new ConstraintExtractor(graph);
		constraints.extractAll();

		devGen = new DeviceGenerator(tech, ep);
		computeDeviceSizes();

		AnalogPlacer placer = new AnalogPlacer(graph, constraints);
		placement = placer.place();
		if (placement == null || placement.isEmpty()) { System.out.println("ALSE ERROR: Placement failed"); return null; }

		// Compact placement to minimize area
		compactPlacement();

		AnalogRouter router = new AnalogRouter(graph, placement);
		routes = router.routeAll();

		Cell layoutCell = generateRowBasedLayout(cellName, destLib);
		if (layoutCell != null) System.out.println("=== ALSE Complete: " + layoutCell.describe(false) + " ===");
		return layoutCell;
	}

	// ==================== TECHNOLOGY ====================

	private void initTechPrimitives()
	{
		nTransistor = findPrim("N-Transistor");
		pTransistor = findPrim("P-Transistor");
		metal1Pin = findPrim("Metal-1-Pin");
		metal2Pin = findPrim("Metal-2-Pin");
		metal3Pin = findPrim("Metal-3-Pin");
		nActiveCon = findPrim("Metal-1-N-Active-Con");
		pActiveCon = findPrim("Metal-1-P-Active-Con");
		polyCon = findPrim("Metal-1-Polysilicon-1-Con");
		nwellCon = findPrim("Metal-1-N-Well-Con");
		pwellCon = findPrim("Metal-1-P-Well-Con");
		m1m2Con = findPrim("Metal-1-Metal-2-Con");
		m2m3Con = findPrim("Metal-2-Metal-3-Con");
		nwellNode = findPrim("N-Well-Node");
		pwellNode = findPrim("P-Well-Node");
		metal1Arc = findArc("Metal-1");
		metal2Arc = findArc("Metal-2");
		metal3Arc = findArc("Metal-3");
		polyArc = findArc("Polysilicon-1");
		System.out.println("  Technology: " + tech.getTechName());
		System.out.println("    N-Trans: " + fmt(nTransistor.getDefWidth(ep)) + "x" + fmt(nTransistor.getDefHeight(ep)));
		System.out.println("    P-Trans: " + fmt(pTransistor.getDefWidth(ep)) + "x" + fmt(pTransistor.getDefHeight(ep)));
		System.out.println("    nActCon: " + fmt(nActiveCon.getDefWidth(ep)) + "x" + fmt(nActiveCon.getDefHeight(ep)));
		System.out.println("    pActCon: " + fmt(pActiveCon.getDefWidth(ep)) + "x" + fmt(pActiveCon.getDefHeight(ep)));
		System.out.println("    pwellCon: " + fmt(pwellCon.getDefWidth(ep)) + "x" + fmt(pwellCon.getDefHeight(ep)));
		System.out.println("    nwellCon: " + fmt(nwellCon.getDefWidth(ep)) + "x" + fmt(nwellCon.getDefHeight(ep)));
	}

	private void computeDeviceSizes()
	{
		for (CircuitGraph.Device dev : graph.getDevices())
		{
			PrimitiveNode prim = primForDevice(dev);
			if (prim != null)
			{
				dev.setLayoutWidth(prim.getDefWidth(ep) + rules.getDeviceSpacing());
				dev.setLayoutHeight(prim.getDefHeight(ep));
			}
			else devGen.estimateDeviceSize(dev);
		}
	}

	// ==================== LAYOUT COMPACTION ====================

	/**
	 * Apply 1D constraint-graph compaction to the placement to minimize area.
	 * Uses the device spacing from TechRules as the minimum constraint distance.
	 */
	private void compactPlacement()
	{
		if (placement == null || placement.isEmpty()) return;

		LayoutCompactor compactor = new LayoutCompactor(rules.getDeviceSpacing());

		// Add all placed devices as compaction elements
		Map<CircuitGraph.Device, LayoutCompactor.Element> elemMap = new LinkedHashMap<>();
		for (Map.Entry<CircuitGraph.Device, double[]> entry : placement.entrySet())
		{
			CircuitGraph.Device dev = entry.getKey();
			double[] pos = entry.getValue();
			LayoutCompactor.Element elem = compactor.addElement(dev.getName(),
				pos[0], pos[1], dev.getLayoutWidth(), dev.getLayoutHeight());
			elemMap.put(dev, elem);
		}

		// Run compaction
		compactor.compact();

		// Apply compacted positions back to placement
		for (Map.Entry<CircuitGraph.Device, LayoutCompactor.Element> entry : elemMap.entrySet())
		{
			LayoutCompactor.Element elem = entry.getValue();
			placement.put(entry.getKey(), new double[]{elem.x, elem.y});
		}
	}

	// ==================== SERIES CHAIN DETECTION ====================

	/**
	 * Detect series-connected transistor chains within a group of same-type devices.
	 * Series: device A's source connects to device B's drain via a non-supply net.
	 * Returns ordered chains where adjacent devices share a diffusion node.
	 */
	private List<List<CircuitGraph.Device>> findSeriesChains(List<CircuitGraph.Device> devs)
	{
		List<List<CircuitGraph.Device>> chains = new ArrayList<List<CircuitGraph.Device>>();
		Set<CircuitGraph.Device> used = new HashSet<CircuitGraph.Device>();

		for (CircuitGraph.Device dev : devs)
		{
			if (used.contains(dev)) continue;

			// Try to build a chain starting from this device
			List<CircuitGraph.Device> chain = new ArrayList<CircuitGraph.Device>();
			chain.add(dev);
			used.add(dev);

			// Extend chain forward: find device whose drain connects to current tail's source
			boolean extended = true;
			while (extended)
			{
				extended = false;
				CircuitGraph.Device tail = chain.get(chain.size() - 1);
				CircuitGraph.Net tailSource = tail.getNet(CircuitGraph.Pin.Function.SOURCE);
				if (tailSource != null && !tailSource.isSupply())
				{
					for (CircuitGraph.Device cand : devs)
					{
						if (used.contains(cand)) continue;
						CircuitGraph.Net candDrain = cand.getNet(CircuitGraph.Pin.Function.DRAIN);
						if (candDrain == tailSource)
						{
							chain.add(cand);
							used.add(cand);
							extended = true;
							break;
						}
					}
				}
			}

			// Extend chain backward: find device whose source connects to current head's drain
			extended = true;
			while (extended)
			{
				extended = false;
				CircuitGraph.Device head = chain.get(0);
				CircuitGraph.Net headDrain = head.getNet(CircuitGraph.Pin.Function.DRAIN);
				if (headDrain != null && !headDrain.isSupply())
				{
					for (CircuitGraph.Device cand : devs)
					{
						if (used.contains(cand)) continue;
						CircuitGraph.Net candSource = cand.getNet(CircuitGraph.Pin.Function.SOURCE);
						if (candSource == headDrain)
						{
							chain.add(0, cand);
							used.add(cand);
							extended = true;
							break;
						}
					}
				}
			}

			chains.add(chain);
		}
		return chains;
	}

	/**
	 * Get the set of internal nets shared between adjacent series transistors.
	 * These nets don't need external contacts or routing — they are connected
	 * by physical diffusion abutment.
	 */
	private Set<CircuitGraph.Net> getInternalSeriesNets(List<List<CircuitGraph.Device>> chains)
	{
		Set<CircuitGraph.Net> internal = new HashSet<CircuitGraph.Net>();
		for (List<CircuitGraph.Device> chain : chains)
		{
			if (chain.size() < 2) continue;
			for (int i = 0; i < chain.size() - 1; i++)
			{
				CircuitGraph.Net shared = chain.get(i).getNet(CircuitGraph.Pin.Function.SOURCE);
				if (shared != null && !shared.isSupply()) internal.add(shared);
			}
		}
		return internal;
	}

	// ==================== ROW-BASED LAYOUT ====================

	private Cell generateRowBasedLayout(String cellName, Library destLib)
	{
		System.out.println("  ALSE Row-Based Layout Generation...");

		Cell cell = Cell.makeInstance(ep, destLib, cellName + "{lay}");
		if (cell == null) { System.out.println("ALSE ERROR: Cannot create layout cell"); return null; }

		// Separate NMOS/PMOS, sort by SA X-position
		List<CircuitGraph.Device> nDevs = new ArrayList<CircuitGraph.Device>();
		List<CircuitGraph.Device> pDevs = new ArrayList<CircuitGraph.Device>();
		for (CircuitGraph.Device d : graph.getDevices())
		{
			if (d.getType() == CircuitGraph.Device.Type.NMOS) nDevs.add(d);
			else if (d.getType() == CircuitGraph.Device.Type.PMOS) pDevs.add(d);
		}
		sortByX(nDevs);
		sortByX(pDevs);

		// Detect series chains for shared diffusion placement
		List<List<CircuitGraph.Device>> nChains = findSeriesChains(nDevs);
		List<List<CircuitGraph.Device>> pChains = findSeriesChains(pDevs);
		Set<CircuitGraph.Net> internalNets = getInternalSeriesNets(nChains);
		internalNets.addAll(getInternalSeriesNets(pChains));

		int nMaxStack = 1, pMaxStack = 1;
		for (List<CircuitGraph.Device> c : nChains) nMaxStack = Math.max(nMaxStack, c.size());
		for (List<CircuitGraph.Device> c : pChains) pMaxStack = Math.max(pMaxStack, c.size());

		System.out.println("    Series chains: NMOS=" + nChains.size() + " (max " + nMaxStack +
			"), PMOS=" + pChains.size() + " (max " + pMaxStack + ")");
		System.out.println("    Internal shared nets: " + internalNets.size());

		// Dimensions
		double nW = nTransistor != null ? nTransistor.getDefWidth(ep) : 15;
		double nH = nTransistor != null ? nTransistor.getDefHeight(ep) : 22;
		double pW = pTransistor != null ? pTransistor.getDefWidth(ep) : 15;
		double pH = pTransistor != null ? pTransistor.getDefHeight(ep) : 22;

		// Effective row heights account for series stacking
		double nRowH = nH * nMaxStack;
		double pRowH = pH * pMaxStack;

		// Row Y positions computed from technology rules
		double DEVICE_SPACING = rules.getDeviceSpacing();
		double ROW_SEPARATION = rules.getRowSeparation();
		double WELL_CON_MARGIN = rules.getWellContactMargin();

		// Estimate channel nets: signal nets that span both NMOS and PMOS regions
		// need M3 channel routing. Expand row separation if many nets need routing.
		int estimatedChannelNets = graph.getSignalNets().size();
		double viaW23 = m2m3Con != null ? m2m3Con.getDefWidth(ep) : 5;
		double m2Spacing = rules.getLayerSpacing(rules.getMetal2Layer(), rules.getMetal2Layer());
		double estTrackPitch = snap(Math.max(viaW23 + m2Spacing, 8));
		double channelNeeded = estimatedChannelNets * estTrackPitch;
		double channelAvailable = ROW_SEPARATION - 2 * rules.getSupplyBusOffset() - 6; // minus buses and margins
		if (channelNeeded > channelAvailable)
		{
			double extraSep = channelNeeded - channelAvailable;
			ROW_SEPARATION = snap(ROW_SEPARATION + extraSep);
			System.out.println("    Channel expanded: " + estimatedChannelNets + " est. nets need " +
				fmt(channelNeeded) + " lambda, row separation -> " + fmt(ROW_SEPARATION));
		}
		double CONTACT_OFFSET = rules.getContactOffset();
		double BUS_OFFSET = rules.getSupplyBusOffset();

		// nmosY = center of NMOS row (bottom of stack)
		double nmosBaseY = snap(0);
		// pmosY = center of PMOS row (accounts for stacking)
		double pmosBaseY = snap(nmosBaseY + nRowH / 2 + ROW_SEPARATION + pRowH / 2);

		// Supply bus Y positions (between NMOS and PMOS rows)
		double gndBusY = snap(nmosBaseY + nRowH / 2 + BUS_OFFSET);
		double vddBusY = snap(pmosBaseY - pRowH / 2 - BUS_OFFSET);

		// Well contact Y positions
		double pwellY = snap(nmosBaseY - nRowH / 2 - WELL_CON_MARGIN);
		double nwellY = snap(pmosBaseY + pRowH / 2 + WELL_CON_MARGIN);

		// Routing channel between supply buses for signal nets
		double m1Spacing = rules.getLayerSpacing(rules.getMetal1Layer(), rules.getMetal1Layer());
		double m1HalfWidth = metal1Arc != null ? metal1Arc.getDefaultLambdaBaseWidth(ep) / 2 : 0.75;
		channelBotY = snap(gndBusY + m1HalfWidth + m1Spacing);
		channelTopY = snap(vddBusY - m1HalfWidth - m1Spacing);

		System.out.println("    Row layout: nmosBaseY=" + fmt(nmosBaseY) + " pmosBaseY=" + fmt(pmosBaseY));
		System.out.println("    Row heights: nmos=" + fmt(nRowH) + " pmos=" + fmt(pRowH));
		System.out.println("    Bus: gndY=" + fmt(gndBusY) + " vddY=" + fmt(vddBusY));
		System.out.println("    Routing channel: " + fmt(channelBotY) + " to " + fmt(channelTopY));
		System.out.println("    Well contacts: pwellY=" + fmt(pwellY) + " nwellY=" + fmt(nwellY));

		// ==================== PLACE TRANSISTORS ====================
		// Series chains are stacked vertically at the same X.
		// Within a chain, device[0] is at the bottom, device[N-1] at the top.
		// Adjacent devices share a diffusion node (source[i] = drain[i+1]).
		// The chain's GND/VDD terminal is at the top, output/signal at the bottom.
		Map<CircuitGraph.Device, NodeInst> devNodes = new LinkedHashMap<CircuitGraph.Device, NodeInst>();

		double xCursor = snap(0);
		for (List<CircuitGraph.Device> chain : nChains)
		{
			double cx = snap(xCursor + nW / 2);
			// Stack from bottom: device[0] at bottom of NMOS region
			double baseY = snap(nmosBaseY - nRowH / 2 + nH / 2);
			for (int i = 0; i < chain.size(); i++)
			{
				double cy = snap(baseY + i * nH);
				CircuitGraph.Device dev = chain.get(i);
				NodeInst ni = NodeInst.makeInstance(nTransistor, ep,
					EPoint.fromLambda(cx, cy), nW, nH, cell);
				if (ni != null)
				{
					devNodes.put(dev, ni);
					applySchematicSize(ni, dev);
				}
				System.out.println("    NMOS " + dev.getName() + " center=(" + fmt(cx) + "," + fmt(cy) +
					")" + (chain.size() > 1 ? " [series " + (i+1) + "/" + chain.size() + "]" : ""));
			}
			xCursor = snap(xCursor + nW + DEVICE_SPACING);
		}

		double pCur = snap(0);
		for (List<CircuitGraph.Device> chain : pChains)
		{
			double cx = snap(pCur + pW / 2);
			double baseY = snap(pmosBaseY - pRowH / 2 + pH / 2);
			for (int i = 0; i < chain.size(); i++)
			{
				double cy = snap(baseY + i * pH);
				CircuitGraph.Device dev = chain.get(i);
				NodeInst ni = NodeInst.makeInstance(pTransistor, ep,
					EPoint.fromLambda(cx, cy), pW, pH, cell);
				if (ni != null)
				{
					devNodes.put(dev, ni);
					applySchematicSize(ni, dev);
				}
				System.out.println("    PMOS " + dev.getName() + " center=(" + fmt(cx) + "," + fmt(cy) +
					")" + (chain.size() > 1 ? " [series " + (i+1) + "/" + chain.size() + "]" : ""));
			}
			pCur = snap(pCur + pW + DEVICE_SPACING);
		}

		double totalW = Math.max(xCursor, pCur);

		// ==================== WIRE SERIES CHAIN INTERNAL NODES ====================
		// Connect adjacent series transistors' shared diffusion ports with active arcs
		int seriesWires = 0;
		for (List<CircuitGraph.Device> chain : nChains)
		{
			for (int i = 0; i < chain.size() - 1; i++)
			{
				// chain[i].source connects to chain[i+1].drain (shared internal node)
				NodeInst niBot = devNodes.get(chain.get(i));
				NodeInst niTop = devNodes.get(chain.get(i + 1));
				if (niBot == null || niTop == null) continue;
				PortInst botSource = findPort(niBot, "diff-top");    // source = diff-top
				PortInst topDrain = findPort(niTop, "diff-bottom");  // drain = diff-bottom
				if (botSource != null && topDrain != null)
				{
					ArcProto arc = compatibleArc(botSource, topDrain);
					if (arc != null)
					{
						try { if (ArcInst.makeInstance(arc, ep, botSource, topDrain) != null) seriesWires++; }
						catch (Exception e) { /* skip */ }
					}
				}
			}
		}
		for (List<CircuitGraph.Device> chain : pChains)
		{
			for (int i = 0; i < chain.size() - 1; i++)
			{
				NodeInst niBot = devNodes.get(chain.get(i));
				NodeInst niTop = devNodes.get(chain.get(i + 1));
				if (niBot == null || niTop == null) continue;
				PortInst botSource = findPort(niBot, "diff-top");
				PortInst topDrain = findPort(niTop, "diff-bottom");
				if (botSource != null && topDrain != null)
				{
					ArcProto arc = compatibleArc(botSource, topDrain);
					if (arc != null)
					{
						try { if (ArcInst.makeInstance(arc, ep, botSource, topDrain) != null) seriesWires++; }
						catch (Exception e) { /* skip */ }
					}
				}
			}
		}
		System.out.println("    Series internal wires: " + seriesWires);

		// ==================== WELL CONTACTS ====================
		List<PortInst> gndBusPorts = new ArrayList<PortInst>();
		List<PortInst> vddBusPorts = new ArrayList<PortInst>();

		// Well contacts placed OUTSIDE device area (right side) to avoid
		// M1 supply drop verticals overlapping with device contacts/routing.
		double wcMargin = rules.getMinWellContactSize() / 2 + m1Spacing;
		double wcStartX = snap(totalW + wcMargin);

		// P-Well contacts below NMOS → GND (only if NMOS devices exist)
		double minWcSize = rules.getMinWellContactSize();
		if (pwellCon != null && !nDevs.isEmpty())
		{
			double wcW = snap(Math.max(pwellCon.getDefWidth(ep), minWcSize));
			double wcH = snap(Math.max(pwellCon.getDefHeight(ep), minWcSize));
			NodeInst wc = NodeInst.makeInstance(pwellCon, ep,
				EPoint.fromLambda(wcStartX, pwellY), wcW, wcH, cell);
			if (wc != null)
			{
				PortInst p = firstPort(wc);
				if (p != null)
				{
					PortInst busPort = dropToBus(p, gndBusY, cell);
					if (busPort != null) gndBusPorts.add(busPort);
					else gndBusPorts.add(p);
				}
			}
		}

		// N-Well contacts above PMOS → VDD (only if PMOS devices exist)
		if (nwellCon != null && !pDevs.isEmpty())
		{
			double wcW = snap(Math.max(nwellCon.getDefWidth(ep), minWcSize));
			double wcH = snap(Math.max(nwellCon.getDefHeight(ep), minWcSize));
			NodeInst wc = NodeInst.makeInstance(nwellCon, ep,
				EPoint.fromLambda(wcStartX, nwellY), wcW, wcH, cell);
			if (wc != null)
			{
				PortInst p = firstPort(wc);
				if (p != null)
				{
					PortInst busPort = dropToBus(p, vddBusY, cell);
					if (busPort != null) vddBusPorts.add(busPort);
					else vddBusPorts.add(p);
				}
			}
		}

		// ==================== SUPPLY WIRING ====================
		// Connect transistor supply pins (source/drain on supply nets) to bus
		// Strategy: create contact OUTSIDE device body → drop to bus Y via vertical M1

		for (CircuitGraph.Net net : graph.getNets())
		{
			if (!net.isSupply()) continue;
			boolean isVdd = net.isPower();
			double busY = isVdd ? vddBusY : gndBusY;

			for (CircuitGraph.Pin pin : net.getPins())
			{
				CircuitGraph.Pin.Function func = pin.getFunction();
				if (func != CircuitGraph.Pin.Function.SOURCE &&
					func != CircuitGraph.Pin.Function.DRAIN) continue;

				NodeInst devNi = devNodes.get(pin.getDevice());
				if (devNi == null) continue;

				String portName = layoutPortName(pin);
				if (portName == null) continue;
				PortInst devPort = findPort(devNi, portName);
				if (devPort == null) continue;

				// Place contact OUTSIDE device bounding box (snap to grid)
				PortInst m1Port = createContactOutsideDevice(devPort, devNi, cell);
				if (m1Port == null) continue;

				// Drop vertical M1 to bus, then add to bus chain
				PortInst busPort = dropToBus(m1Port, busY, cell);
				if (busPort != null)
				{
					if (isVdd) vddBusPorts.add(busPort);
					else gndBusPorts.add(busPort);
				}
			}
		}

		// Chain bus ports horizontally with Metal-1
		int supplyWires = 0;
		supplyWires += chainHorizontal(gndBusPorts, cell, "GND");
		supplyWires += chainHorizontal(vddBusPorts, cell, "VDD");
		System.out.println("    Supply wires: " + supplyWires);

		// ==================== SIGNAL NET WIRING ====================
		// Use Sea-of-Gates router for robust DRC-clean routing
		int sigWires = routeSignalNetsSOG(devNodes, internalNets, cell);
		System.out.println("    Signal wires: " + sigWires);

		// ==================== N-WELL FILL ====================
		// Place N-Well fill rectangle covering PMOS area + contacts
		// to prevent thin N-Well protrusions at arc/contact junctions.
		if (nwellNode != null && !pDevs.isEmpty())
		{
			double[] nwFill = rules.computeNWellFill(pmosBaseY, pRowH, nwellY, totalW);
			if (nwFill != null)
			{
				NodeInst.makeInstance(nwellNode, ep,
					EPoint.fromLambda(nwFill[0], nwFill[1]), nwFill[2], nwFill[3], cell);
				System.out.println("    N-Well fill: center=(" + fmt(nwFill[0]) + "," + fmt(nwFill[1]) +
					") size=" + fmt(nwFill[2]) + "x" + fmt(nwFill[3]));
			}
		}

		// ==================== P-WELL FILL ====================
		// Place P-Well fill rectangle covering NMOS area + well contacts
		// to ensure P-Well contact connects to NMOS P-Well region.
		if (pwellNode != null && !nDevs.isEmpty())
		{
			double conOff = rules.getContactOffset();
			double nConWellExt = nActiveCon != null ? nActiveCon.getDefHeight(ep) / 2 : 8.5;
			double pwcWellExt = pwellCon != null ? pwellCon.getDefHeight(ep) / 2 : 9.5;

			double pwTop = nmosBaseY + nRowH / 2 + conOff + nConWellExt;
			double pwBottom = pwellY - pwcWellExt;
			double pwCx = snap(totalW / 2);
			double pwCy = snap((pwTop + pwBottom) / 2);
			double pwW = snap(totalW + 30);
			double pwH = snap(pwTop - pwBottom + 2);
			NodeInst.makeInstance(pwellNode, ep,
				EPoint.fromLambda(pwCx, pwCy), pwW, pwH, cell);
			System.out.println("    P-Well fill: center=(" + fmt(pwCx) + "," + fmt(pwCy) +
				") size=" + fmt(pwW) + "x" + fmt(pwH));
		}

		// ==================== EXPORTS ====================
		if (!gndBusPorts.isEmpty())
			tryExport(cell, gndBusPorts.get(0), "gnd", PortCharacteristic.GND);
		if (!vddBusPorts.isEmpty())
			tryExport(cell, vddBusPorts.get(0), "vdd", PortCharacteristic.PWR);

		// Signal port exports
		Map<CircuitGraph.Net, List<PortInst>> netPorts = buildNetPortMap(devNodes);
		for (CircuitGraph.Port port : graph.getPorts())
		{
			CircuitGraph.Net net = port.getNet();
			if (net == null || net.isSupply()) continue;
			List<PortInst> ports = netPorts.get(net);
			if (ports != null && !ports.isEmpty())
			{
				PortCharacteristic pc = PortCharacteristic.UNKNOWN;
				switch (port.getDirection())
				{
					case INPUT: pc = PortCharacteristic.IN; break;
					case OUTPUT: pc = PortCharacteristic.OUT; break;
					case BIDIR: pc = PortCharacteristic.BIDIR; break;
				}
				tryExport(cell, ports.get(0), port.getName(), pc);
			}
		}

		System.out.println("    Total: " + devNodes.size() + " devices");
		return cell;
	}

	// ==================== CONTACT PLACEMENT ====================

	/**
	 * Create a contact OUTSIDE the device bounding box, connected to the device port.
	 * Returns the Metal-1 port of the contact.
	 */
	private PortInst createContactOutsideDevice(PortInst devPort, NodeInst devNi, Cell cell)
	{
		if (metal1Arc != null && devPort.getPortProto().connectsTo(metal1Arc))
			return devPort; // already Metal-1 compatible

		String portName = devPort.getPortProto().getName();
		PrimitiveNode conNode = contactForPort(portName, devNi);
		if (conNode == null) return null;

		double conW = conNode.getDefWidth(ep);
		double conH = conNode.getDefHeight(ep);

		// Use primitive's nominal size (NOT NodeInst.getYSize which includes well/implant extents)
		double devCx = devNi.getAnchorCenterX();
		double devCy = devNi.getAnchorCenterY();
		PrimitiveNode devPrim = (PrimitiveNode) devNi.getProto();
		double defHalfW = devPrim.getDefWidth(ep) / 2;
		double defHalfH = devPrim.getDefHeight(ep) / 2;

		// Place contact center just outside the transistor nominal edge.
		// Contact layers (P-Well, N-Select, N-Active) naturally merge with transistor layers.
		double conOffset = rules.getContactOffset();
		double cx, cy;
		if (portName.equals("diff-top"))
		{
			cx = snap(devCx);
			cy = snap(devCy + defHalfH + conOffset);
		}
		else if (portName.equals("diff-bottom"))
		{
			cx = snap(devCx);
			cy = snap(devCy - defHalfH - conOffset);
		}
		else if (portName.equals("poly-left"))
		{
			cx = snap(devCx - defHalfW - conOffset);
			cy = snap(devCy);
		}
		else if (portName.equals("poly-right"))
		{
			cx = snap(devCx + defHalfW + conOffset);
			cy = snap(devCy);
		}
		else return null;

		NodeInst con = NodeInst.makeInstance(conNode, ep,
			EPoint.fromLambda(cx, cy), conW, conH, cell);
		if (con == null) return null;

		PortInst conPort = firstPort(con);
		if (conPort == null) return null;

		// Wire device port → contact on native layer
		ArcProto arc = compatibleArc(devPort, conPort);
		if (arc != null)
		{
			try { ArcInst.makeInstance(arc, ep, devPort, conPort); }
			catch (Exception e)
			{
				System.out.println("    WARN: wire " + portName + " → contact failed: " + e.getMessage());
			}
		}

		System.out.println("    Contact " + conNode.getName() + " at (" + fmt(cx) + "," + fmt(cy) +
			") size " + fmt(conW) + "x" + fmt(conH) +
			" for " + portName + " on " + devPrim.getName());

		return conPort;
	}

	/**
	 * Drop a Metal-1 port vertically to a bus Y line.
	 * Creates a Metal-1 pin on the bus and wires vertically.
	 */
	private PortInst dropToBus(PortInst m1Port, double busY, Cell cell)
	{
		if (metal1Arc == null || metal1Pin == null) return null;

		double x = snap(m1Port.getCenter().getLambdaX());
		double pinW = metal1Pin.getDefWidth(ep);
		double pinH = metal1Pin.getDefHeight(ep);

		NodeInst busPin = NodeInst.makeInstance(metal1Pin, ep,
			EPoint.fromLambda(x, busY), pinW, pinH, cell);
		if (busPin == null) return null;

		PortInst busPort = firstPort(busPin);
		if (busPort == null) return null;

		try { ArcInst.makeInstance(metal1Arc, ep, m1Port, busPort); }
		catch (Exception e) { return null; }

		return busPort;
	}

	/**
	 * Chain ports at the SAME Y into a horizontal Metal-1 bus.
	 * All ports are first projected to a Metal-1 pin at their X on the bus Y,
	 * then chained left-to-right.
	 */
	private int chainHorizontal(List<PortInst> ports, Cell cell, String name)
	{
		if (ports.size() < 2 || metal1Arc == null) return 0;

		// Sort by X
		Collections.sort(ports, new Comparator<PortInst>()
		{
			public int compare(PortInst a, PortInst b)
			{
				return Double.compare(a.getCenter().getLambdaX(), b.getCenter().getLambdaX());
			}
		});

		// All ports should be at (or near) the same Y. Chain horizontally.
		int wires = 0;
		for (int i = 1; i < ports.size(); i++)
		{
			PortInst p1 = ports.get(i - 1);
			PortInst p2 = ports.get(i);
			double y1 = p1.getCenter().getLambdaY();
			double y2 = p2.getCenter().getLambdaY();

			if (Math.abs(y1 - y2) < 0.5)
			{
				// Same Y → direct horizontal Metal-1
				try
				{
					if (ArcInst.makeInstance(metal1Arc, ep, p1, p2) != null) wires++;
				}
				catch (Exception e) { /* skip */ }
			}
			else
			{
				// Different Y → vertical M2 route (supply bus fallback)
				wires += m2VerticalRoute(p1, p2, cell);
			}
		}
		return wires;
	}

	// ==================== SIGNAL NET WIRING ====================

	/**
	 * Wire all signal nets with hierarchical routing strategy:
	 * <ol>
	 *   <li>Internal series nets: already connected by diffusion abutment, skip</li>
	 *   <li>Same-X port groups: connect with M2 vertical (NMOS↔PMOS aligned pairs)</li>
	 *   <li>Same-Y remaining ports: connect with M1 horizontal</li>
	 *   <li>Mixed remaining ports: M2 vertical to channel + M3 horizontal trunk,
	 *       each net on its own unique channel track Y</li>
	 * </ol>
	 */
	private int wireSignalNets(Map<CircuitGraph.Device, NodeInst> devNodes,
		Set<CircuitGraph.Net> internalNets, Cell cell)
	{
		int wires = 0;
		if (metal1Arc == null) return 0;

		// Pre-compute all net ports (once, to avoid duplicate contact creation)
		Map<CircuitGraph.Net, List<PortInst>> netPorts =
			new LinkedHashMap<CircuitGraph.Net, List<PortInst>>();
		for (CircuitGraph.Net net : graph.getNets())
		{
			if (net.isSupply()) continue;
			if (internalNets.contains(net))
			{
				System.out.println("      Skip internal series net: " + net.getName());
				continue;
			}

			List<PortInst> m1Ports = new ArrayList<PortInst>();
			for (CircuitGraph.Pin pin : net.getPins())
			{
				if (isInternalSeriesPin(pin, internalNets)) continue;

				NodeInst ni = devNodes.get(pin.getDevice());
				if (ni == null) continue;
				String pn = layoutPortName(pin);
				if (pn == null) continue;
				PortInst dp = findPort(ni, pn);
				if (dp == null) continue;
				PortInst m1 = createContactOutsideDevice(dp, ni, cell);
				if (m1 != null) m1Ports.add(m1);
			}
			if (m1Ports.size() >= 2) netPorts.put(net, m1Ports);
		}

		// Phase 1: Group-and-connect strategy for each net
		// Track M2 segments {x, yMin, yMax} with net ownership for conflict detection
		double m2ColMinDist = (m1m2Con != null ? m1m2Con.getDefWidth(ep) : 5) +
			rules.getLayerSpacing(rules.getMetal2Layer(), rules.getMetal2Layer());
		double m2YMargin = (metal2Arc != null ? metal2Arc.getDefaultLambdaBaseWidth(ep) : 3) +
			rules.getLayerSpacing(rules.getMetal2Layer(), rules.getMetal2Layer());
		List<double[]> m2Segments = new ArrayList<double[]>(); // {x, yMin, yMax}
		List<CircuitGraph.Net> m2SegNets = new ArrayList<CircuitGraph.Net>(); // parallel: net owner
		List<CircuitGraph.Net> channelNets = new ArrayList<CircuitGraph.Net>();
		Map<CircuitGraph.Net, List<PortInst>> remainingPorts =
			new LinkedHashMap<CircuitGraph.Net, List<PortInst>>();

		for (Map.Entry<CircuitGraph.Net, List<PortInst>> entry : netPorts.entrySet())
		{
			CircuitGraph.Net net = entry.getKey();
			List<PortInst> ports = entry.getValue();

			// Group ports by X coordinate
			Map<Long, List<PortInst>> byX = new LinkedHashMap<Long, List<PortInst>>();
			for (PortInst p : ports)
			{
				long xKey = Math.round(p.getCenter().getLambdaX() * 2);
				List<PortInst> group = byX.get(xKey);
				if (group == null) { group = new ArrayList<PortInst>(); byX.put(xKey, group); }
				group.add(p);
			}

			// Connect same-X groups with M2 vertical and track used segments
			List<PortInst> representatives = new ArrayList<PortInst>();
			for (Map.Entry<Long, List<PortInst>> xEntry : byX.entrySet())
			{
				List<PortInst> group = xEntry.getValue();
				if (group.size() > 1)
				{
					Collections.sort(group, new Comparator<PortInst>()
					{
						public int compare(PortInst a, PortInst b)
						{ return Double.compare(a.getCenter().getLambdaY(), b.getCenter().getLambdaY()); }
					});
					for (int i = 1; i < group.size(); i++)
						wires += m2VerticalRoute(group.get(i - 1), group.get(i), cell);
					// Track this M2 segment with net ownership
					double colX = snap(group.get(0).getCenter().getLambdaX());
					double yBot = snap(group.get(0).getCenter().getLambdaY());
					double yTop = snap(group.get(group.size() - 1).getCenter().getLambdaY());
					m2Segments.add(new double[] { colX, yBot, yTop });
					m2SegNets.add(net);
				}
				representatives.add(group.get(group.size() / 2));
			}

			if (representatives.size() <= 1) continue;

			if (allSameY(representatives))
			{
				sortPortsByX(representatives);
				for (int i = 1; i < representatives.size(); i++)
				{
					try { if (ArcInst.makeInstance(metal1Arc, ep,
						representatives.get(i - 1), representatives.get(i)) != null) wires++; }
					catch (Exception e) { /* skip */ }
				}
			}
			else
			{
				channelNets.add(net);
				remainingPorts.put(net, representatives);
			}
		}

		// Phase 2: Route remaining nets via M2 vertical + M3 horizontal
		// Left-Edge track assignment: nets with non-overlapping X ranges share tracks
		if (!channelNets.isEmpty())
		{
			double viaW23 = m2m3Con != null ? m2m3Con.getDefWidth(ep) : 5;
			double m2Spacing = rules.getLayerSpacing(rules.getMetal2Layer(), rules.getMetal2Layer());
			double trackPitch = snap(Math.max(viaW23 + m2Spacing, m2ColMinDist));
			double channelMid = (channelBotY + channelTopY) / 2;

			// Compute X-range for each net (min/max X of its representative ports)
			List<double[]> netXRanges = new ArrayList<double[]>(); // [minX, maxX]
			for (CircuitGraph.Net net : channelNets)
			{
				List<PortInst> reps = remainingPorts.get(net);
				double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
				if (reps != null)
				{
					for (PortInst p : reps)
					{
						double x = p.getCenter().getLambdaX();
						if (x < minX) minX = x;
						if (x > maxX) maxX = x;
					}
				}
				netXRanges.add(new double[] { minX - viaW23, maxX + viaW23 });
			}

			// Left-Edge track assignment: assign nets to tracks, reuse when X-ranges don't overlap
			List<List<Integer>> tracks = new ArrayList<List<Integer>>(); // tracks[trackIdx] = list of net indices
			List<Integer> netToTrack = new ArrayList<Integer>();
			for (int ni = 0; ni < channelNets.size(); ni++)
			{
				double[] range = netXRanges.get(ni);
				int assignedTrack = -1;

				// Try to find existing track where this net doesn't overlap
				for (int ti = 0; ti < tracks.size(); ti++)
				{
					boolean canShare = true;
					for (int existingNet : tracks.get(ti))
					{
						double[] existRange = netXRanges.get(existingNet);
						// Check X-range overlap (with margin)
						if (range[0] < existRange[1] && existRange[0] < range[1])
						{
							canShare = false;
							break;
						}
					}
					if (canShare)
					{
						assignedTrack = ti;
						break;
					}
				}

				if (assignedTrack < 0)
				{
					assignedTrack = tracks.size();
					tracks.add(new ArrayList<Integer>());
				}
				tracks.get(assignedTrack).add(ni);
				netToTrack.add(assignedTrack);
			}

			int numTracks = tracks.size();
			System.out.println("    Channel routing: " + channelNets.size() + " nets -> " +
				numTracks + " tracks (left-edge sharing)");

			// Route each net on its assigned track
			for (int ni = 0; ni < channelNets.size(); ni++)
			{
				CircuitGraph.Net net = channelNets.get(ni);
				List<PortInst> reps = remainingPorts.get(net);
				if (reps == null || reps.size() < 2) continue;

				int trackIdx = netToTrack.get(ni);
				double trackY = snap(channelMid + trackPitch * (trackIdx - (numTracks - 1) / 2.0));

				wires += m2m3RouteViaChannel(reps, trackY, m2Segments, m2SegNets, net,
					m2ColMinDist, m2YMargin, cell);
			}
		}

		return wires;
	}

	// ==================== SEA-OF-GATES SIGNAL ROUTING ====================

	/**
	 * Route signal nets using Electric's Sea-of-Gates router.
	 * Creates unrouted arcs for all signal net connections, then invokes
	 * the SOG engine to perform DRC-clean maze routing.
	 */
	private int routeSignalNetsSOG(Map<CircuitGraph.Device, NodeInst> devNodes,
		Set<CircuitGraph.Net> internalNets, Cell cell)
	{
		if (metal1Arc == null) return 0;

		// Build port map with M1 contacts: net -> list of Metal-1 PortInsts
		// The SOG router needs metal ports — create poly/active contacts first
		Map<CircuitGraph.Net, List<PortInst>> rawPorts = buildNetPortMap(devNodes, internalNets);
		Map<CircuitGraph.Net, List<PortInst>> netPorts = new LinkedHashMap<CircuitGraph.Net, List<PortInst>>();

		for (Map.Entry<CircuitGraph.Net, List<PortInst>> entry : rawPorts.entrySet())
		{
			List<PortInst> m1Ports = new ArrayList<PortInst>();
			for (PortInst dp : entry.getValue())
			{
				// Create M1 contact at each port (poly contact or active contact)
				NodeInst devNi = dp.getNodeInst();
				PortInst m1 = createContactOutsideDevice(dp, devNi, cell);
				if (m1 != null) m1Ports.add(m1);
			}
			if (m1Ports.size() >= 2) netPorts.put(entry.getKey(), m1Ports);
		}

		// Create unrouted arcs connecting each net's M1 contact ports
		ArcProto unroutedArc = com.sun.electric.technology.technologies.Generic.tech().unrouted_arc;
		List<ArcInst> arcsToRoute = new ArrayList<ArcInst>();
		int connections = 0;

		for (Map.Entry<CircuitGraph.Net, List<PortInst>> entry : netPorts.entrySet())
		{
			List<PortInst> ports = entry.getValue();
			if (ports.size() < 2) continue;

			// Find the most central port (closest to centroid) as hub
			double cx = 0, cy = 0;
			for (PortInst p : ports) { cx += p.getCenter().getLambdaX(); cy += p.getCenter().getLambdaY(); }
			cx /= ports.size(); cy /= ports.size();

			PortInst hub = ports.get(0);
			double bestDist = Double.MAX_VALUE;
			for (PortInst p : ports)
			{
				double d = Math.abs(p.getCenter().getLambdaX() - cx) + Math.abs(p.getCenter().getLambdaY() - cy);
				if (d < bestDist) { bestDist = d; hub = p; }
			}

			// Connect each non-hub port to the hub with an unrouted arc
			// SOG will find optimal paths — only need N-1 arcs minimum
			// Use pairs instead of star to avoid redundancy: connect sequential pairs
			List<PortInst> sorted = new ArrayList<PortInst>(ports);
			final PortInst hubFinal = hub;
			// Sort by distance from hub
			Collections.sort(sorted, new Comparator<PortInst>()
			{
				public int compare(PortInst a, PortInst b)
				{
					double da = Math.abs(a.getCenter().getLambdaX() - hubFinal.getCenter().getLambdaX()) +
						Math.abs(a.getCenter().getLambdaY() - hubFinal.getCenter().getLambdaY());
					double db = Math.abs(b.getCenter().getLambdaX() - hubFinal.getCenter().getLambdaX()) +
						Math.abs(b.getCenter().getLambdaY() - hubFinal.getCenter().getLambdaY());
					return Double.compare(da, db);
				}
			});

			// Chain: connect each port to the next nearest (sequential pairs)
			for (int i = 1; i < sorted.size(); i++)
			{
				try
				{
					ArcInst ai = ArcInst.makeInstance(unroutedArc, ep, sorted.get(i - 1), sorted.get(i));
					if (ai != null)
					{
						arcsToRoute.add(ai);
						connections++;
					}
				}
				catch (Exception e) { /* skip */ }
			}
		}

		System.out.println("    Created " + connections + " unrouted arcs for SOG routing");

		if (arcsToRoute.isEmpty()) return 0;

		// Invoke Sea-of-Gates router
		try
		{
			com.sun.electric.tool.routing.seaOfGates.SeaOfGatesEngine sogEngine =
				com.sun.electric.tool.routing.seaOfGates.SeaOfGatesEngineFactory.createSeaOfGatesEngine();

			// Set routing preferences
			com.sun.electric.tool.routing.SeaOfGates.SeaOfGatesOptions sogPrefs =
				new com.sun.electric.tool.routing.SeaOfGates.SeaOfGatesOptions();
			sogPrefs.useParallelRoutes = false;
			sogEngine.setPrefs(sogPrefs);

			// Configure cell parameters: force grid alignment on all metal arcs
			com.sun.electric.tool.routing.SeaOfGates.SeaOfGatesCellParameters sogParams =
				new com.sun.electric.tool.routing.SeaOfGates.SeaOfGatesCellParameters(cell);
			for (Iterator<ArcProto> it = tech.getArcs(); it.hasNext(); )
			{
				ArcProto ap = it.next();
				if (ap.getFunction().isMetal()) sogParams.setGridForced(ap, true);
			}

			// Use Electric's built-in handler for direct cell modification
			SeaOfGatesEngine.Handler handler =
				com.sun.electric.tool.routing.seaOfGates.SeaOfGatesHandlers.getDefault(
					cell, null,
					com.sun.electric.tool.routing.Routing.SoGContactsStrategy.SOGCONTACTSATTOPLEVEL,
					null, ep);

			sogEngine.routeIt(handler, cell, false, arcsToRoute, sogParams);

			// Clean up any remaining unrouted arcs (SOG replaces them with metal)
			ArcProto unroutedType = com.sun.electric.technology.technologies.Generic.tech().unrouted_arc;
			List<ArcInst> toKill = new ArrayList<ArcInst>();
			for (Iterator<ArcInst> it = cell.getArcs(); it.hasNext(); )
			{
				ArcInst ai = it.next();
				if (ai.getProto() == unroutedType) toKill.add(ai);
			}
			for (ArcInst ai : toKill)
			{
				if (ai.isLinked()) ai.kill();
			}
			if (!toKill.isEmpty())
				System.out.println("    Cleaned up " + toKill.size() + " residual unrouted arcs");

			System.out.println("    SOG routing complete");
		}
		catch (Exception e)
		{
			System.out.println("    SOG routing failed: " + e.getMessage());
			// Remove unrouted arcs before falling back
			for (ArcInst ai : arcsToRoute)
			{
				if (ai.isLinked()) ai.kill();
			}
			System.out.println("    Falling back to channel routing");
			return wireSignalNets(devNodes, internalNets, cell);
		}

		return connections;
	}

	/**
	 * Build port map: signal net -> list of layout PortInsts.
	 * Excludes supply nets and internal series chain nets.
	 */
	private Map<CircuitGraph.Net, List<PortInst>> buildNetPortMap(
		Map<CircuitGraph.Device, NodeInst> devNodes, Set<CircuitGraph.Net> internalNets)
	{
		Map<CircuitGraph.Net, List<PortInst>> netPorts =
			new LinkedHashMap<CircuitGraph.Net, List<PortInst>>();

		for (CircuitGraph.Net net : graph.getSignalNets())
		{
			if (net.isSupply()) continue;

			List<PortInst> m1Ports = new ArrayList<PortInst>();
			for (CircuitGraph.Pin pin : net.getPins())
			{
				if (isInternalSeriesPin(pin, internalNets)) continue;

				NodeInst ni = devNodes.get(pin.getDevice());
				if (ni == null) continue;
				String pn = layoutPortName(pin);
				if (pn == null) continue;
				PortInst dp = findPort(ni, pn);
				if (dp == null) continue;
				m1Ports.add(dp);
			}
			if (m1Ports.size() >= 2) netPorts.put(net, m1Ports);
		}
		return netPorts;
	}

	/**
	 * Apply schematic W/L to a layout transistor NodeInst.
	 * Sets the transistor's physical size to match schematic dimensions.
	 */
	private void applySchematicSize(NodeInst ni, CircuitGraph.Device dev)
	{
		double schW = dev.getWidth();
		double schL = dev.getLength();
		if (schW <= 0 && schL <= 0) return; // no schematic size specified

		// Get current layout dimensions
		TransistorSize curSize = ni.getTransistorSize(VarContext.globalContext);
		if (curSize == null) return;

		double layoutW = curSize.getDoubleWidth();
		double layoutL = curSize.getDoubleLength();

		// Use schematic values where specified, keep layout defaults otherwise
		double targetW = schW > 0 ? schW : layoutW;
		double targetL = schL > 0 ? schL : layoutL;

		if (targetW != layoutW || targetL != layoutL)
		{
			ni.setPrimitiveNodeSize(targetW, targetL, ep);
			System.out.println("    Size adjusted: " + dev.getName() +
				" W=" + fmt(targetW) + " L=" + fmt(targetL) +
				" (from schematic W=" + fmt(schW) + " L=" + fmt(schL) + ")");
		}
	}

	/**
	 * Check if a pin is an internal connection of a series chain.
	 */
	private boolean isInternalSeriesPin(CircuitGraph.Pin pin, Set<CircuitGraph.Net> internalNets)
	{
		CircuitGraph.Pin.Function func = pin.getFunction();
		if (func != CircuitGraph.Pin.Function.SOURCE && func != CircuitGraph.Pin.Function.DRAIN)
			return false;
		CircuitGraph.Net net = pin.getNet();
		return net != null && internalNets.contains(net);
	}

	/** Check if proposed M2 segment conflicts with any existing segment from OTHER nets.
	 * Two segments conflict if close in X AND overlapping in Y range. */
	private boolean isSegmentFree(double x, double yMin, double yMax,
		List<double[]> segments, List<CircuitGraph.Net> segNets, CircuitGraph.Net currentNet,
		double xMinDist, double yMargin)
	{
		for (int i = 0; i < segments.size(); i++)
		{
			if (segNets.get(i) == currentNet) continue; // skip same-net segments
			double[] seg = segments.get(i);
			if (Math.abs(x - seg[0]) < xMinDist)
			{
				// Close in X — check Y overlap with margin
				if (yMin - yMargin < seg[2] && seg[1] - yMargin < yMax)
					return false;
			}
		}
		return true;
	}

	/** Check if a Via2 X position is clear of existing Via2s on the same track. */
	private boolean isVia2Clear(double x, List<Double> via2Xs, double minDist)
	{
		for (int i = 0; i < via2Xs.size(); i++)
		{
			if (Math.abs(x - via2Xs.get(i).doubleValue()) < minDist)
				return false;
		}
		return true;
	}

	/**
	 * Route ports via M2 vertical + M3 horizontal through channel:
	 * 1. M1-M2 via at port X (offset only if Y-range overlap with OTHER net M2)
	 * 2. M2 vertical from port Y to trackY
	 * 3. M2-M3 via at (m2X, trackY)
	 * 4. M3 horizontal chain at trackY
	 */
	private int m2m3RouteViaChannel(List<PortInst> ports, double trackY,
		List<double[]> m2Segments, List<CircuitGraph.Net> m2SegNets, CircuitGraph.Net currentNet,
		double m2ColMinDist, double m2YMargin, Cell cell)
	{
		if (m1m2Con == null || metal2Arc == null || ports.size() < 2) return 0;

		boolean useM3 = (m2m3Con != null && metal3Arc != null);
		if (!useM3)
		{
			// Fallback: M2 only
			int w = 0;
			for (int i = 1; i < ports.size(); i++)
				w += m2VerticalRoute(ports.get(i - 1), ports.get(i), cell);
			return w;
		}

		int wires = 0;
		double viaW12 = m1m2Con.getDefWidth(ep);
		double viaH12 = m1m2Con.getDefHeight(ep);
		double viaW23 = m2m3Con.getDefWidth(ep);
		double viaH23 = m2m3Con.getDefHeight(ep);

		// Via2 cut spacing: cutSize + separation to avoid merged cut DRC errors
		Technology.NodeLayer cutLayer23 = m2m3Con.findMulticut();
		double via2MinDist = 5.0; // default: cutSize(2) + sep(3)
		if (cutLayer23 != null)
			via2MinDist = cutLayer23.getMulticutSizeX().getLambda() + cutLayer23.getMulticutSep1D().getLambda();
		List<Double> via2TrackXs = new ArrayList<Double>(); // Via2 X positions on this track

		List<PortInst> m3TrackPins = new ArrayList<PortInst>();

		for (PortInst port : ports)
		{
			double px = snap(port.getCenter().getLambdaX());
			double py = snap(port.getCenter().getLambdaY());
			double segYMin = Math.min(py, trackY);
			double segYMax = Math.max(py, trackY);

			// Step 1: Find clear M2 column with limited offset
			double m2X = px;
			double step = Math.max(m2ColMinDist, via2MinDist);
			if (!isSegmentFree(px, segYMin, segYMax, m2Segments, m2SegNets, currentNet,
				m2ColMinDist, m2YMargin) || !isVia2Clear(px, via2TrackXs, via2MinDist))
			{
				boolean found = false;
				for (int attempt = 1; attempt <= 5; attempt++)
				{
					// Try right first (typically more open space)
					double tryRight = snap(px + step * attempt);
					if (isSegmentFree(tryRight, segYMin, segYMax, m2Segments, m2SegNets, currentNet,
						m2ColMinDist, m2YMargin) && isVia2Clear(tryRight, via2TrackXs, via2MinDist))
					{ m2X = tryRight; found = true; break; }
					double tryLeft = snap(px - step * attempt);
					if (isSegmentFree(tryLeft, segYMin, segYMax, m2Segments, m2SegNets, currentNet,
						m2ColMinDist, m2YMargin) && isVia2Clear(tryLeft, via2TrackXs, via2MinDist))
					{ m2X = tryLeft; found = true; break; }
				}
			}
			m2Segments.add(new double[] { m2X, segYMin, segYMax });
			m2SegNets.add(currentNet);
			via2TrackXs.add(m2X);

			// Step 2: M1-M2 via at (m2X, py)
			System.out.println("    Phase2: port (" + fmt(px) + "," + fmt(py) + ") m2X=" + fmt(m2X) +
				" trackY=" + fmt(trackY) + " offset=" + fmt(m2X - px) + " net=" + currentNet.getName());
			NodeInst via12 = NodeInst.makeInstance(m1m2Con, ep,
				EPoint.fromLambda(m2X, py), viaW12, viaH12, cell);
			if (via12 == null) continue;
			PortInst vp12 = firstPort(via12);
			if (vp12 == null) continue;

			// M1 from port to M1-M2 via (horizontal if offset, zero-length if same X)
			try { ArcInst.makeInstance(metal1Arc, ep, port, vp12); }
			catch (Exception e) { /* skip */ }

			// Step 3: M2-M3 via at (m2X, trackY)
			NodeInst via23 = NodeInst.makeInstance(m2m3Con, ep,
				EPoint.fromLambda(m2X, trackY), viaW23, viaH23, cell);
			if (via23 == null) continue;
			PortInst vp23 = firstPort(via23);
			if (vp23 == null) continue;

			// M2 vertical from (m2X, py) to (m2X, trackY)
			try { if (ArcInst.makeInstance(metal2Arc, ep, vp12, vp23) != null) wires++; }
			catch (Exception e) { /* skip */ }

			m3TrackPins.add(vp23);
		}

		// Step 4: Chain M3 pins at trackY with M3 horizontal arcs
		Collections.sort(m3TrackPins, new Comparator<PortInst>()
		{
			public int compare(PortInst a, PortInst b)
			{ return Double.compare(a.getCenter().getLambdaX(), b.getCenter().getLambdaX()); }
		});

		for (int i = 1; i < m3TrackPins.size(); i++)
		{
			try { if (ArcInst.makeInstance(metal3Arc, ep,
				m3TrackPins.get(i - 1), m3TrackPins.get(i)) != null) wires++; }
			catch (Exception e) { /* skip */ }
		}

		return wires;
	}

	// ==================== ROUTING HELPERS ====================

	/**
	 * Route vertically using Metal-2 to avoid Metal-1 supply bus conflicts.
	 * Places via at each end: M1→via→M2(vertical)→via→M1
	 */
	private int m2VerticalRoute(PortInst p1, PortInst p2, Cell cell)
	{
		if (m1m2Con == null || metal2Arc == null) return 0;

		double x1 = snap(p1.getCenter().getLambdaX());
		double y1 = snap(p1.getCenter().getLambdaY());
		double x2 = snap(p2.getCenter().getLambdaX());
		double y2 = snap(p2.getCenter().getLambdaY());

		// Same position → no wire
		if (Math.abs(x1 - x2) < 0.5 && Math.abs(y1 - y2) < 0.5) return 0;

		double x = x1; // same X for vertical route

		double viaW = m1m2Con.getDefWidth(ep);
		double viaH = m1m2Con.getDefHeight(ep);

		// Via at p1 location
		NodeInst via1 = NodeInst.makeInstance(m1m2Con, ep,
			EPoint.fromLambda(x, y1), viaW, viaH, cell);
		if (via1 == null) return 0;

		// Via at p2 location
		NodeInst via2 = NodeInst.makeInstance(m1m2Con, ep,
			EPoint.fromLambda(x, y2), viaW, viaH, cell);
		if (via2 == null) return 0;

		PortInst vp1 = firstPort(via1);
		PortInst vp2 = firstPort(via2);
		if (vp1 == null || vp2 == null) return 0;

		int w = 0;
		// M1 from p1 to via1
		try { if (ArcInst.makeInstance(metal1Arc, ep, p1, vp1) != null) w++; }
		catch (Exception e) { /* skip */ }
		// M2 vertical from via1 to via2
		try { if (ArcInst.makeInstance(metal2Arc, ep, vp1, vp2) != null) w++; }
		catch (Exception e) { /* skip */ }
		// M1 from via2 to p2
		try { if (ArcInst.makeInstance(metal1Arc, ep, vp2, p2) != null) w++; }
		catch (Exception e) { /* skip */ }
		return w;
	}

	private boolean allSameX(List<PortInst> ports)
	{
		if (ports.size() < 2) return true;
		double x0 = snap(ports.get(0).getCenter().getLambdaX());
		for (int i = 1; i < ports.size(); i++)
			if (Math.abs(snap(ports.get(i).getCenter().getLambdaX()) - x0) > 0.5)
				return false;
		return true;
	}

	private boolean allSameY(List<PortInst> ports)
	{
		if (ports.size() < 2) return true;
		double y0 = snap(ports.get(0).getCenter().getLambdaY());
		for (int i = 1; i < ports.size(); i++)
			if (Math.abs(snap(ports.get(i).getCenter().getLambdaY()) - y0) > 0.5)
				return false;
		return true;
	}

	private void sortPortsByX(List<PortInst> ports)
	{
		Collections.sort(ports, new Comparator<PortInst>()
		{
			public int compare(PortInst a, PortInst b)
			{
				return Double.compare(a.getCenter().getLambdaX(), b.getCenter().getLambdaX());
			}
		});
	}

	// ==================== HELPERS ====================

	private Map<CircuitGraph.Net, List<PortInst>> buildNetPortMap(
		Map<CircuitGraph.Device, NodeInst> devNodes)
	{
		Map<CircuitGraph.Net, List<PortInst>> map = new LinkedHashMap<CircuitGraph.Net, List<PortInst>>();
		for (CircuitGraph.Net net : graph.getNets())
		{
			List<PortInst> ports = new ArrayList<PortInst>();
			for (CircuitGraph.Pin pin : net.getPins())
			{
				NodeInst ni = devNodes.get(pin.getDevice());
				if (ni == null) continue;
				String pn = layoutPortName(pin);
				if (pn != null)
				{
					PortInst pi = findPort(ni, pn);
					if (pi != null) ports.add(pi);
				}
			}
			map.put(net, ports);
		}
		return map;
	}

	private void sortByX(List<CircuitGraph.Device> devs)
	{
		Collections.sort(devs, new Comparator<CircuitGraph.Device>()
		{
			public int compare(CircuitGraph.Device a, CircuitGraph.Device b)
			{
				double[] pa = placement.get(a);
				double[] pb = placement.get(b);
				return Double.compare(pa != null ? pa[0] : 0, pb != null ? pb[0] : 0);
			}
		});
	}

	private PrimitiveNode primForDevice(CircuitGraph.Device dev)
	{
		switch (dev.getType())
		{
			case NMOS: return nTransistor;
			case PMOS: return pTransistor;
			default: return metal1Pin;
		}
	}

	private PrimitiveNode contactForPort(String portName, NodeInst devNi)
	{
		if (portName.startsWith("poly")) return polyCon;
		if (portName.startsWith("diff"))
		{
			return devNi.getProto().getName().contains("P-Transistor") ? pActiveCon : nActiveCon;
		}
		return null; // don't create contacts for well ports
	}

	private String layoutPortName(CircuitGraph.Pin pin)
	{
		if (!pin.getDevice().isMosfet()) return null;
		switch (pin.getFunction())
		{
			case GATE: return "poly-left";
			case SOURCE: return "diff-top";
			case DRAIN: return "diff-bottom";
			case BODY: return pin.getDevice().getType() == CircuitGraph.Device.Type.PMOS ?
				"p-trans-well" : "n-trans-well";
			default: return "poly-left";
		}
	}

	private PortInst findPort(NodeInst ni, String name)
	{
		for (Iterator<PortInst> it = ni.getPortInsts(); it.hasNext(); )
		{
			PortInst pi = it.next();
			if (pi.getPortProto().getName().equals(name)) return pi;
		}
		return null;
	}

	private ArcProto compatibleArc(PortInst pi1, PortInst pi2)
	{
		for (Iterator<ArcProto> it = tech.getArcs(); it.hasNext(); )
		{
			ArcProto a = it.next();
			if (pi1.getPortProto().connectsTo(a) && pi2.getPortProto().connectsTo(a))
				return a;
		}
		return null;
	}

	private PortInst firstPort(NodeInst ni)
	{
		if (ni == null) return null;
		Iterator<PortInst> it = ni.getPortInsts();
		return it.hasNext() ? it.next() : null;
	}

	private void tryExport(Cell cell, PortInst pi, String name, PortCharacteristic pc)
	{
		try { Export.newInstance(cell, pi, name, ep, pc); }
		catch (Exception e) { /* skip */ }
	}

	/** Snap to 0.5 lambda grid to avoid resolution errors */
	private static double snap(double v) { return Math.round(v * 2.0) / 2.0; }

	private static String fmt(double v) { return String.format("%.1f", v); }

	private PrimitiveNode findPrim(String name)
	{
		for (Iterator<PrimitiveNode> it = tech.getNodes(); it.hasNext(); )
		{
			PrimitiveNode n = it.next();
			if (n.getName().equals(name)) return n;
		}
		return null;
	}

	private ArcProto findArc(String name)
	{
		for (Iterator<ArcProto> it = tech.getArcs(); it.hasNext(); )
		{
			ArcProto a = it.next();
			if (a.getName().equals(name)) return a;
		}
		return null;
	}

	// ==================== ACCESSORS ====================

	public CircuitGraph getGraph() { return graph; }
	public ConstraintExtractor getConstraints() { return constraints; }
	public Map<CircuitGraph.Device, double[]> getPlacement() { return placement; }
	public List<AnalogRouter.RoutePath> getRoutes() { return routes; }
}
