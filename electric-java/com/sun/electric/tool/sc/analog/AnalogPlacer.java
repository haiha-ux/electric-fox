/* -*- tab-width: 4 -*-
 *
 * Electric(tm) VLSI Design System
 *
 * File: AnalogPlacer.java
 * Analog Layout Synthesis Engine: Simulated Annealing placement engine
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
 * Simulated Annealing placement engine for analog circuits.
 *
 * Uses B*-tree representation with contour-based evaluation.
 * Cost function combines:
 * <ul>
 *   <li>Total area (primary)</li>
 *   <li>Total wirelength (HPWL)</li>
 *   <li>Symmetry violation penalty</li>
 *   <li>Matching violation penalty</li>
 *   <li>Proximity constraint penalty</li>
 * </ul>
 *
 * SA schedule: geometric cooling with adaptive perturbation.
 * Based on techniques from MAGICAL (UT Austin) analog placer.
 */
public class AnalogPlacer
{
	private final CircuitGraph graph;
	private final ConstraintExtractor constraints;
	private final BStarTree tree;

	// SA parameters
	private double initialTemp = 1e6;
	private double coolingRate = 0.95;
	private double freezingTemp = 0.1;
	private int movesPerTemp = 200;

	// Cost weights
	private double weightArea = 0.35;
	private double weightWire = 0.25;
	private double weightSymmetry = 0.2;
	private double weightProximity = 0.1;
	private double weightParasitic = 0.1;

	// Statistics
	private int totalMoves = 0;
	private int acceptedMoves = 0;
	private double bestCost = Double.MAX_VALUE;
	private Map<CircuitGraph.Device, double[]> bestPositions;

	public AnalogPlacer(CircuitGraph graph, ConstraintExtractor constraints)
	{
		this.graph = graph;
		this.constraints = constraints;
		this.tree = new BStarTree();
	}

	/**
	 * Set SA parameters.
	 */
	public void setParameters(double initTemp, double cooling, double freeze, int movesPerT)
	{
		this.initialTemp = initTemp;
		this.coolingRate = cooling;
		this.freezingTemp = freeze;
		this.movesPerTemp = movesPerT;
	}

	/**
	 * Set cost function weights (must sum to ~1.0).
	 */
	public void setWeights(double area, double wire, double symmetry, double proximity)
	{
		this.weightArea = area;
		this.weightWire = wire;
		this.weightSymmetry = symmetry;
		this.weightProximity = proximity;
	}

	/**
	 * Run the Simulated Annealing placement optimization.
	 *
	 * @return map from device to [x, y] placement coordinates
	 */
	public Map<CircuitGraph.Device, double[]> place()
	{
		List<CircuitGraph.Device> devices = graph.getDevices();
		if (devices.isEmpty()) return new LinkedHashMap<CircuitGraph.Device, double[]>();

		System.out.println("  ALSE Placement: " + devices.size() + " devices, SA optimization...");

		// Initialize B*-tree
		tree.buildInitial(devices);
		tree.evaluate();

		// Compute initial cost
		Map<CircuitGraph.Device, double[]> positions = tree.getPositions();
		double currentCost = computeCost(positions);
		bestCost = currentCost;
		bestPositions = new LinkedHashMap<CircuitGraph.Device, double[]>(positions);

		// Normalize cost components for initial temperature
		double normArea = tree.getTotalArea();
		double normWire = graph.computeTotalHPWL(positions);
		if (normWire <= 0) normWire = 1;

		// Auto-tune initial temperature: accept ~95% of moves initially
		initialTemp = Math.max(currentCost * 0.5, 1.0);

		// SA main loop
		double temp = initialTemp;
		int iteration = 0;

		while (temp > freezingTemp)
		{
			int accepted = 0;

			for (int m = 0; m < movesPerTemp; m++)
			{
				totalMoves++;

				// Perturb
				BStarTree.Memento memento = tree.perturb();

				// Evaluate
				tree.evaluate();
				positions = tree.getPositions();
				double newCost = computeCost(positions);

				double deltaCost = newCost - currentCost;

				// Metropolis criterion
				if (deltaCost < 0 || Math.random() < Math.exp(-deltaCost / temp))
				{
					// Accept
					currentCost = newCost;
					accepted++;
					acceptedMoves++;

					if (currentCost < bestCost)
					{
						bestCost = currentCost;
						bestPositions = new LinkedHashMap<CircuitGraph.Device, double[]>(positions);
					}
				}
				else
				{
					// Reject: undo perturbation
					memento.undo();
				}
			}

			// Cool down
			temp *= coolingRate;
			iteration++;

			// Progress report every 10 iterations
			if (iteration % 10 == 0)
			{
				double acceptRate = (double) accepted / movesPerTemp;
				System.out.println("    SA iter=" + iteration + " T=" +
					String.format("%.1f", temp) + " cost=" +
					String.format("%.2f", currentCost) + " accept=" +
					String.format("%.0f%%", acceptRate * 100));
			}

			// Early termination if acceptance rate too low
			if (accepted < movesPerTemp * 0.01 && iteration > 20)
				break;
		}

		// Restore best solution
		System.out.println("  ALSE Placement complete: " + totalMoves + " moves, " +
			acceptedMoves + " accepted, best cost=" + String.format("%.2f", bestCost));
		System.out.println("    Area: " + String.format("%.1f", tree.getTotalWidth()) + " x " +
			String.format("%.1f", tree.getTotalHeight()));

		return bestPositions;
	}

	// ==================== COST FUNCTION ====================

	/**
	 * Compute the total weighted cost for a placement.
	 */
	private double computeCost(Map<CircuitGraph.Device, double[]> positions)
	{
		double areaCost = computeAreaCost();
		double wireCost = computeWireCost(positions);
		double symCost = computeSymmetryCost(positions);
		double proxCost = computeProximityCost(positions);
		double parasiticCost = computeParasiticCost(positions) + computeMatchingParasiticCost(positions);

		return weightArea * areaCost +
			weightWire * wireCost +
			weightSymmetry * symCost +
			weightProximity * proxCost +
			weightParasitic * parasiticCost;
	}

	/**
	 * Area cost: bounding box area of the placement.
	 */
	private double computeAreaCost()
	{
		return tree.getTotalArea();
	}

	/**
	 * Wirelength cost: total Half-Perimeter Wire Length.
	 */
	private double computeWireCost(Map<CircuitGraph.Device, double[]> positions)
	{
		return graph.computeTotalHPWL(positions);
	}

	/**
	 * Symmetry violation cost: penalize asymmetric placement of symmetric pairs.
	 */
	private double computeSymmetryCost(Map<CircuitGraph.Device, double[]> positions)
	{
		if (constraints == null) return 0;

		double cost = 0;
		for (ConstraintExtractor.SymmetryConstraint sc : constraints.getSymmetryConstraints())
		{
			// Compute center of mass for each group
			double[] centerA = computeCenter(sc.devicesA, positions);
			double[] centerB = computeCenter(sc.devicesB, positions);

			if (centerA == null || centerB == null) continue;

			if (sc.axis == ConstraintExtractor.SymmetryConstraint.Axis.VERTICAL)
			{
				// Y positions should match, X should be symmetric about axis
				double yDiff = Math.abs(centerA[1] - centerB[1]);
				cost += yDiff * 100; // heavy penalty for y mismatch
			}
			else
			{
				double xDiff = Math.abs(centerA[0] - centerB[0]);
				cost += xDiff * 100;
			}
		}
		return cost;
	}

	/**
	 * Proximity constraint cost: penalize distant placement of proximate pairs.
	 */
	private double computeProximityCost(Map<CircuitGraph.Device, double[]> positions)
	{
		if (constraints == null) return 0;

		double cost = 0;
		for (ConstraintExtractor.ProximityConstraint pc : constraints.getProximityConstraints())
		{
			double[] posA = positions.get(pc.deviceA);
			double[] posB = positions.get(pc.deviceB);
			if (posA == null || posB == null) continue;

			double dist = Math.abs(posA[0] - posB[0]) + Math.abs(posA[1] - posB[1]);
			if (dist > pc.maxDistance)
			{
				cost += (dist - pc.maxDistance) * 10;
			}
		}
		return cost;
	}

	/**
	 * Compute center of mass for a group of devices.
	 */
	private double[] computeCenter(List<CircuitGraph.Device> devices,
		Map<CircuitGraph.Device, double[]> positions)
	{
		double sumX = 0, sumY = 0;
		int count = 0;
		for (CircuitGraph.Device d : devices)
		{
			double[] pos = positions.get(d);
			if (pos != null)
			{
				sumX += pos[0] + d.getLayoutWidth() / 2;
				sumY += pos[1] + d.getLayoutHeight() / 2;
				count++;
			}
		}
		if (count == 0) return null;
		return new double[]{sumX / count, sumY / count};
	}

	// ==================== PARASITIC-AWARE OPTIMIZATION ====================

	/**
	 * Estimate interconnect parasitics for the current placement.
	 * Computes approximate wire resistance and capacitance based on
	 * Manhattan distances between connected devices.
	 *
	 * @param positions current device positions
	 * @return total parasitic cost (weighted sum of R and C)
	 */
	private double computeParasiticCost(Map<CircuitGraph.Device, double[]> positions)
	{
		// Metal-1 sheet resistance and capacitance per unit length (typical values)
		double rSheet = 0.08;   // ohms/square for Metal-1
		double cPerUm = 0.02;  // fF/um for Metal-1
		double metalW = 0.3;    // um, typical metal width

		double totalRC = 0;

		for (CircuitGraph.Net net : graph.getSignalNets())
		{
			if (net.isSupply()) continue;
			if (net.isCritical())
			{
				// For critical nets, estimate RC delay
				List<CircuitGraph.Pin> pins = net.getPins();
				if (pins.size() < 2) continue;

				// Compute HPWL as wire length estimate
				double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
				double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;

				for (CircuitGraph.Pin pin : pins)
				{
					double[] pos = positions.get(pin.getDevice());
					if (pos == null) continue;
					double cx = pos[0] + pin.getDevice().getLayoutWidth() / 2;
					double cy = pos[1] + pin.getDevice().getLayoutHeight() / 2;
					if (cx < minX) minX = cx;
					if (cx > maxX) maxX = cx;
					if (cy < minY) minY = cy;
					if (cy > maxY) maxY = cy;
				}

				double wireLen = (maxX - minX) + (maxY - minY);
				if (wireLen <= 0) continue;

				// Elmore delay model: RC = R_total * C_total / 2
				double rWire = rSheet * wireLen / metalW;
				double cWire = cPerUm * wireLen;
				double rcDelay = rWire * cWire / 2;

				// Higher penalty for critical nets
				totalRC += rcDelay * 50;
			}
		}

		return totalRC;
	}

	/**
	 * Compute parasitic mismatch cost for matched device pairs.
	 * Penalizes asymmetric routing parasitics that degrade matching.
	 */
	private double computeMatchingParasiticCost(Map<CircuitGraph.Device, double[]> positions)
	{
		if (constraints == null) return 0;

		double cost = 0;
		for (ConstraintExtractor.MatchingConstraint mc : constraints.getMatchingConstraints())
		{
			List<CircuitGraph.Device> devs = mc.devices;
			if (devs.size() < 2) continue;

			// For each pair of matched devices, penalize parasitic mismatch
			for (int i = 0; i < devs.size() - 1; i++)
			{
				for (int j = i + 1; j < devs.size(); j++)
				{
					CircuitGraph.Device dA = devs.get(i);
					CircuitGraph.Device dB = devs.get(j);
					double[] posA = positions.get(dA);
					double[] posB = positions.get(dB);
					if (posA == null || posB == null) continue;

					// For matched pairs, check distance symmetry to shared nets
					for (CircuitGraph.Net net : graph.getSignalNets())
					{
						boolean connA = false, connB = false;
						for (CircuitGraph.Pin pin : net.getPins())
						{
							if (pin.getDevice() == dA) connA = true;
							if (pin.getDevice() == dB) connB = true;
						}

						if (connA && connB)
						{
							double centerX = 0, centerY = 0;
							int cnt = 0;
							for (CircuitGraph.Pin pin : net.getPins())
							{
								double[] pos = positions.get(pin.getDevice());
								if (pos != null) { centerX += pos[0]; centerY += pos[1]; cnt++; }
							}
							if (cnt > 0) { centerX /= cnt; centerY /= cnt; }

							double distA = Math.abs(posA[0] - centerX) + Math.abs(posA[1] - centerY);
							double distB = Math.abs(posB[0] - centerX) + Math.abs(posB[1] - centerY);
							cost += Math.abs(distA - distB) * 20;
						}
					}
				}
			}
		}

		return cost;
	}

	// ==================== RESULTS ====================

	public Map<CircuitGraph.Device, double[]> getBestPositions() { return bestPositions; }
	public double getBestCost() { return bestCost; }
}
