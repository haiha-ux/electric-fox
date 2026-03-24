/* -*- tab-width: 4 -*-
 *
 * Electric(tm) VLSI Design System
 *
 * File: CircuitGraph.java
 * Analog Layout Synthesis Engine: circuit connectivity graph
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

import java.util.*;

/**
 * Circuit connectivity graph for analog layout synthesis.
 *
 * This is the central data structure for the Analog Layout Synthesis Engine (ALSE).
 * It represents a circuit as a bipartite graph of devices and nets, with rich
 * annotations for device parameters, net types, and layout constraints.
 *
 * Built from either:
 * - Electric schematic cells (via {@link CircuitGraphBuilder#fromCell})
 * - SPICE netlist files (via {@link CircuitGraphBuilder#fromSpice})
 *
 * Used by:
 * - {@link ConstraintExtractor} to derive symmetry/matching/proximity constraints
 * - AnalogPlacer for simulated annealing placement
 * - AnalogRouter for symmetry-aware routing
 */
public class CircuitGraph
{
	private String name;
	private final List<Device> devices = new ArrayList<Device>();
	private final List<Net> nets = new ArrayList<Net>();
	private final Map<String, Net> netsByName = new LinkedHashMap<String, Net>();
	private final Map<String, Device> devicesByName = new LinkedHashMap<String, Device>();
	private final List<Port> ports = new ArrayList<Port>(); // external ports
	private final List<SubCircuit> subCircuits = new ArrayList<SubCircuit>(); // hierarchical sub-cells

	// ==================== DEVICE (transistor, resistor, capacitor, etc.) ====================

	/**
	 * A circuit device: MOSFET, BJT, resistor, capacitor, diode, or subcircuit.
	 */
	public static class Device
	{
		public enum Type {
			NMOS, PMOS,
			NPN, PNP,
			RESISTOR, CAPACITOR, INDUCTOR,
			DIODE,
			VOLTAGE_SOURCE, CURRENT_SOURCE,
			SUBCIRCUIT
		}

		private final String name;
		private final Type type;
		private final List<Pin> pins = new ArrayList<Pin>();
		private final Map<String, String> params = new LinkedHashMap<String, String>();

		// MOSFET-specific
		private double width = -1;      // W in microns
		private double length = -1;     // L in microns
		private int fingers = 1;        // number of fingers (nf/m)
		private String modelName;       // SPICE model name

		// Layout geometry (filled during device generation)
		private double layoutWidth = -1;
		private double layoutHeight = -1;

		// Constraint annotations (filled by ConstraintExtractor)
		private int symmetryGroup = -1;     // devices in same group must be symmetric
		private int matchingGroup = -1;     // devices in same group must be matched
		private Device matchPartner;        // paired device for interdigitation

		public Device(String name, Type type)
		{
			this.name = name;
			this.type = type;
		}

		public String getName() { return name; }
		public Type getType() { return type; }
		public List<Pin> getPins() { return pins; }
		public Map<String, String> getParams() { return params; }

		public double getWidth() { return width; }
		public void setWidth(double w) { this.width = w; }
		public double getLength() { return length; }
		public void setLength(double l) { this.length = l; }
		public int getFingers() { return fingers; }
		public void setFingers(int f) { this.fingers = f; }
		public String getModelName() { return modelName; }
		public void setModelName(String m) { this.modelName = m; }

		public double getLayoutWidth() { return layoutWidth; }
		public void setLayoutWidth(double w) { this.layoutWidth = w; }
		public double getLayoutHeight() { return layoutHeight; }
		public void setLayoutHeight(double h) { this.layoutHeight = h; }

		public int getSymmetryGroup() { return symmetryGroup; }
		public void setSymmetryGroup(int g) { this.symmetryGroup = g; }
		public int getMatchingGroup() { return matchingGroup; }
		public void setMatchingGroup(int g) { this.matchingGroup = g; }
		public Device getMatchPartner() { return matchPartner; }
		public void setMatchPartner(Device p) { this.matchPartner = p; }

		public boolean isMosfet() { return type == Type.NMOS || type == Type.PMOS; }
		public boolean isBjt() { return type == Type.NPN || type == Type.PNP; }
		public boolean isPassive() {
			return type == Type.RESISTOR || type == Type.CAPACITOR || type == Type.INDUCTOR;
		}

		/**
		 * Find pin by function.
		 */
		public Pin findPin(Pin.Function func)
		{
			for (Pin p : pins)
				if (p.function == func) return p;
			return null;
		}

		/**
		 * Get the net connected to a specific pin function.
		 */
		public Net getNet(Pin.Function func)
		{
			Pin p = findPin(func);
			return p != null ? p.net : null;
		}

		@Override
		public String toString()
		{
			return type + " " + name + " (W=" + width + " L=" + length + " nf=" + fingers + ")";
		}
	}

	// ==================== PIN (device terminal) ====================

	/**
	 * A pin on a device, connecting it to a net.
	 */
	public static class Pin
	{
		public enum Function {
			// MOSFET
			GATE, DRAIN, SOURCE, BODY,
			// BJT
			BASE, COLLECTOR, EMITTER, SUBSTRATE,
			// Passive
			PLUS, MINUS,
			// Generic
			PORT_1, PORT_2, PORT_3, PORT_4
		}

		private final Device device;
		private final Function function;
		private Net net;

		public Pin(Device device, Function function)
		{
			this.device = device;
			this.function = function;
		}

		public Device getDevice() { return device; }
		public Function getFunction() { return function; }
		public Net getNet() { return net; }
		public void setNet(Net net) { this.net = net; }
	}

	// ==================== NET (electrical connection) ====================

	/**
	 * An electrical net connecting multiple device pins.
	 */
	public static class Net
	{
		public enum Type {
			SIGNAL,         // regular signal net
			POWER,          // VDD/VCC supply
			GROUND,         // GND/VSS supply
			CLOCK,          // clock signal
			BIAS,           // bias voltage/current
			DIFFERENTIAL_P, // positive differential signal
			DIFFERENTIAL_N  // negative differential signal
		}

		private final String name;
		private Type type = Type.SIGNAL;
		private final List<Pin> pins = new ArrayList<Pin>();

		// Constraint annotations
		private Net symmetricPair;          // paired net for symmetric routing
		private boolean isShielded;         // requires shielding
		private boolean isCritical;         // performance-critical (minimize parasitics)
		private double maxResistance = -1;  // maximum allowed routing resistance
		private double maxCapacitance = -1; // maximum allowed routing capacitance

		public Net(String name)
		{
			this.name = name;
		}

		public String getName() { return name; }
		public Type getType() { return type; }
		public void setType(Type t) { this.type = t; }
		public List<Pin> getPins() { return pins; }

		public Net getSymmetricPair() { return symmetricPair; }
		public void setSymmetricPair(Net p) { this.symmetricPair = p; }
		public boolean isShielded() { return isShielded; }
		public void setShielded(boolean s) { this.isShielded = s; }
		public boolean isCritical() { return isCritical; }
		public void setCritical(boolean c) { this.isCritical = c; }

		public boolean isPower() { return type == Type.POWER; }
		public boolean isGround() { return type == Type.GROUND; }
		public boolean isSupply() { return isPower() || isGround(); }

		/**
		 * Get all devices connected to this net.
		 */
		public List<Device> getDevices()
		{
			List<Device> result = new ArrayList<Device>();
			Set<Device> seen = new HashSet<Device>();
			for (Pin p : pins)
			{
				if (seen.add(p.device))
					result.add(p.device);
			}
			return result;
		}

		/**
		 * Number of connected devices (fanout).
		 */
		public int fanout()
		{
			Set<Device> seen = new HashSet<Device>();
			for (Pin p : pins) seen.add(p.device);
			return seen.size();
		}

		@Override
		public String toString()
		{
			return name + " (" + type + ", fanout=" + fanout() + ")";
		}
	}

	// ==================== PORT (external connection) ====================

	/**
	 * An external port of the circuit (maps to a SPICE subcircuit port).
	 */
	public static class Port
	{
		public enum Direction { INPUT, OUTPUT, BIDIR }

		private final String name;
		private Direction direction = Direction.BIDIR;
		private Net net;

		public Port(String name)
		{
			this.name = name;
		}

		public String getName() { return name; }
		public Direction getDirection() { return direction; }
		public void setDirection(Direction d) { this.direction = d; }
		public Net getNet() { return net; }
		public void setNet(Net n) { this.net = n; }
	}

	// ==================== SUBCIRCUIT (hierarchical child) ====================

	/**
	 * A hierarchical sub-circuit instance with its own CircuitGraph.
	 * Represents a cell instance in the schematic that has been recursively
	 * extracted. The sub-circuit has ports that map to nets in the parent graph.
	 */
	public static class SubCircuit
	{
		private final String instanceName;      // instance name in parent
		private final String cellName;           // cell/definition name
		private final CircuitGraph childGraph;   // recursively extracted child
		private final Map<String, Net> portNets; // port name -> parent net mapping
		private double layoutWidth = -1;
		private double layoutHeight = -1;
		private double placedX, placedY;         // placement position

		public SubCircuit(String instanceName, String cellName, CircuitGraph childGraph)
		{
			this.instanceName = instanceName;
			this.cellName = cellName;
			this.childGraph = childGraph;
			this.portNets = new LinkedHashMap<String, Net>();
		}

		public String getInstanceName() { return instanceName; }
		public String getCellName() { return cellName; }
		public CircuitGraph getChildGraph() { return childGraph; }
		public Map<String, Net> getPortNets() { return portNets; }
		public void mapPort(String portName, Net parentNet) { portNets.put(portName, parentNet); }

		public double getLayoutWidth() { return layoutWidth; }
		public void setLayoutWidth(double w) { this.layoutWidth = w; }
		public double getLayoutHeight() { return layoutHeight; }
		public void setLayoutHeight(double h) { this.layoutHeight = h; }
		public double getPlacedX() { return placedX; }
		public void setPlacedX(double x) { this.placedX = x; }
		public double getPlacedY() { return placedY; }
		public void setPlacedY(double y) { this.placedY = y; }

		@Override
		public String toString()
		{
			return "SubCircuit " + instanceName + " (" + cellName + ", " +
				childGraph.getDevices().size() + " devices, " +
				childGraph.getSubCircuits().size() + " sub-cells)";
		}
	}

	// ==================== GRAPH CONSTRUCTION ====================

	public CircuitGraph(String name)
	{
		this.name = name;
	}

	public String getName() { return name; }
	public List<Device> getDevices() { return devices; }
	public List<Net> getNets() { return nets; }
	public List<Port> getPorts() { return ports; }
	public List<SubCircuit> getSubCircuits() { return subCircuits; }
	public boolean isHierarchical() { return !subCircuits.isEmpty(); }

	/**
	 * Add a sub-circuit instance to the graph.
	 */
	public SubCircuit addSubCircuit(String instanceName, String cellName, CircuitGraph childGraph)
	{
		SubCircuit sc = new SubCircuit(instanceName, cellName, childGraph);
		subCircuits.add(sc);
		return sc;
	}

	/**
	 * Get total device count including all sub-circuits recursively.
	 */
	public int getTotalDeviceCount()
	{
		int count = devices.size();
		for (SubCircuit sc : subCircuits)
			count += sc.childGraph.getTotalDeviceCount();
		return count;
	}

	/**
	 * Flatten all sub-circuits into this graph by merging their devices and nets.
	 * Sub-circuit ports are connected to parent nets via shared net merging.
	 * After flattening, all devices are at the top level for global optimization.
	 */
	public void flatten()
	{
		if (subCircuits.isEmpty()) return;

		for (SubCircuit sc : new ArrayList<>(subCircuits))
		{
			CircuitGraph child = sc.getChildGraph();

			// Recursively flatten children first
			child.flatten();

			String prefix = sc.getInstanceName() + "_";

			// Map child nets to parent nets (via port connections)
			Map<Net, Net> childToParent = new HashMap<>();
			for (Map.Entry<String, Net> portEntry : sc.getPortNets().entrySet())
			{
				String portName = portEntry.getKey();
				Net parentNet = portEntry.getValue();

				// Find the child net connected to this port
				for (Port p : child.getPorts())
				{
					if (p.getName().equalsIgnoreCase(portName) && p.getNet() != null)
					{
						childToParent.put(p.getNet(), parentNet);
						break;
					}
				}
			}

			// Also map child supply nets to parent supply nets by name
			for (Net childNet : child.getNets())
			{
				if (childToParent.containsKey(childNet)) continue;
				if (childNet.isPower())
				{
					Net parentVdd = findSupplyNet(Net.Type.POWER);
					if (parentVdd != null) childToParent.put(childNet, parentVdd);
				}
				else if (childNet.isGround())
				{
					Net parentGnd = findSupplyNet(Net.Type.GROUND);
					if (parentGnd != null) childToParent.put(childNet, parentGnd);
				}
			}

			// Merge child devices into parent
			for (Device childDev : child.getDevices())
			{
				String newName = prefix + childDev.getName();
				Device parentDev = addDevice(newName, childDev.getType());
				parentDev.setWidth(childDev.getWidth());
				parentDev.setLength(childDev.getLength());
				parentDev.setFingers(childDev.getFingers());
				parentDev.setModelName(childDev.getModelName());

				// Re-connect pins to parent nets
				for (Pin childPin : childDev.getPins())
				{
					Net childNet = childPin.getNet();
					Net parentNet = childToParent.get(childNet);
					if (parentNet == null)
					{
						// Internal net — create in parent with prefixed name
						String intNetName = prefix + childNet.getName();
						parentNet = getOrCreateNet(intNetName);
						parentNet.setType(childNet.getType());
						childToParent.put(childNet, parentNet);
					}
					connect(parentDev, childPin.getFunction(), parentNet);
				}
			}
		}

		// Clear sub-circuits after flattening
		subCircuits.clear();
		System.out.println("  Flattened: " + devices.size() + " devices, " + nets.size() + " nets");
	}

	/** Find first supply net of given type */
	private Net findSupplyNet(Net.Type type)
	{
		for (Net n : nets)
			if (n.getType() == type) return n;
		return null;
	}

	/**
	 * Get or create a net by name.
	 */
	public Net getOrCreateNet(String name)
	{
		Net net = netsByName.get(name.toLowerCase());
		if (net == null)
		{
			net = new Net(name);
			nets.add(net);
			netsByName.put(name.toLowerCase(), net);

			// Auto-detect power/ground nets
			String lower = name.toLowerCase();
			if (lower.equals("vdd") || lower.equals("vcc") || lower.equals("avdd") ||
				lower.equals("dvdd") || lower.startsWith("vdd"))
			{
				net.setType(Net.Type.POWER);
			}
			else if (lower.equals("gnd") || lower.equals("vss") || lower.equals("agnd") ||
				lower.equals("dgnd") || lower.equals("0") || lower.startsWith("gnd") ||
				lower.startsWith("vss"))
			{
				net.setType(Net.Type.GROUND);
			}
		}
		return net;
	}

	/**
	 * Look up a net by name.
	 */
	public Net findNet(String name)
	{
		return netsByName.get(name.toLowerCase());
	}

	/**
	 * Add a device to the graph.
	 */
	public Device addDevice(String name, Device.Type type)
	{
		Device dev = new Device(name, type);
		devices.add(dev);
		devicesByName.put(name, dev);
		return dev;
	}

	/**
	 * Look up a device by name.
	 */
	public Device findDevice(String name)
	{
		return devicesByName.get(name);
	}

	/**
	 * Connect a device pin to a net.
	 */
	public Pin connect(Device device, Pin.Function func, Net net)
	{
		Pin pin = new Pin(device, func);
		pin.setNet(net);
		device.getPins().add(pin);
		net.getPins().add(pin);
		return pin;
	}

	/**
	 * Add an external port.
	 */
	public Port addPort(String name, Net net, Port.Direction dir)
	{
		Port port = new Port(name);
		port.setDirection(dir);
		port.setNet(net);
		ports.add(port);
		return port;
	}

	// ==================== QUERIES ====================

	/**
	 * Get all NMOS transistors.
	 */
	public List<Device> getNmos()
	{
		List<Device> result = new ArrayList<Device>();
		for (Device d : devices)
			if (d.type == Device.Type.NMOS) result.add(d);
		return result;
	}

	/**
	 * Get all PMOS transistors.
	 */
	public List<Device> getPmos()
	{
		List<Device> result = new ArrayList<Device>();
		for (Device d : devices)
			if (d.type == Device.Type.PMOS) result.add(d);
		return result;
	}

	/**
	 * Get all MOSFETs.
	 */
	public List<Device> getMosfets()
	{
		List<Device> result = new ArrayList<Device>();
		for (Device d : devices)
			if (d.isMosfet()) result.add(d);
		return result;
	}

	/**
	 * Get all signal nets (exclude power/ground).
	 */
	public List<Net> getSignalNets()
	{
		List<Net> result = new ArrayList<Net>();
		for (Net n : nets)
			if (!n.isSupply()) result.add(n);
		return result;
	}

	/**
	 * Find devices sharing a common gate net (potential differential pair or current mirror).
	 */
	public Map<Net, List<Device>> getDevicesByGateNet()
	{
		Map<Net, List<Device>> result = new LinkedHashMap<Net, List<Device>>();
		for (Device d : devices)
		{
			if (!d.isMosfet()) continue;
			Net gateNet = d.getNet(Pin.Function.GATE);
			if (gateNet == null || gateNet.isSupply()) continue;
			List<Device> list = result.get(gateNet);
			if (list == null)
			{
				list = new ArrayList<Device>();
				result.put(gateNet, list);
			}
			list.add(d);
		}
		return result;
	}

	/**
	 * Find devices sharing a common source net (potential differential pair).
	 */
	public Map<Net, List<Device>> getDevicesBySourceNet()
	{
		Map<Net, List<Device>> result = new LinkedHashMap<Net, List<Device>>();
		for (Device d : devices)
		{
			if (!d.isMosfet()) continue;
			Net sourceNet = d.getNet(Pin.Function.SOURCE);
			if (sourceNet == null || sourceNet.isSupply()) continue;
			List<Device> list = result.get(sourceNet);
			if (list == null)
			{
				list = new ArrayList<Device>();
				result.put(sourceNet, list);
			}
			list.add(d);
		}
		return result;
	}

	/**
	 * Compute Half-Perimeter Wire Length (HPWL) estimate for a net.
	 * Used as placement cost metric. Returns -1 if devices have no layout positions.
	 */
	public double computeHPWL(Net net, Map<Device, double[]> positions)
	{
		double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
		double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
		boolean hasPos = false;
		for (Pin pin : net.getPins())
		{
			double[] pos = positions.get(pin.device);
			if (pos == null) continue;
			hasPos = true;
			if (pos[0] < minX) minX = pos[0];
			if (pos[0] > maxX) maxX = pos[0];
			if (pos[1] < minY) minY = pos[1];
			if (pos[1] > maxY) maxY = pos[1];
		}
		if (!hasPos) return -1;
		return (maxX - minX) + (maxY - minY);
	}

	/**
	 * Compute total HPWL across all signal nets.
	 */
	public double computeTotalHPWL(Map<Device, double[]> positions)
	{
		double total = 0;
		for (Net net : nets)
		{
			if (net.isSupply()) continue;
			double hpwl = computeHPWL(net, positions);
			if (hpwl >= 0) total += hpwl;
		}
		return total;
	}

	// ==================== STATISTICS ====================

	/**
	 * Print circuit statistics.
	 */
	public void printStats()
	{
		int nmos = 0, pmos = 0, res = 0, cap = 0, other = 0;
		for (Device d : devices)
		{
			switch (d.type)
			{
				case NMOS: nmos++; break;
				case PMOS: pmos++; break;
				case RESISTOR: res++; break;
				case CAPACITOR: cap++; break;
				default: other++; break;
			}
		}
		System.out.println("  ALSE Circuit: " + name);
		System.out.println("    Devices: " + devices.size() +
			" (NMOS=" + nmos + " PMOS=" + pmos + " R=" + res + " C=" + cap + " other=" + other + ")");
		System.out.println("    Nets: " + nets.size() + " (signal=" + getSignalNets().size() + ")");
		System.out.println("    Ports: " + ports.size());
		if (!subCircuits.isEmpty())
		{
			System.out.println("    SubCircuits: " + subCircuits.size() +
				" (total devices incl. hierarchy: " + getTotalDeviceCount() + ")");
			for (SubCircuit sc : subCircuits)
				System.out.println("      " + sc);
		}
	}
}
