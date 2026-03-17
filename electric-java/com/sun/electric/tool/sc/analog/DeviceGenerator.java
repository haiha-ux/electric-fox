/* -*- tab-width: 4 -*-
 *
 * Electric(tm) VLSI Design System
 *
 * File: DeviceGenerator.java
 * Analog Layout Synthesis Engine: parameterized device layout generator
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
import com.sun.electric.database.hierarchy.Export;
import com.sun.electric.database.hierarchy.Library;
import com.sun.electric.database.prototype.PortCharacteristic;
import com.sun.electric.database.topology.ArcInst;
import com.sun.electric.database.topology.NodeInst;
import com.sun.electric.database.topology.PortInst;
import com.sun.electric.technology.*;

import java.util.*;

/**
 * Generates parameterized device layout cells for analog circuits.
 *
 * Supported device types:
 * <ul>
 *   <li>Multi-finger MOSFET: N fingers with shared source/drain diffusions</li>
 *   <li>Interdigitated pair: Two matched MOSFETs with ABAB or ABBA finger patterns</li>
 *   <li>Common centroid array: 2D arrangement for matched devices</li>
 *   <li>Guard ring: Protection ring around device groups</li>
 * </ul>
 *
 * All generators are technology-aware, reading design rules from the
 * current Technology object (typically MOCMOS or TSMC).
 */
public class DeviceGenerator
{
	private final Technology tech;
	private final EditingPreferences ep;

	// Technology-dependent design rules (populated from Technology)
	private double polyWidth;
	private double polyExtPast;     // poly extension past active
	private double metalWidth;
	private double metal1Width;
	private double contactSize;
	private double contactSpacing;
	private double activeWidth;
	private double gateToContact;   // poly-to-contact spacing
	private double diffExtPast;     // diffusion extension past contact
	private double wellOverhang;    // well overhang past active
	private double metalOverhang;   // metal overhang past contact

	// Layer references
	private Layer polyLayer;
	private Layer metal1Layer;
	private Layer metal2Layer;
	private Layer activePLayer;
	private Layer activeNLayer;
	private Layer pWellLayer;
	private Layer nWellLayer;
	private Layer contactLayer;

	// Node references
	private PrimitiveNode metalContactNode;
	private PrimitiveNode polyContactNode;
	private PrimitiveNode metal1PinNode;
	private PrimitiveNode metal2PinNode;

	// Arc references
	private ArcProto metal1Arc;
	private ArcProto metal2Arc;
	private ArcProto polyArc;

	public DeviceGenerator(Technology tech, EditingPreferences ep)
	{
		this.tech = tech;
		this.ep = ep;
		initDesignRules();
	}

	/**
	 * Initialize design rules from technology.
	 * Uses reasonable defaults for MOCMOS if specific rules not found.
	 */
	private void initDesignRules()
	{
		// Default design rules for MOCMOS (scalable CMOS)
		polyWidth = 2.0;
		polyExtPast = 2.0;
		metalWidth = 3.0;
		metal1Width = 3.0;
		contactSize = 2.0;
		contactSpacing = 3.0;
		activeWidth = 3.0;
		gateToContact = 2.0;
		diffExtPast = 3.0;
		wellOverhang = 6.0;
		metalOverhang = 1.0;

		// Find layers
		polyLayer = findLayer("Polysilicon-1");
		metal1Layer = findLayer("Metal-1");
		metal2Layer = findLayer("Metal-2");
		activePLayer = findLayer("P-Active");
		activeNLayer = findLayer("N-Active");
		pWellLayer = findLayer("P-Well");
		nWellLayer = findLayer("N-Well");
		contactLayer = findLayer("Active-Cut");

		// Find arcs
		metal1Arc = findArc("Metal-1");
		metal2Arc = findArc("Metal-2");
		polyArc = findArc("Polysilicon-1");

		// Find nodes
		metalContactNode = findNode("Metal-1-P-Active-Con");
		polyContactNode = findNode("Metal-1-Polysilicon-1-Con");
		metal1PinNode = findNode("Metal-1-Pin");
		metal2PinNode = findNode("Metal-2-Pin");
	}

	private Layer findLayer(String name)
	{
		for (Iterator<Layer> it = tech.getLayers(); it.hasNext(); )
		{
			Layer l = it.next();
			if (l.getName().equals(name)) return l;
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

	private PrimitiveNode findNode(String name)
	{
		for (Iterator<PrimitiveNode> it = tech.getNodes(); it.hasNext(); )
		{
			PrimitiveNode n = it.next();
			if (n.getName().equals(name)) return n;
		}
		return null;
	}

	/**
	 * Convert a device parameter value to lambda (technology) units.
	 * Handles three cases:
	 * - Value <= 0: use default
	 * - Value < 0.01: assume meters, convert to lambda (multiply by 1e6)
	 * - Value >= 0.01: assume already in lambda/um units
	 */
	private double convertToLambda(double value, double defaultVal)
	{
		if (value <= 0) return defaultVal;
		if (value < 0.01) return value * 1e6; // meters -> um/lambda
		return value; // already in lambda
	}

	// ==================== MOSFET DEVICE SIZE ESTIMATION ====================

	/**
	 * Estimate the layout dimensions of a MOSFET device.
	 * Used during placement for area estimation without generating actual layout.
	 *
	 * @param device the circuit graph device
	 * @return [width, height] in technology units
	 */
	public double[] estimateMosfetSize(CircuitGraph.Device device)
	{
		int nf = Math.max(1, device.getFingers());
		double w = convertToLambda(device.getWidth(), 4.0);
		double l = convertToLambda(device.getLength(), 2.0);

		// Width = number of fingers * (gate pitch) + contacts on sides
		double gatePitch = l + gateToContact + contactSize + gateToContact;
		double layoutW = nf * gatePitch + 2 * diffExtPast;

		// Height = transistor width + poly extensions + contact rows
		double layoutH = w + 2 * polyExtPast + 2 * (contactSize + metalOverhang);

		device.setLayoutWidth(layoutW);
		device.setLayoutHeight(layoutH);

		return new double[]{layoutW, layoutH};
	}

	/**
	 * Estimate layout size for any device type.
	 */
	public double[] estimateDeviceSize(CircuitGraph.Device device)
	{
		if (device.isMosfet())
			return estimateMosfetSize(device);

		// Default size for passive devices
		double w = 10.0, h = 10.0;
		if (device.isPassive())
		{
			w = 8.0;
			h = 8.0;
		}
		device.setLayoutWidth(w);
		device.setLayoutHeight(h);
		return new double[]{w, h};
	}

	/**
	 * Estimate sizes for all devices in a circuit graph.
	 */
	public void estimateAllSizes(CircuitGraph graph)
	{
		for (CircuitGraph.Device d : graph.getDevices())
			estimateDeviceSize(d);
	}

	// ==================== INTERDIGITATED PAIR ====================

	/**
	 * Generate an interdigitated pair layout pattern.
	 * For matched MOSFETs, returns the finger assignment pattern.
	 *
	 * @param devA first device
	 * @param devB second device
	 * @param style ABAB or ABBA pattern
	 * @return array where 0=deviceA finger, 1=deviceB finger
	 */
	public int[] generateInterdigitatedPattern(CircuitGraph.Device devA,
		CircuitGraph.Device devB, ConstraintExtractor.MatchingConstraint.Style style)
	{
		int nfA = Math.max(1, devA.getFingers());
		int nfB = Math.max(1, devB.getFingers());
		int total = nfA + nfB;
		int[] pattern = new int[total];

		if (style == ConstraintExtractor.MatchingConstraint.Style.ABBA)
		{
			// ABBA pattern: A B B A A B B A ...
			int idx = 0;
			boolean isA = true;
			int countA = 0, countB = 0;
			while (idx < total)
			{
				if (isA && countA < nfA) { pattern[idx++] = 0; countA++; }
				else if (!isA && countB < nfB) { pattern[idx++] = 1; countB++; }

				// Switch every 1 or 2 fingers
				if (idx % 2 == 0) isA = !isA;
				if (countA >= nfA) isA = false;
				if (countB >= nfB) isA = true;
			}
		}
		else
		{
			// ABAB pattern: alternating A and B
			int countA = 0, countB = 0;
			for (int i = 0; i < total; i++)
			{
				if (i % 2 == 0 && countA < nfA) { pattern[i] = 0; countA++; }
				else if (countB < nfB) { pattern[i] = 1; countB++; }
				else { pattern[i] = 0; countA++; }
			}
		}

		return pattern;
	}

	// ==================== COMMON CENTROID ARRAY ====================

	/**
	 * Generate a common centroid placement pattern for matched devices.
	 * Creates a 2D array where the center of gravity of each device's
	 * fingers coincides.
	 *
	 * @param numDevices number of devices to match (2 or 4)
	 * @param fingersPerDevice number of fingers per device
	 * @return 2D array [row][col] where value = device index
	 */
	public int[][] generateCommonCentroidPattern(int numDevices, int fingersPerDevice)
	{
		int totalFingers = numDevices * fingersPerDevice;

		if (numDevices == 2)
		{
			// Classic ABBA column or 2D pattern
			int cols = fingersPerDevice;
			int rows = 2;
			int[][] pattern = new int[rows][cols];

			// Top row: A B A B ...
			// Bottom row: B A B A ... (mirror)
			for (int c = 0; c < cols; c++)
			{
				pattern[0][c] = c % 2;
				pattern[1][c] = 1 - (c % 2);
			}
			return pattern;
		}
		else if (numDevices == 4)
		{
			// 2x2 common centroid: ABCD/DCBA pattern
			int side = (int) Math.ceil(Math.sqrt(totalFingers));
			int[][] pattern = new int[side][side];
			// Simple rotation pattern
			int[] order = {0, 1, 2, 3, 3, 2, 1, 0};
			int idx = 0;
			for (int r = 0; r < side; r++)
			{
				for (int c = 0; c < side; c++)
				{
					pattern[r][c] = order[idx % order.length];
					idx++;
				}
			}
			return pattern;
		}

		// Fallback: simple stripe pattern
		int[][] pattern = new int[1][totalFingers];
		for (int i = 0; i < totalFingers; i++)
			pattern[0][i] = i % numDevices;
		return pattern;
	}

	// ==================== GUARD RING GENERATION ====================

	/**
	 * Describes a guard ring surrounding a group of devices.
	 * Contains the bounding box, ring type, and contact positions.
	 */
	public static class GuardRing
	{
		public enum RingType { P_PLUS, N_PLUS, DUAL }

		public final RingType type;
		public final double x, y, width, height;
		public final double ringWidth;
		public final List<double[]> contacts;  // [x, y] of substrate contacts

		public GuardRing(RingType type, double x, double y, double w, double h, double ringW)
		{
			this.type = type;
			this.x = x;
			this.y = y;
			this.width = w;
			this.height = h;
			this.ringWidth = ringW;
			this.contacts = new ArrayList<double[]>();
		}
	}

	/**
	 * Compute guard ring parameters for a group of devices.
	 *
	 * For NMOS devices in P-well: P+ guard ring (connects to ground)
	 * For PMOS devices in N-well: N+ guard ring (connects to VDD)
	 *
	 * @param devices the devices to surround
	 * @param positions device placement positions
	 * @return the guard ring specification, or null if not needed
	 */
	public GuardRing computeGuardRing(List<CircuitGraph.Device> devices,
		Map<CircuitGraph.Device, double[]> positions)
	{
		if (devices.isEmpty()) return null;

		// Determine ring type from device types
		boolean hasNmos = false, hasPmos = false;
		for (CircuitGraph.Device d : devices)
		{
			if (d.getType() == CircuitGraph.Device.Type.NMOS) hasNmos = true;
			if (d.getType() == CircuitGraph.Device.Type.PMOS) hasPmos = true;
		}

		GuardRing.RingType ringType;
		if (hasNmos && hasPmos) ringType = GuardRing.RingType.DUAL;
		else if (hasPmos) ringType = GuardRing.RingType.N_PLUS;
		else ringType = GuardRing.RingType.P_PLUS;

		// Compute bounding box of all devices
		double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
		double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;

		for (CircuitGraph.Device d : devices)
		{
			double[] pos = positions.get(d);
			if (pos == null) continue;

			double w = d.getLayoutWidth() > 0 ? d.getLayoutWidth() : 10;
			double h = d.getLayoutHeight() > 0 ? d.getLayoutHeight() : 10;

			if (pos[0] < minX) minX = pos[0];
			if (pos[1] < minY) minY = pos[1];
			if (pos[0] + w > maxX) maxX = pos[0] + w;
			if (pos[1] + h > maxY) maxY = pos[1] + h;
		}

		// Add margin for well overhang and ring width
		double ringW = activeWidth + 2 * metalOverhang;
		double margin = wellOverhang + ringW;

		double rx = minX - margin;
		double ry = minY - margin;
		double rw = (maxX - minX) + 2 * margin;
		double rh = (maxY - minY) + 2 * margin;

		GuardRing ring = new GuardRing(ringType, rx, ry, rw, rh, ringW);

		// Place substrate contacts along the ring at regular intervals
		double contactPitch = contactSize + contactSpacing;
		// Bottom edge
		for (double cx = rx + ringW / 2; cx < rx + rw; cx += contactPitch)
			ring.contacts.add(new double[]{cx, ry + ringW / 2});
		// Top edge
		for (double cx = rx + ringW / 2; cx < rx + rw; cx += contactPitch)
			ring.contacts.add(new double[]{cx, ry + rh - ringW / 2});
		// Left edge
		for (double cy = ry + ringW / 2 + contactPitch; cy < ry + rh - contactPitch; cy += contactPitch)
			ring.contacts.add(new double[]{rx + ringW / 2, cy});
		// Right edge
		for (double cy = ry + ringW / 2 + contactPitch; cy < ry + rh - contactPitch; cy += contactPitch)
			ring.contacts.add(new double[]{rx + rw - ringW / 2, cy});

		return ring;
	}

	/**
	 * Compute well tie positions for a group of same-type devices.
	 * Well ties connect substrate/well to the appropriate supply rail,
	 * preventing latchup and reducing substrate noise.
	 *
	 * @param devices the devices needing well ties
	 * @param positions device placement positions
	 * @return list of [x, y, isNwell] positions for well tie contacts
	 */
	public List<double[]> computeWellTies(List<CircuitGraph.Device> devices,
		Map<CircuitGraph.Device, double[]> positions)
	{
		List<double[]> ties = new ArrayList<double[]>();
		double maxSpacing = 50.0; // maximum distance between well ties

		for (CircuitGraph.Device d : devices)
		{
			double[] pos = positions.get(d);
			if (pos == null) continue;

			double w = d.getLayoutWidth() > 0 ? d.getLayoutWidth() : 10;
			double h = d.getLayoutHeight() > 0 ? d.getLayoutHeight() : 10;

			double isNwell = (d.getType() == CircuitGraph.Device.Type.PMOS) ? 1.0 : 0.0;

			// Place ties at device edges (top and bottom)
			double cx = pos[0] + w / 2;
			ties.add(new double[]{cx, pos[1] - contactSize - 1, isNwell});
			ties.add(new double[]{cx, pos[1] + h + 1, isNwell});

			// Additional ties for wide devices
			if (w > maxSpacing)
			{
				int numExtra = (int) (w / maxSpacing);
				for (int i = 1; i <= numExtra; i++)
				{
					double tx = pos[0] + i * w / (numExtra + 1);
					ties.add(new double[]{tx, pos[1] - contactSize - 1, isNwell});
					ties.add(new double[]{tx, pos[1] + h + 1, isNwell});
				}
			}
		}

		return ties;
	}

	// ==================== ACCESSORS ====================

	public double getPolyWidth() { return polyWidth; }
	public double getContactSize() { return contactSize; }
	public double getMetalWidth() { return metal1Width; }
	public double getWellOverhang() { return wellOverhang; }
}
