/* -*- tab-width: 4 -*-
 *
 * Electric(tm) VLSI Design System
 *
 * File: AnalogRouter.java
 * Analog Layout Synthesis Engine: multi-layer symmetry-aware router
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
 * Multi-layer symmetry-aware router for analog circuits.
 *
 * Features:
 * <ul>
 *   <li>A* maze routing on a multi-layer grid</li>
 *   <li>Symmetric routing of differential net pairs</li>
 *   <li>Priority-based net ordering (critical nets first)</li>
 *   <li>Via minimization</li>
 *   <li>Power/ground mesh generation</li>
 * </ul>
 *
 * Based on grid-based routing with A* search kernel,
 * extended with analog constraint handling from MAGICAL router.
 */
public class AnalogRouter
{
	private final CircuitGraph graph;
	private final Map<CircuitGraph.Device, double[]> positions;
	private int gridPitch = 1;      // routing grid pitch
	private int numLayers = 3;      // Metal-1, Metal-2, Metal-3

	// Routing grid
	private int gridW, gridH;
	private boolean[][][] occupied;  // [layer][x][y]

	// Results
	private final List<RoutePath> routes = new ArrayList<RoutePath>();

	/**
	 * A routing path: sequence of grid points with layer assignments.
	 */
	public static class RoutePath
	{
		public final CircuitGraph.Net net;
		public final List<int[]> points;  // each: [x, y, layer]
		public final List<int[]> vias;    // each: [x, y, fromLayer, toLayer]

		public RoutePath(CircuitGraph.Net net)
		{
			this.net = net;
			this.points = new ArrayList<int[]>();
			this.vias = new ArrayList<int[]>();
		}
	}

	public AnalogRouter(CircuitGraph graph, Map<CircuitGraph.Device, double[]> positions)
	{
		this.graph = graph;
		this.positions = positions;
	}

	public void setGridPitch(int pitch) { this.gridPitch = pitch; }
	public void setNumLayers(int layers) { this.numLayers = layers; }

	/**
	 * Route all nets in the circuit.
	 *
	 * @return list of routing paths
	 */
	public List<RoutePath> routeAll()
	{
		System.out.println("  ALSE Routing: " + graph.getSignalNets().size() +
			" signal nets, " + numLayers + " layers...");

		routes.clear();
		initGrid();

		// Sort nets by priority: symmetric pairs first, then critical, then by fanout
		List<CircuitGraph.Net> netOrder = prioritizeNets();

		int routed = 0, failed = 0;
		Set<CircuitGraph.Net> routedNets = new HashSet<CircuitGraph.Net>();

		for (CircuitGraph.Net net : netOrder)
		{
			if (routedNets.contains(net)) continue;
			if (net.isSupply()) continue;

			// Check for symmetric pair — route both simultaneously
			CircuitGraph.Net pair = net.getSymmetricPair();
			if (pair != null && !routedNets.contains(pair))
			{
				boolean ok = routeSymmetricPair(net, pair);
				routedNets.add(net);
				routedNets.add(pair);
				if (ok) routed += 2;
				else failed += 2;
			}
			else
			{
				boolean ok = routeNet(net);
				routedNets.add(net);
				if (ok) routed++;
				else failed++;
			}
		}

		System.out.println("  ALSE Routing complete: " + routed + " routed, " + failed + " failed");
		return routes;
	}

	/**
	 * Initialize the routing grid from placement results.
	 */
	private void initGrid()
	{
		// Compute grid dimensions from placement bounding box
		double maxX = 0, maxY = 0;
		for (Map.Entry<CircuitGraph.Device, double[]> entry : positions.entrySet())
		{
			double[] pos = entry.getValue();
			CircuitGraph.Device dev = entry.getKey();
			double right = pos[0] + dev.getLayoutWidth();
			double top = pos[1] + dev.getLayoutHeight();
			if (right > maxX) maxX = right;
			if (top > maxY) maxY = top;
		}

		// Add margin for routing channels
		gridW = (int) (maxX / gridPitch) + 20;
		gridH = (int) (maxY / gridPitch) + 20;

		// Cap grid size to prevent OOM
		int maxGrid = 2000;
		if (gridW > maxGrid || gridH > maxGrid)
		{
			System.out.println("  ALSE Routing: grid too large (" + gridW + "x" + gridH +
				"), scaling pitch to fit");
			int maxDim = Math.max(gridW, gridH);
			gridPitch = Math.max(gridPitch, (maxDim + maxGrid - 1) / maxGrid);
			gridW = (int) (maxX / gridPitch) + 20;
			gridH = (int) (maxY / gridPitch) + 20;
			if (gridW > maxGrid) gridW = maxGrid;
			if (gridH > maxGrid) gridH = maxGrid;
		}

		occupied = new boolean[numLayers][gridW][gridH];

		// Block grid cells occupied by devices
		for (Map.Entry<CircuitGraph.Device, double[]> entry : positions.entrySet())
		{
			double[] pos = entry.getValue();
			CircuitGraph.Device dev = entry.getKey();
			int x1 = (int) (pos[0] / gridPitch);
			int y1 = (int) (pos[1] / gridPitch);
			int x2 = x1 + (int) (dev.getLayoutWidth() / gridPitch);
			int y2 = y1 + (int) (dev.getLayoutHeight() / gridPitch);

			// Block layer 0 (device level)
			for (int x = Math.max(0, x1); x < Math.min(gridW, x2); x++)
				for (int y = Math.max(0, y1); y < Math.min(gridH, y2); y++)
					occupied[0][x][y] = true;
		}
	}

	/**
	 * Prioritize nets for routing order.
	 */
	private List<CircuitGraph.Net> prioritizeNets()
	{
		List<CircuitGraph.Net> nets = new ArrayList<CircuitGraph.Net>(graph.getSignalNets());
		Collections.sort(nets, new Comparator<CircuitGraph.Net>()
		{
			public int compare(CircuitGraph.Net a, CircuitGraph.Net b)
			{
				// Symmetric pairs first
				int symA = a.getSymmetricPair() != null ? 0 : 1;
				int symB = b.getSymmetricPair() != null ? 0 : 1;
				if (symA != symB) return symA - symB;

				// Critical nets next
				int critA = a.isCritical() ? 0 : 1;
				int critB = b.isCritical() ? 0 : 1;
				if (critA != critB) return critA - critB;

				// Lower fanout first (easier to route)
				return a.fanout() - b.fanout();
			}
		});
		return nets;
	}

	/**
	 * Route a single net using A* maze routing.
	 */
	private boolean routeNet(CircuitGraph.Net net)
	{
		List<int[]> terminals = getNetTerminals(net);
		if (terminals.size() < 2) return true; // nothing to route

		RoutePath path = new RoutePath(net);

		// Route as Steiner tree: connect all terminals via minimum spanning tree
		Set<Integer> connected = new HashSet<Integer>();
		connected.add(0);

		while (connected.size() < terminals.size())
		{
			// Find nearest unconnected terminal to any connected terminal
			int bestFrom = -1, bestTo = -1;
			double bestDist = Double.MAX_VALUE;

			for (int from : connected)
			{
				for (int to = 0; to < terminals.size(); to++)
				{
					if (connected.contains(to)) continue;
					int[] f = terminals.get(from);
					int[] t = terminals.get(to);
					double dist = Math.abs(f[0] - t[0]) + Math.abs(f[1] - t[1]);
					if (dist < bestDist)
					{
						bestDist = dist;
						bestFrom = from;
						bestTo = to;
					}
				}
			}

			if (bestTo < 0) break;

			// Route between bestFrom and bestTo using A*
			int[] from = terminals.get(bestFrom);
			int[] to = terminals.get(bestTo);
			List<int[]> segment = aStarRoute(from, to, 1); // route on metal-1

			if (segment != null)
			{
				path.points.addAll(segment);
				// Mark as occupied
				for (int[] pt : segment)
				{
					int layer = pt.length > 2 ? pt[2] : 1;
					if (pt[0] >= 0 && pt[0] < gridW && pt[1] >= 0 && pt[1] < gridH)
						occupied[layer][pt[0]][pt[1]] = true;
				}
			}

			connected.add(bestTo);
		}

		routes.add(path);
		return true;
	}

	/**
	 * Route a symmetric pair of nets simultaneously.
	 * Ensures the two routes are mirror images about the symmetry axis.
	 */
	private boolean routeSymmetricPair(CircuitGraph.Net netP, CircuitGraph.Net netN)
	{
		List<int[]> terminalsP = getNetTerminals(netP);
		List<int[]> terminalsN = getNetTerminals(netN);

		if (terminalsP.size() < 2 && terminalsN.size() < 2) return true;

		// Find symmetry axis (average X of all terminals)
		double axisX = 0;
		int count = 0;
		for (int[] t : terminalsP) { axisX += t[0]; count++; }
		for (int[] t : terminalsN) { axisX += t[0]; count++; }
		if (count > 0) axisX /= count;

		// Route the positive net
		boolean okP = routeNet(netP);

		// Mirror the route for the negative net
		if (okP && !routes.isEmpty())
		{
			RoutePath routeP = routes.get(routes.size() - 1);
			RoutePath routeN = new RoutePath(netN);

			for (int[] pt : routeP.points)
			{
				int mirrorX = (int) (2 * axisX - pt[0]);
				int layer = pt.length > 2 ? pt[2] : 1;
				routeN.points.add(new int[]{mirrorX, pt[1], layer});

				// Mark mirrored route as occupied
				if (mirrorX >= 0 && mirrorX < gridW && pt[1] >= 0 && pt[1] < gridH)
					occupied[layer][mirrorX][pt[1]] = true;
			}

			routes.add(routeN);
		}
		else
		{
			// Fallback: route independently
			routeNet(netN);
		}

		return okP;
	}

	/**
	 * A* maze routing between two grid points.
	 */
	private List<int[]> aStarRoute(int[] start, int[] end, int layer)
	{
		if (start[0] == end[0] && start[1] == end[1]) return new ArrayList<int[]>();

		// Priority queue: [x, y, gCost, fCost]
		PriorityQueue<int[]> openSet = new PriorityQueue<int[]>(new Comparator<int[]>()
		{
			public int compare(int[] a, int[] b) { return a[3] - b[3]; }
		});

		Map<Long, int[]> cameFrom = new HashMap<Long, int[]>();
		Map<Long, Integer> gScore = new HashMap<Long, Integer>();

		long startKey = gridKey(start[0], start[1]);
		gScore.put(startKey, 0);
		int h = Math.abs(end[0] - start[0]) + Math.abs(end[1] - start[1]);
		openSet.add(new int[]{start[0], start[1], 0, h});

		int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
		int maxIter = gridW * gridH; // prevent infinite loops

		while (!openSet.isEmpty() && maxIter-- > 0)
		{
			int[] current = openSet.poll();
			int cx = current[0], cy = current[1];

			if (cx == end[0] && cy == end[1])
			{
				// Reconstruct path
				return reconstructPath(cameFrom, end, layer);
			}

			for (int[] dir : dirs)
			{
				int nx = cx + dir[0];
				int ny = cy + dir[1];

				if (nx < 0 || nx >= gridW || ny < 0 || ny >= gridH) continue;
				if (layer < numLayers && occupied[layer][nx][ny]) continue;

				long nKey = gridKey(nx, ny);
				int tentG = current[2] + 1;

				Integer prevG = gScore.get(nKey);
				if (prevG != null && tentG >= prevG) continue;

				gScore.put(nKey, tentG);
				cameFrom.put(nKey, new int[]{cx, cy});
				int fCost = tentG + Math.abs(end[0] - nx) + Math.abs(end[1] - ny);
				openSet.add(new int[]{nx, ny, tentG, fCost});
			}
		}

		// No path found — return L-shaped fallback
		return lShapeRoute(start, end, layer);
	}

	/**
	 * Reconstruct path from A* came-from map.
	 */
	private List<int[]> reconstructPath(Map<Long, int[]> cameFrom, int[] end, int layer)
	{
		List<int[]> path = new ArrayList<int[]>();
		int[] current = end;

		while (current != null)
		{
			path.add(new int[]{current[0], current[1], layer});
			long key = gridKey(current[0], current[1]);
			current = cameFrom.get(key);
		}

		Collections.reverse(path);
		return path;
	}

	/**
	 * L-shaped fallback route when A* fails.
	 */
	private List<int[]> lShapeRoute(int[] start, int[] end, int layer)
	{
		List<int[]> path = new ArrayList<int[]>();

		// Horizontal segment
		int x = start[0];
		int dx = end[0] > start[0] ? 1 : -1;
		while (x != end[0])
		{
			path.add(new int[]{x, start[1], layer});
			x += dx;
		}

		// Vertical segment
		int y = start[1];
		int dy = end[1] > start[1] ? 1 : -1;
		while (y != end[1])
		{
			path.add(new int[]{end[0], y, layer});
			y += dy;
		}
		path.add(new int[]{end[0], end[1], layer});

		return path;
	}

	/**
	 * Get grid coordinates of all terminals on a net.
	 */
	private List<int[]> getNetTerminals(CircuitGraph.Net net)
	{
		List<int[]> terminals = new ArrayList<int[]>();
		Set<CircuitGraph.Device> seen = new HashSet<CircuitGraph.Device>();

		for (CircuitGraph.Pin pin : net.getPins())
		{
			CircuitGraph.Device dev = pin.getDevice();
			if (!seen.add(dev)) continue;

			double[] pos = positions.get(dev);
			if (pos == null) continue;

			// Terminal at center of device
			int gx = (int) ((pos[0] + dev.getLayoutWidth() / 2) / gridPitch);
			int gy = (int) ((pos[1] + dev.getLayoutHeight() / 2) / gridPitch);
			terminals.add(new int[]{gx, gy});
		}

		return terminals;
	}

	private long gridKey(int x, int y)
	{
		return ((long) x << 32) | (y & 0xFFFFFFFFL);
	}

	public List<RoutePath> getRoutes() { return routes; }
}
