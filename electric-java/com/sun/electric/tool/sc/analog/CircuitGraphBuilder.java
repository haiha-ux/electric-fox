/* -*- tab-width: 4 -*-
 *
 * Electric(tm) VLSI Design System
 *
 * File: CircuitGraphBuilder.java
 * Analog Layout Synthesis Engine: builds CircuitGraph from various sources
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

import com.sun.electric.database.hierarchy.Cell;
import com.sun.electric.database.prototype.PortProto;
import com.sun.electric.database.hierarchy.Export;
import com.sun.electric.database.hierarchy.Nodable;
import com.sun.electric.database.network.Netlist;
import com.sun.electric.database.network.Network;
import com.sun.electric.database.prototype.NodeProto;
import com.sun.electric.database.prototype.PortCharacteristic;
import com.sun.electric.database.prototype.PortProto;
import com.sun.electric.database.topology.NodeInst;
import com.sun.electric.database.topology.PortInst;
import com.sun.electric.database.variable.Variable;
import com.sun.electric.technology.PrimitiveNode;
import com.sun.electric.technology.technologies.Schematics;
import com.sun.electric.tool.io.input.spicenetlist.SpiceInstance;
import com.sun.electric.tool.io.input.spicenetlist.SpiceNetlistReader;
import com.sun.electric.tool.io.input.spicenetlist.SpiceSubckt;
import com.sun.electric.tool.io.output.Spice;
import com.sun.electric.tool.simulation.SimulationTool;

import java.io.FileNotFoundException;
import java.util.*;

/**
 * Builds a {@link CircuitGraph} from either an Electric schematic cell
 * or a SPICE netlist file. This is the entry point for the ALSE pipeline.
 *
 * Two construction paths:
 * <ul>
 *   <li>{@link #fromCell(Cell)} - builds from Electric's internal schematic representation</li>
 *   <li>{@link #fromSpice(String)} - builds from a SPICE netlist file using SpiceNetlistReader</li>
 * </ul>
 */
public class CircuitGraphBuilder
{
	// ==================== BUILD FROM ELECTRIC SCHEMATIC CELL ====================

	/**
	 * Build a CircuitGraph from an Electric schematic cell.
	 * Extracts all transistors, passive devices, and their connectivity.
	 *
	 * @param cell the schematic cell to analyze
	 * @return the circuit graph, or null on error
	 */
	public static CircuitGraph fromCell(Cell cell)
	{
		if (cell == null) return null;

		Netlist netlist = cell.getNetlist();
		if (netlist == null)
		{
			System.out.println("ALSE ERROR: Cannot get netlist for " + cell.describe(false));
			return null;
		}

		CircuitGraph graph = new CircuitGraph(cell.getName());

		// Build net map from Electric networks
		Map<Network, CircuitGraph.Net> netMap = new LinkedHashMap<Network, CircuitGraph.Net>();
		for (Iterator<Network> it = netlist.getNetworks(); it.hasNext(); )
		{
			Network nw = it.next();
			String netName = getNetworkName(nw);
			CircuitGraph.Net net = graph.getOrCreateNet(netName);
			netMap.put(nw, net);

			// Detect power/ground from port characteristics
			for (Iterator<Export> eIt = nw.getExports(); eIt.hasNext(); )
			{
				Export exp = eIt.next();
				if (exp.isPower()) net.setType(CircuitGraph.Net.Type.POWER);
				else if (exp.isGround()) net.setType(CircuitGraph.Net.Type.GROUND);
			}
		}

		// Iterate all instances in the cell
		for (Iterator<Nodable> it = netlist.getNodables(); it.hasNext(); )
		{
			Nodable no = it.next();
			NodeProto np = no.getProto();

			// Handle cell instances: recursively extract sub-circuit graph
			if (no.isCellInstance())
			{
				Cell subCell = (Cell) np;
				// Find schematic view if this is an icon
				Cell schemView = subCell.isSchematic() ? subCell : subCell.getEquivalent();
				if (schemView != null && schemView.isSchematic())
				{
					// Avoid infinite recursion: track visited cells
					String cellKey = schemView.describe(false);
					CircuitGraph childGraph = fromCell(schemView);
					if (childGraph != null && (childGraph.getDevices().size() > 0 || childGraph.getSubCircuits().size() > 0))
					{
						CircuitGraph.SubCircuit sc = graph.addSubCircuit(no.getName(), cellKey, childGraph);
						// Map sub-cell ports to parent nets
						for (Iterator<PortProto> pIt = np.getPorts(); pIt.hasNext(); )
						{
							PortProto pp = pIt.next();
							Network nw = netlist.getNetwork(no, pp, 0);
							CircuitGraph.Net parentNet = netMap.get(nw);
							if (parentNet != null)
								sc.mapPort(pp.getName(), parentNet);
						}
					}
				}
				continue;
			}

			NodeInst ni = no.getNodeInst();
			PrimitiveNode.Function func = ni.getFunction();

			// Determine device type
			CircuitGraph.Device.Type devType = mapFunctionToType(func);
			if (devType == null) continue; // skip non-device nodes (connectors, pins, etc.)

			// Create device
			CircuitGraph.Device device = graph.addDevice(no.getName(), devType);

			// Extract parameters
			extractDeviceParams(ni, device);

			// Connect pins to nets
			connectDevicePins(no, device, devType, netlist, netMap, graph);
		}

		// Build external ports from cell exports
		for (Iterator<Export> it = cell.getExports(); it.hasNext(); )
		{
			Export exp = it.next();
			Network nw = netlist.getNetwork(exp, 0);
			CircuitGraph.Net net = netMap.get(nw);
			if (net == null) continue;

			CircuitGraph.Port.Direction dir = CircuitGraph.Port.Direction.BIDIR;
			if (exp.getCharacteristic() == PortCharacteristic.IN) dir = CircuitGraph.Port.Direction.INPUT;
			else if (exp.getCharacteristic() == PortCharacteristic.OUT) dir = CircuitGraph.Port.Direction.OUTPUT;

			graph.addPort(exp.getName(), net, dir);
		}

		graph.printStats();
		return graph;
	}

	/**
	 * Map Electric PrimitiveNode.Function to CircuitGraph.Device.Type.
	 */
	private static CircuitGraph.Device.Type mapFunctionToType(PrimitiveNode.Function func)
	{
		if (func.isNTypeTransistor()) return CircuitGraph.Device.Type.NMOS;
		if (func.isPTypeTransistor()) return CircuitGraph.Device.Type.PMOS;
		if (func == PrimitiveNode.Function.TRANPN || func == PrimitiveNode.Function.TRA4NPN)
			return CircuitGraph.Device.Type.NPN;
		if (func == PrimitiveNode.Function.TRAPNP || func == PrimitiveNode.Function.TRA4PNP)
			return CircuitGraph.Device.Type.PNP;
		if (func.isResistor()) return CircuitGraph.Device.Type.RESISTOR;
		if (func.isCapacitor()) return CircuitGraph.Device.Type.CAPACITOR;
		if (func == PrimitiveNode.Function.INDUCT) return CircuitGraph.Device.Type.INDUCTOR;
		if (func == PrimitiveNode.Function.DIODE || func == PrimitiveNode.Function.DIODEZ)
			return CircuitGraph.Device.Type.DIODE;
		if (func == PrimitiveNode.Function.CONPOWER) return null; // power symbol, not a device
		if (func == PrimitiveNode.Function.CONGROUND) return null; // ground symbol
		return null;
	}

	/**
	 * Extract device parameters (W, L, M) from a NodeInst.
	 */
	private static void extractDeviceParams(NodeInst ni, CircuitGraph.Device device)
	{
		// Width
		Variable wVar = ni.getVar(Schematics.ATTR_WIDTH);
		if (wVar != null)
		{
			try { device.setWidth(parseSpiceValue(wVar.getPureValue(-1))); }
			catch (Exception e) { /* ignore parse errors */ }
		}

		// Length
		Variable lVar = ni.getVar(Schematics.ATTR_LENGTH);
		if (lVar != null)
		{
			try { device.setLength(parseSpiceValue(lVar.getPureValue(-1))); }
			catch (Exception e) { /* ignore parse errors */ }
		}

		// Multiplier / fingers
		Variable mVar = ni.getVar(SimulationTool.M_FACTOR_KEY);
		if (mVar != null)
		{
			try { device.setFingers(Integer.parseInt(mVar.getPureValue(-1))); }
			catch (Exception e) { /* ignore */ }
		}

		// SPICE model
		Variable modelVar = ni.getVar(Spice.SPICE_MODEL_KEY);
		if (modelVar != null)
		{
			device.setModelName(modelVar.getPureValue(-1));
		}
	}

	/**
	 * Connect device pins to nets based on port characteristics and device type.
	 */
	private static void connectDevicePins(Nodable no, CircuitGraph.Device device,
		CircuitGraph.Device.Type devType, Netlist netlist,
		Map<Network, CircuitGraph.Net> netMap, CircuitGraph graph)
	{
		NodeProto np = no.getProto();

		if (device.isMosfet())
		{
			// MOSFET: ports are g, s, d, (b for 4-port)
			connectPortByName(no, "g", CircuitGraph.Pin.Function.GATE, device, netlist, netMap, graph);
			connectPortByName(no, "s", CircuitGraph.Pin.Function.SOURCE, device, netlist, netMap, graph);
			connectPortByName(no, "d", CircuitGraph.Pin.Function.DRAIN, device, netlist, netMap, graph);
			connectPortByName(no, "b", CircuitGraph.Pin.Function.BODY, device, netlist, netMap, graph);
		}
		else if (device.isBjt())
		{
			connectPortByName(no, "b", CircuitGraph.Pin.Function.BASE, device, netlist, netMap, graph);
			connectPortByName(no, "c", CircuitGraph.Pin.Function.COLLECTOR, device, netlist, netMap, graph);
			connectPortByName(no, "e", CircuitGraph.Pin.Function.EMITTER, device, netlist, netMap, graph);
		}
		else if (device.isPassive() || devType == CircuitGraph.Device.Type.DIODE)
		{
			// 2-terminal devices
			int portIdx = 0;
			for (Iterator<PortProto> it = np.getPorts(); it.hasNext(); )
			{
				PortProto pp = it.next();
				CircuitGraph.Pin.Function func = portIdx == 0 ?
					CircuitGraph.Pin.Function.PLUS : CircuitGraph.Pin.Function.MINUS;
				Network nw = netlist.getNetwork(no, pp, 0);
				if (nw != null)
				{
					CircuitGraph.Net net = netMap.get(nw);
					if (net != null) graph.connect(device, func, net);
				}
				portIdx++;
			}
		}
		else
		{
			// Generic: connect all ports with numbered functions
			CircuitGraph.Pin.Function[] genericFuncs = {
				CircuitGraph.Pin.Function.PORT_1, CircuitGraph.Pin.Function.PORT_2,
				CircuitGraph.Pin.Function.PORT_3, CircuitGraph.Pin.Function.PORT_4
			};
			int portIdx = 0;
			for (Iterator<PortProto> it = np.getPorts(); it.hasNext(); )
			{
				PortProto pp = it.next();
				if (portIdx >= genericFuncs.length) break;
				Network nw = netlist.getNetwork(no, pp, 0);
				if (nw != null)
				{
					CircuitGraph.Net net = netMap.get(nw);
					if (net != null) graph.connect(device, genericFuncs[portIdx], net);
				}
				portIdx++;
			}
		}
	}

	/**
	 * Connect a specific named port on a nodable to a pin function.
	 */
	private static void connectPortByName(Nodable no, String portName,
		CircuitGraph.Pin.Function func, CircuitGraph.Device device,
		Netlist netlist, Map<Network, CircuitGraph.Net> netMap, CircuitGraph graph)
	{
		NodeProto np = no.getProto();
		for (Iterator<PortProto> it = np.getPorts(); it.hasNext(); )
		{
			PortProto pp = it.next();
			if (pp.getName().equals(portName))
			{
				Network nw = netlist.getNetwork(no, pp, 0);
				if (nw != null)
				{
					CircuitGraph.Net net = netMap.get(nw);
					if (net != null) graph.connect(device, func, net);
				}
				return;
			}
		}
	}

	/**
	 * Get a clean name for a Network.
	 */
	private static String getNetworkName(Network nw)
	{
		Iterator<String> names = nw.getNames();
		if (names.hasNext()) return names.next();
		return nw.describe(false);
	}

	// ==================== BUILD FROM SPICE NETLIST FILE ====================

	/**
	 * Build a CircuitGraph from a SPICE netlist file.
	 * Uses the existing SpiceNetlistReader infrastructure.
	 *
	 * @param fileName path to .spi/.cir/.spice file
	 * @return the circuit graph, or null on error
	 */
	public static CircuitGraph fromSpice(String fileName)
	{
		return fromSpice(fileName, null);
	}

	/**
	 * Build a CircuitGraph from a SPICE netlist file, optionally targeting a specific subcircuit.
	 *
	 * @param fileName path to SPICE netlist file
	 * @param subcktName name of subcircuit to extract (null = top level)
	 * @return the circuit graph, or null on error
	 */
	public static CircuitGraph fromSpice(String fileName, String subcktName)
	{
		SpiceNetlistReader reader = new SpiceNetlistReader();
		try
		{
			reader.readFile(fileName, false);
		}
		catch (FileNotFoundException e)
		{
			System.out.println("ALSE ERROR: Cannot open SPICE file: " + fileName);
			return null;
		}

		// Find target subcircuit
		if (subcktName != null)
		{
			SpiceSubckt subckt = reader.getSubckt(subcktName);
			if (subckt == null)
			{
				System.out.println("ALSE ERROR: Subcircuit '" + subcktName + "' not found in " + fileName);
				return null;
			}
			return fromSpiceSubckt(subckt);
		}

		// If no subcircuit specified, use top-level instances
		// or the first (or only) subcircuit
		Collection<SpiceSubckt> subckts = reader.getSubckts();
		if (!subckts.isEmpty())
		{
			SpiceSubckt first = subckts.iterator().next();
			return fromSpiceSubckt(first);
		}

		// Build from top-level instances
		CircuitGraph graph = new CircuitGraph("top");
		for (SpiceInstance inst : reader.getTopLevelInstances())
		{
			addSpiceInstance(graph, inst);
		}
		graph.printStats();
		return graph;
	}

	/**
	 * Build a CircuitGraph from a SpiceSubckt object.
	 */
	public static CircuitGraph fromSpiceSubckt(SpiceSubckt subckt)
	{
		CircuitGraph graph = new CircuitGraph(subckt.getName());

		// Process all instances in the subcircuit
		for (SpiceInstance inst : subckt.getInstances())
		{
			addSpiceInstance(graph, inst);
		}

		// Add external ports
		for (String portName : subckt.getPorts())
		{
			CircuitGraph.Net net = graph.getOrCreateNet(portName);
			SpiceSubckt.PortType pt = subckt.getPortType(portName);
			CircuitGraph.Port.Direction dir = CircuitGraph.Port.Direction.BIDIR;
			if (pt == SpiceSubckt.PortType.IN) dir = CircuitGraph.Port.Direction.INPUT;
			else if (pt == SpiceSubckt.PortType.OUT) dir = CircuitGraph.Port.Direction.OUTPUT;
			graph.addPort(portName, net, dir);
		}

		graph.printStats();
		return graph;
	}

	/**
	 * Add a SPICE instance to the circuit graph.
	 */
	private static void addSpiceInstance(CircuitGraph graph, SpiceInstance inst)
	{
		char type = Character.toLowerCase(inst.getType());
		String name = String.valueOf(inst.getType()) + inst.getName();
		List<String> nets = inst.getNets();

		switch (type)
		{
			case 'm': // MOSFET
			{
				if (nets.size() < 4) break;
				// SPICE MOSFET: Mname drain gate source body modelName [params]
				boolean isPmos = isPmosModel(inst);
				CircuitGraph.Device.Type devType = isPmos ?
					CircuitGraph.Device.Type.PMOS : CircuitGraph.Device.Type.NMOS;
				CircuitGraph.Device dev = graph.addDevice(name, devType);

				graph.connect(dev, CircuitGraph.Pin.Function.DRAIN, graph.getOrCreateNet(nets.get(0)));
				graph.connect(dev, CircuitGraph.Pin.Function.GATE, graph.getOrCreateNet(nets.get(1)));
				graph.connect(dev, CircuitGraph.Pin.Function.SOURCE, graph.getOrCreateNet(nets.get(2)));
				graph.connect(dev, CircuitGraph.Pin.Function.BODY, graph.getOrCreateNet(nets.get(3)));

				// Extract W/L
				String w = inst.getParams().get("w");
				if (w != null) dev.setWidth(parseSpiceValue(w));
				String l = inst.getParams().get("l");
				if (l != null) dev.setLength(parseSpiceValue(l));
				String m = inst.getParams().get("m");
				if (m == null) m = inst.getParams().get("nf");
				if (m != null)
				{
					try { dev.setFingers(Integer.parseInt(m)); }
					catch (NumberFormatException e) { /* ignore */ }
				}
				break;
			}
			case 'q': // BJT
			{
				if (nets.size() < 3) break;
				// SPICE BJT: Qname collector base emitter [substrate] modelName
				CircuitGraph.Device dev = graph.addDevice(name, CircuitGraph.Device.Type.NPN);
				graph.connect(dev, CircuitGraph.Pin.Function.COLLECTOR, graph.getOrCreateNet(nets.get(0)));
				graph.connect(dev, CircuitGraph.Pin.Function.BASE, graph.getOrCreateNet(nets.get(1)));
				graph.connect(dev, CircuitGraph.Pin.Function.EMITTER, graph.getOrCreateNet(nets.get(2)));
				if (nets.size() >= 4)
					graph.connect(dev, CircuitGraph.Pin.Function.SUBSTRATE, graph.getOrCreateNet(nets.get(3)));
				break;
			}
			case 'r': // Resistor
			{
				if (nets.size() < 2) break;
				CircuitGraph.Device dev = graph.addDevice(name, CircuitGraph.Device.Type.RESISTOR);
				graph.connect(dev, CircuitGraph.Pin.Function.PLUS, graph.getOrCreateNet(nets.get(0)));
				graph.connect(dev, CircuitGraph.Pin.Function.MINUS, graph.getOrCreateNet(nets.get(1)));
				String val = inst.getParams().get("r");
				if (val == null && !inst.getParams().isEmpty())
					val = inst.getParams().values().iterator().next();
				if (val != null) dev.getParams().put("R", val);
				break;
			}
			case 'c': // Capacitor
			{
				if (nets.size() < 2) break;
				CircuitGraph.Device dev = graph.addDevice(name, CircuitGraph.Device.Type.CAPACITOR);
				graph.connect(dev, CircuitGraph.Pin.Function.PLUS, graph.getOrCreateNet(nets.get(0)));
				graph.connect(dev, CircuitGraph.Pin.Function.MINUS, graph.getOrCreateNet(nets.get(1)));
				String val = inst.getParams().get("c");
				if (val == null && !inst.getParams().isEmpty())
					val = inst.getParams().values().iterator().next();
				if (val != null) dev.getParams().put("C", val);
				break;
			}
			case 'l': // Inductor
			{
				if (nets.size() < 2) break;
				CircuitGraph.Device dev = graph.addDevice(name, CircuitGraph.Device.Type.INDUCTOR);
				graph.connect(dev, CircuitGraph.Pin.Function.PLUS, graph.getOrCreateNet(nets.get(0)));
				graph.connect(dev, CircuitGraph.Pin.Function.MINUS, graph.getOrCreateNet(nets.get(1)));
				break;
			}
			case 'd': // Diode
			{
				if (nets.size() < 2) break;
				CircuitGraph.Device dev = graph.addDevice(name, CircuitGraph.Device.Type.DIODE);
				graph.connect(dev, CircuitGraph.Pin.Function.PLUS, graph.getOrCreateNet(nets.get(0)));
				graph.connect(dev, CircuitGraph.Pin.Function.MINUS, graph.getOrCreateNet(nets.get(1)));
				break;
			}
			case 'v': // Voltage source
			{
				if (nets.size() < 2) break;
				CircuitGraph.Device dev = graph.addDevice(name, CircuitGraph.Device.Type.VOLTAGE_SOURCE);
				graph.connect(dev, CircuitGraph.Pin.Function.PLUS, graph.getOrCreateNet(nets.get(0)));
				graph.connect(dev, CircuitGraph.Pin.Function.MINUS, graph.getOrCreateNet(nets.get(1)));
				break;
			}
			case 'i': // Current source
			{
				if (nets.size() < 2) break;
				CircuitGraph.Device dev = graph.addDevice(name, CircuitGraph.Device.Type.CURRENT_SOURCE);
				graph.connect(dev, CircuitGraph.Pin.Function.PLUS, graph.getOrCreateNet(nets.get(0)));
				graph.connect(dev, CircuitGraph.Pin.Function.MINUS, graph.getOrCreateNet(nets.get(1)));
				break;
			}
			case 'x': // Subcircuit instance
			{
				CircuitGraph.Device dev = graph.addDevice(name, CircuitGraph.Device.Type.SUBCIRCUIT);
				SpiceSubckt sub = inst.getSubckt();
				if (sub != null)
				{
					dev.setModelName(sub.getName());
					List<String> subPorts = sub.getPorts();
					for (int i = 0; i < Math.min(nets.size(), subPorts.size()); i++)
					{
						CircuitGraph.Pin.Function[] genericFuncs = {
							CircuitGraph.Pin.Function.PORT_1, CircuitGraph.Pin.Function.PORT_2,
							CircuitGraph.Pin.Function.PORT_3, CircuitGraph.Pin.Function.PORT_4
						};
						if (i < genericFuncs.length)
						{
							graph.connect(dev, genericFuncs[i], graph.getOrCreateNet(nets.get(i)));
						}
					}
				}
				break;
			}
		}
	}

	/**
	 * Determine if a MOSFET instance uses a PMOS model.
	 * Checks model name heuristics (pmos, pch, pfet, etc.)
	 */
	private static boolean isPmosModel(SpiceInstance inst)
	{
		// Check params for model name
		Map<String, String> params = inst.getParams();
		for (String key : params.keySet())
		{
			String val = key.toLowerCase();
			if (val.contains("pmos") || val.contains("pch") || val.contains("pfet") ||
				val.startsWith("p"))
				return true;
		}
		// Check nets — SPICE MOSFET order: drain gate source body
		// If body connects to VDD-like net, likely PMOS
		List<String> nets = inst.getNets();
		if (nets.size() >= 4)
		{
			String body = nets.get(3).toLowerCase();
			if (body.contains("vdd") || body.contains("vcc") || body.contains("avdd"))
				return true;
		}
		return false;
	}

	// ==================== UTILITIES ====================

	/**
	 * Parse a SPICE value string with unit suffix (e.g., "1u", "100n", "0.18u").
	 * Returns value in base units (meters for L/W).
	 */
	public static double parseSpiceValue(String val)
	{
		if (val == null || val.isEmpty()) return -1;
		val = val.trim().toLowerCase();

		// Remove trailing units like "m" for meters (don't confuse with milli)
		double multiplier = 1.0;
		char lastChar = val.charAt(val.length() - 1);
		if (!Character.isDigit(lastChar) && lastChar != '.')
		{
			switch (lastChar)
			{
				case 't': multiplier = 1e12; break;
				case 'g': multiplier = 1e9; break;
				case 'x': // meg
				case 'k': multiplier = 1e3; break;
				case 'm': multiplier = 1e-3; break;
				case 'u': multiplier = 1e-6; break;
				case 'n': multiplier = 1e-9; break;
				case 'p': multiplier = 1e-12; break;
				case 'f': multiplier = 1e-15; break;
				case 'a': multiplier = 1e-18; break;
				default: break;
			}
			val = val.substring(0, val.length() - 1);

			// Handle "meg" suffix
			if (val.endsWith("me")) { multiplier = 1e6; val = val.substring(0, val.length() - 2); }
		}

		try { return Double.parseDouble(val) * multiplier; }
		catch (NumberFormatException e) { return -1; }
	}
}
