/* -*- tab-width: 4 -*-
 *
 * Electric(tm) VLSI Design System
 *
 * File: ConstraintExtractor.java
 * Analog Layout Synthesis Engine: extract layout constraints from circuit topology
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
 * Extracts layout constraints from circuit topology (graph analysis).
 *
 * Detects:
 * <ul>
 *   <li>Symmetry groups: differential pairs, matched loads</li>
 *   <li>Matching constraints: current mirrors, matched resistors</li>
 *   <li>Proximity constraints: bias networks, feedback paths</li>
 *   <li>Net types: differential pairs, clock, bias, supply</li>
 * </ul>
 *
 * Based on techniques from MAGICAL (UT Austin) and ALIGN (UMN):
 * - Seed-based symmetry detection (connected sources = virtual ground)
 * - Graph isomorphism for structural matching
 * - Topological analysis for constraint inference
 */
public class ConstraintExtractor
{
	private final CircuitGraph graph;
	private final List<SymmetryConstraint> symmetryConstraints = new ArrayList<SymmetryConstraint>();
	private final List<MatchingConstraint> matchingConstraints = new ArrayList<MatchingConstraint>();
	private final List<ProximityConstraint> proximityConstraints = new ArrayList<ProximityConstraint>();
	private int nextSymGroup = 0;
	private int nextMatchGroup = 0;

	// ==================== CONSTRAINT TYPES ====================

	/**
	 * Symmetry constraint: two devices (or groups) must be placed symmetrically
	 * about an axis. Required for differential pairs, matched loads, etc.
	 */
	public static class SymmetryConstraint
	{
		public enum Axis { HORIZONTAL, VERTICAL }

		public final int groupId;
		public final List<CircuitGraph.Device> devicesA;
		public final List<CircuitGraph.Device> devicesB;
		public final Axis axis;
		public final String reason;

		public SymmetryConstraint(int groupId, List<CircuitGraph.Device> a,
			List<CircuitGraph.Device> b, Axis axis, String reason)
		{
			this.groupId = groupId;
			this.devicesA = a;
			this.devicesB = b;
			this.axis = axis;
			this.reason = reason;
		}

		@Override
		public String toString()
		{
			return "SYM[" + groupId + "] " + devicesA + " <=> " + devicesB + " (" + reason + ")";
		}
	}

	/**
	 * Matching constraint: devices must be matched (same W/L, interdigitated
	 * or common centroid placement). Used for current mirrors, load devices.
	 */
	public static class MatchingConstraint
	{
		public enum Style { INTERDIGITATED, COMMON_CENTROID, ABBA }

		public final int groupId;
		public final List<CircuitGraph.Device> devices;
		public final Style style;
		public final String reason;

		public MatchingConstraint(int groupId, List<CircuitGraph.Device> devices,
			Style style, String reason)
		{
			this.groupId = groupId;
			this.devices = devices;
			this.style = style;
			this.reason = reason;
		}

		@Override
		public String toString()
		{
			return "MATCH[" + groupId + "] " + devices + " " + style + " (" + reason + ")";
		}
	}

	/**
	 * Proximity constraint: devices should be placed close together.
	 */
	public static class ProximityConstraint
	{
		public final CircuitGraph.Device deviceA;
		public final CircuitGraph.Device deviceB;
		public final double maxDistance;
		public final String reason;

		public ProximityConstraint(CircuitGraph.Device a, CircuitGraph.Device b,
			double maxDist, String reason)
		{
			this.deviceA = a;
			this.deviceB = b;
			this.maxDistance = maxDist;
			this.reason = reason;
		}
	}

	// ==================== MAIN EXTRACTION ====================

	public ConstraintExtractor(CircuitGraph graph)
	{
		this.graph = graph;
	}

	/**
	 * Run all constraint extraction passes.
	 */
	public void extractAll()
	{
		System.out.println("  ALSE Constraint Extraction: analyzing circuit topology...");

		detectDifferentialPairs();
		detectCurrentMirrors();
		detectMatchedLoads();
		detectDifferentialNets();
		detectBiasNetworks();

		// Apply constraints to devices
		for (SymmetryConstraint sc : symmetryConstraints)
		{
			for (CircuitGraph.Device d : sc.devicesA)
				d.setSymmetryGroup(sc.groupId);
			for (CircuitGraph.Device d : sc.devicesB)
				d.setSymmetryGroup(sc.groupId);
		}
		for (MatchingConstraint mc : matchingConstraints)
		{
			for (CircuitGraph.Device d : mc.devices)
				d.setMatchingGroup(mc.groupId);
			// Set match partners for pairs
			if (mc.devices.size() == 2)
			{
				mc.devices.get(0).setMatchPartner(mc.devices.get(1));
				mc.devices.get(1).setMatchPartner(mc.devices.get(0));
			}
		}

		printSummary();
	}

	public List<SymmetryConstraint> getSymmetryConstraints() { return symmetryConstraints; }
	public List<MatchingConstraint> getMatchingConstraints() { return matchingConstraints; }
	public List<ProximityConstraint> getProximityConstraints() { return proximityConstraints; }

	// ==================== DIFFERENTIAL PAIR DETECTION ====================

	/**
	 * Detect differential pairs: two same-type MOSFETs sharing a common source
	 * node (tail current), with similar W/L ratios.
	 *
	 * Pattern: M1 and M2 have same source net (not supply), same type, similar W/L.
	 * The shared source connects to a current source (tail).
	 */
	private void detectDifferentialPairs()
	{
		Map<CircuitGraph.Net, List<CircuitGraph.Device>> bySource = graph.getDevicesBySourceNet();

		for (Map.Entry<CircuitGraph.Net, List<CircuitGraph.Device>> entry : bySource.entrySet())
		{
			CircuitGraph.Net sourceNet = entry.getKey();
			List<CircuitGraph.Device> devices = entry.getValue();

			if (sourceNet.isSupply()) continue;
			if (devices.size() < 2) continue;

			// Group by type
			Map<CircuitGraph.Device.Type, List<CircuitGraph.Device>> byType =
				new LinkedHashMap<CircuitGraph.Device.Type, List<CircuitGraph.Device>>();
			for (CircuitGraph.Device d : devices)
			{
				List<CircuitGraph.Device> list = byType.get(d.getType());
				if (list == null)
				{
					list = new ArrayList<CircuitGraph.Device>();
					byType.put(d.getType(), list);
				}
				list.add(d);
			}

			for (List<CircuitGraph.Device> sameType : byType.values())
			{
				if (sameType.size() < 2) continue;

				// Find pairs with matching W/L
				for (int i = 0; i < sameType.size(); i++)
				{
					for (int j = i + 1; j < sameType.size(); j++)
					{
						CircuitGraph.Device d1 = sameType.get(i);
						CircuitGraph.Device d2 = sameType.get(j);

						if (isMatchedWL(d1, d2))
						{
							// Check that source net connects to a current source or tail transistor
							boolean hasTail = false;
							for (CircuitGraph.Pin pin : sourceNet.getPins())
							{
								CircuitGraph.Device tailDev = pin.getDevice();
								if (tailDev != d1 && tailDev != d2)
								{
									// Source net connects to a third device = tail current
									hasTail = true;
									break;
								}
							}

							if (hasTail || sourceNet.fanout() >= 3)
							{
								int gid = nextSymGroup++;
								symmetryConstraints.add(new SymmetryConstraint(gid,
									Arrays.asList(d1), Arrays.asList(d2),
									SymmetryConstraint.Axis.VERTICAL,
									"differential pair (shared source=" + sourceNet.getName() + ")"));
								System.out.println("    Found differential pair: " +
									d1.getName() + " <=> " + d2.getName());
							}
						}
					}
				}
			}
		}
	}

	// ==================== CURRENT MIRROR DETECTION ====================

	/**
	 * Detect current mirrors: two or more same-type MOSFETs sharing a common
	 * gate net, where at least one has gate=drain (diode-connected).
	 *
	 * Pattern: M1 (diode) and M2..Mn share gate net. M1.gate = M1.drain.
	 */
	private void detectCurrentMirrors()
	{
		Map<CircuitGraph.Net, List<CircuitGraph.Device>> byGate = graph.getDevicesByGateNet();

		for (Map.Entry<CircuitGraph.Net, List<CircuitGraph.Device>> entry : byGate.entrySet())
		{
			CircuitGraph.Net gateNet = entry.getKey();
			List<CircuitGraph.Device> devices = entry.getValue();

			if (gateNet.isSupply()) continue;
			if (devices.size() < 2) continue;

			// Group by type
			Map<CircuitGraph.Device.Type, List<CircuitGraph.Device>> byType =
				new LinkedHashMap<CircuitGraph.Device.Type, List<CircuitGraph.Device>>();
			for (CircuitGraph.Device d : devices)
			{
				if (!d.isMosfet()) continue;
				List<CircuitGraph.Device> list = byType.get(d.getType());
				if (list == null)
				{
					list = new ArrayList<CircuitGraph.Device>();
					byType.put(d.getType(), list);
				}
				list.add(d);
			}

			for (List<CircuitGraph.Device> sameType : byType.values())
			{
				if (sameType.size() < 2) continue;

				// Check for diode-connected device (gate = drain)
				boolean hasDiode = false;
				for (CircuitGraph.Device d : sameType)
				{
					CircuitGraph.Net drain = d.getNet(CircuitGraph.Pin.Function.DRAIN);
					if (drain != null && drain == gateNet)
					{
						hasDiode = true;
						break;
					}
				}

				if (hasDiode)
				{
					int mid = nextMatchGroup++;
					matchingConstraints.add(new MatchingConstraint(mid,
						new ArrayList<CircuitGraph.Device>(sameType),
						MatchingConstraint.Style.INTERDIGITATED,
						"current mirror (gate=" + gateNet.getName() + ")"));
					System.out.println("    Found current mirror: " +
						deviceNames(sameType) + " (gate=" + gateNet.getName() + ")");

					// Also add symmetry if exactly 2 devices with same W/L
					if (sameType.size() == 2 && isMatchedWL(sameType.get(0), sameType.get(1)))
					{
						int gid = nextSymGroup++;
						symmetryConstraints.add(new SymmetryConstraint(gid,
							Arrays.asList(sameType.get(0)), Arrays.asList(sameType.get(1)),
							SymmetryConstraint.Axis.VERTICAL,
							"matched current mirror"));
					}

					// Add proximity constraints between all devices in mirror
					for (int i = 0; i < sameType.size(); i++)
					{
						for (int j = i + 1; j < sameType.size(); j++)
						{
							proximityConstraints.add(new ProximityConstraint(
								sameType.get(i), sameType.get(j), 50.0,
								"current mirror proximity"));
						}
					}
				}
			}
		}
	}

	// ==================== MATCHED LOAD DETECTION ====================

	/**
	 * Detect matched loads: same-type MOSFETs sharing a common source (supply)
	 * with matched W/L but different drain nets. Typical for active loads
	 * in differential amplifiers.
	 */
	private void detectMatchedLoads()
	{
		Map<CircuitGraph.Net, List<CircuitGraph.Device>> bySource = graph.getDevicesBySourceNet();

		for (Map.Entry<CircuitGraph.Net, List<CircuitGraph.Device>> entry : bySource.entrySet())
		{
			CircuitGraph.Net sourceNet = entry.getKey();
			List<CircuitGraph.Device> devices = entry.getValue();

			// Matched loads typically connect to supply
			if (!sourceNet.isSupply()) continue;
			if (devices.size() < 2) continue;

			// Group by type and gate net (shared gate = mirrored load)
			Map<CircuitGraph.Net, List<CircuitGraph.Device>> byGate =
				new LinkedHashMap<CircuitGraph.Net, List<CircuitGraph.Device>>();
			for (CircuitGraph.Device d : devices)
			{
				if (!d.isMosfet()) continue;
				CircuitGraph.Net gateNet = d.getNet(CircuitGraph.Pin.Function.GATE);
				if (gateNet == null || gateNet.isSupply()) continue;
				List<CircuitGraph.Device> list = byGate.get(gateNet);
				if (list == null)
				{
					list = new ArrayList<CircuitGraph.Device>();
					byGate.put(gateNet, list);
				}
				list.add(d);
			}

			for (List<CircuitGraph.Device> sameGate : byGate.values())
			{
				if (sameGate.size() < 2) continue;

				// Check W/L matching
				boolean allMatched = true;
				for (int i = 1; i < sameGate.size(); i++)
				{
					if (!isMatchedWL(sameGate.get(0), sameGate.get(i)))
					{
						allMatched = false;
						break;
					}
				}

				if (allMatched)
				{
					// Check if already detected as current mirror
					boolean alreadyDetected = false;
					for (MatchingConstraint mc : matchingConstraints)
					{
						if (mc.devices.containsAll(sameGate))
						{
							alreadyDetected = true;
							break;
						}
					}

					if (!alreadyDetected)
					{
						int mid = nextMatchGroup++;
						matchingConstraints.add(new MatchingConstraint(mid,
							new ArrayList<CircuitGraph.Device>(sameGate),
							MatchingConstraint.Style.COMMON_CENTROID,
							"matched load (source=" + sourceNet.getName() + ")"));
						System.out.println("    Found matched load: " + deviceNames(sameGate));
					}
				}
			}
		}
	}

	// ==================== DIFFERENTIAL NET DETECTION ====================

	/**
	 * Detect differential signal pairs: nets that are the drain outputs of
	 * a differential pair. Mark them for symmetric routing.
	 */
	private void detectDifferentialNets()
	{
		for (SymmetryConstraint sc : symmetryConstraints)
		{
			if (!sc.reason.startsWith("differential pair")) continue;

			// Get drain nets of the pair
			CircuitGraph.Net drainA = null, drainB = null;
			if (!sc.devicesA.isEmpty())
				drainA = sc.devicesA.get(0).getNet(CircuitGraph.Pin.Function.DRAIN);
			if (!sc.devicesB.isEmpty())
				drainB = sc.devicesB.get(0).getNet(CircuitGraph.Pin.Function.DRAIN);

			if (drainA != null && drainB != null && drainA != drainB)
			{
				drainA.setType(CircuitGraph.Net.Type.DIFFERENTIAL_P);
				drainB.setType(CircuitGraph.Net.Type.DIFFERENTIAL_N);
				drainA.setSymmetricPair(drainB);
				drainB.setSymmetricPair(drainA);
				drainA.setCritical(true);
				drainB.setCritical(true);
				System.out.println("    Differential net pair: " +
					drainA.getName() + " / " + drainB.getName());
			}

			// Gate inputs are also differential
			CircuitGraph.Net gateA = null, gateB = null;
			if (!sc.devicesA.isEmpty())
				gateA = sc.devicesA.get(0).getNet(CircuitGraph.Pin.Function.GATE);
			if (!sc.devicesB.isEmpty())
				gateB = sc.devicesB.get(0).getNet(CircuitGraph.Pin.Function.GATE);

			if (gateA != null && gateB != null && gateA != gateB)
			{
				gateA.setSymmetricPair(gateB);
				gateB.setSymmetricPair(gateA);
			}
		}
	}

	// ==================== BIAS NETWORK DETECTION ====================

	/**
	 * Detect bias networks: diode-connected transistors and their associated
	 * current paths. Mark for proximity to the circuits they bias.
	 */
	private void detectBiasNetworks()
	{
		for (CircuitGraph.Device d : graph.getDevices())
		{
			if (!d.isMosfet()) continue;

			CircuitGraph.Net gate = d.getNet(CircuitGraph.Pin.Function.GATE);
			CircuitGraph.Net drain = d.getNet(CircuitGraph.Pin.Function.DRAIN);

			if (gate == null || drain == null) continue;

			// Diode-connected: gate = drain
			if (gate == drain)
			{
				gate.setType(CircuitGraph.Net.Type.BIAS);

				// Find all devices connected to this bias net
				for (CircuitGraph.Device biased : gate.getDevices())
				{
					if (biased == d) continue;
					if (biased.getNet(CircuitGraph.Pin.Function.GATE) == gate)
					{
						proximityConstraints.add(new ProximityConstraint(d, biased, 30.0,
							"bias proximity (gate=" + gate.getName() + ")"));
					}
				}
			}
		}
	}

	// ==================== UTILITIES ====================

	/**
	 * Check if two MOSFETs have matching W/L ratios (within 1% tolerance).
	 */
	private static boolean isMatchedWL(CircuitGraph.Device d1, CircuitGraph.Device d2)
	{
		if (d1.getType() != d2.getType()) return false;

		double w1 = d1.getWidth(), w2 = d2.getWidth();
		double l1 = d1.getLength(), l2 = d2.getLength();

		// If both have valid W/L, check matching
		if (w1 > 0 && w2 > 0 && l1 > 0 && l2 > 0)
		{
			double ratio1 = w1 / l1;
			double ratio2 = w2 / l2;
			return Math.abs(ratio1 - ratio2) / Math.max(ratio1, ratio2) < 0.01;
		}

		// If no parameters, assume matched if same type
		return true;
	}

	private static String deviceNames(List<CircuitGraph.Device> devices)
	{
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < devices.size(); i++)
		{
			if (i > 0) sb.append(", ");
			sb.append(devices.get(i).getName());
		}
		return sb.toString();
	}

	private void printSummary()
	{
		System.out.println("  ALSE Constraints:");
		System.out.println("    Symmetry groups: " + symmetryConstraints.size());
		System.out.println("    Matching groups: " + matchingConstraints.size());
		System.out.println("    Proximity pairs: " + proximityConstraints.size());
	}

}
