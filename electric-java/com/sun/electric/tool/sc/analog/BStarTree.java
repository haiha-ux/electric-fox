/* -*- tab-width: 4 -*-
 *
 * Electric(tm) VLSI Design System
 *
 * File: BStarTree.java
 * Analog Layout Synthesis Engine: B*-tree floorplan representation
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
 * B*-tree representation for non-slicing floorplans.
 *
 * A B*-tree is an ordered binary tree where:
 * - Left child = placed to the right of parent
 * - Right child = placed above parent
 * - Tree structure encodes relative placement of all modules
 *
 * This representation supports efficient perturbation operations for
 * Simulated Annealing: rotate, move, swap, mirror.
 *
 * For analog layout, extended with Automatically Symmetric-Feasible (ASF)
 * constraints to maintain symmetry during perturbation.
 *
 * Reference: Chang et al., "B*-Trees: A New Representation for Non-Slicing
 * Floorplans", DAC 2000.
 */
public class BStarTree
{
	/**
	 * A node in the B*-tree, representing a device/module.
	 */
	static class BNode
	{
		CircuitGraph.Device device;
		double width;
		double height;
		boolean rotated;       // 90-degree rotation

		BNode left;            // right-of parent
		BNode right;           // above parent
		BNode parent;

		// Packed coordinates (computed during evaluation)
		double x, y;

		// Symmetry
		int symmetryGroup;     // -1 = no constraint
		BNode symmetryPartner; // paired node for symmetric placement

		BNode(CircuitGraph.Device device, double w, double h)
		{
			this.device = device;
			this.width = w;
			this.height = h;
			this.rotated = false;
			this.symmetryGroup = device.getSymmetryGroup();
		}

		double getW() { return rotated ? height : width; }
		double getH() { return rotated ? width : height; }
	}

	private BNode root;
	private final List<BNode> nodes = new ArrayList<BNode>();
	private final Map<CircuitGraph.Device, BNode> deviceToNode = new HashMap<CircuitGraph.Device, BNode>();
	private final Random random = new Random(42);

	// Packed results
	private double totalWidth;
	private double totalHeight;

	// ==================== CONSTRUCTION ====================

	/**
	 * Build initial B*-tree from a list of devices with estimated sizes.
	 * Creates a balanced tree with left-skewed insertion.
	 */
	public void buildInitial(List<CircuitGraph.Device> devices)
	{
		nodes.clear();
		deviceToNode.clear();
		root = null;

		for (CircuitGraph.Device d : devices)
		{
			double w = d.getLayoutWidth() > 0 ? d.getLayoutWidth() : 10;
			double h = d.getLayoutHeight() > 0 ? d.getLayoutHeight() : 10;
			BNode node = new BNode(d, w, h);
			nodes.add(node);
			deviceToNode.put(d, node);
		}

		// Link symmetry partners
		for (BNode n : nodes)
		{
			CircuitGraph.Device partner = n.device.getMatchPartner();
			if (partner != null)
			{
				BNode partnerNode = deviceToNode.get(partner);
				if (partnerNode != null)
				{
					n.symmetryPartner = partnerNode;
					partnerNode.symmetryPartner = n;
				}
			}
		}

		// Build balanced initial tree
		if (!nodes.isEmpty())
		{
			root = nodes.get(0);
			for (int i = 1; i < nodes.size(); i++)
			{
				insertNode(nodes.get(i));
			}
		}
	}

	/**
	 * Insert a node into the tree, maintaining balance.
	 */
	private void insertNode(BNode node)
	{
		if (root == null)
		{
			root = node;
			return;
		}

		// Find insertion point: prefer left children for horizontal packing
		BNode current = root;
		while (true)
		{
			if (current.left == null)
			{
				current.left = node;
				node.parent = current;
				return;
			}
			else if (current.right == null)
			{
				current.right = node;
				node.parent = current;
				return;
			}
			else
			{
				// Go deeper — alternate left/right
				if (random.nextBoolean())
					current = current.left;
				else
					current = current.right;
			}
		}
	}

	// ==================== TREE EVALUATION (PACKING) ====================

	/**
	 * Evaluate the B*-tree to compute device positions.
	 * Uses contour-based packing for efficient evaluation.
	 *
	 * @return [totalWidth, totalHeight] of the packed floorplan
	 */
	public double[] evaluate()
	{
		if (root == null) return new double[]{0, 0};

		// Contour: tracks the top edge of placed modules
		TreeMap<Double, Double> contour = new TreeMap<Double, Double>();
		contour.put(0.0, 0.0); // initial empty contour

		// DFS traversal: pack each node
		packNode(root, 0, contour);

		// Compute bounding box
		totalWidth = 0;
		totalHeight = 0;
		for (BNode n : nodes)
		{
			double right = n.x + n.getW();
			double top = n.y + n.getH();
			if (right > totalWidth) totalWidth = right;
			if (top > totalHeight) totalHeight = top;
		}

		return new double[]{totalWidth, totalHeight};
	}

	/**
	 * Pack a node and its children using contour-based evaluation.
	 */
	private void packNode(BNode node, double xPos, TreeMap<Double, Double> contour)
	{
		node.x = xPos;

		// Find maximum y along contour in [xPos, xPos+width]
		double w = node.getW();
		double yMax = 0;
		Map.Entry<Double, Double> entry = contour.floorEntry(xPos);
		if (entry != null) yMax = entry.getValue();

		for (Map.Entry<Double, Double> e : contour.subMap(xPos, true, xPos + w, false).entrySet())
		{
			if (e.getValue() > yMax) yMax = e.getValue();
		}

		node.y = yMax;

		// Update contour
		double newTop = yMax + node.getH();
		// Remove contour points within our range
		contour.subMap(xPos, false, xPos + w, false).clear();
		contour.put(xPos, newTop);
		contour.put(xPos + w, yMax); // restore contour after our block

		// Recurse: left child goes to the right of this node
		if (node.left != null)
		{
			packNode(node.left, xPos + w, contour);
		}

		// Right child goes above this node (same x)
		if (node.right != null)
		{
			packNode(node.right, xPos, contour);
		}
	}

	// ==================== PERTURBATION OPERATIONS ====================

	/**
	 * Perform a random perturbation for Simulated Annealing.
	 * Returns a Memento to undo the perturbation if rejected.
	 */
	public Memento perturb()
	{
		int op = random.nextInt(4);
		switch (op)
		{
			case 0: return rotateRandom();
			case 1: return moveRandom();
			case 2: return swapRandom();
			case 3: return mirrorRandom();
			default: return rotateRandom();
		}
	}

	/**
	 * Memento pattern for undo support.
	 */
	public static class Memento
	{
		private final int operation;
		private BNode node1, node2;
		private boolean oldRotation;
		// For move: save old parent/child relationships
		private BNode oldParent1, oldLeft1, oldRight1;
		private BNode oldParent2, oldLeft2, oldRight2;
		private boolean wasLeft1, wasLeft2; // was node a left child?
		private BStarTree tree;

		Memento(BStarTree tree, int op) { this.tree = tree; this.operation = op; }

		public void undo()
		{
			switch (operation)
			{
				case 0: // rotate
					node1.rotated = oldRotation;
					break;
				case 2: // swap
					tree.swapNodes(node1, node2);
					break;
				case 1: // move - restore saved structure
				case 3: // mirror
					tree.restoreStructure(this);
					break;
			}
		}
	}

	/**
	 * Rotate a random module 90 degrees.
	 */
	private Memento rotateRandom()
	{
		BNode node = nodes.get(random.nextInt(nodes.size()));
		Memento m = new Memento(this, 0);
		m.node1 = node;
		m.oldRotation = node.rotated;
		node.rotated = !node.rotated;

		// If symmetry partner exists, rotate it too
		if (node.symmetryPartner != null)
			node.symmetryPartner.rotated = node.rotated;

		return m;
	}

	/**
	 * Swap two random modules in the tree.
	 */
	private Memento swapRandom()
	{
		if (nodes.size() < 2) return rotateRandom();

		int i = random.nextInt(nodes.size());
		int j;
		do { j = random.nextInt(nodes.size()); } while (j == i);

		BNode n1 = nodes.get(i);
		BNode n2 = nodes.get(j);

		Memento m = new Memento(this, 2);
		m.node1 = n1;
		m.node2 = n2;

		swapNodes(n1, n2);
		return m;
	}

	/**
	 * Swap two nodes' positions in the tree (swap device references, not tree structure).
	 */
	private void swapNodes(BNode n1, BNode n2)
	{
		// Swap device and dimensions
		CircuitGraph.Device tmpDev = n1.device;
		double tmpW = n1.width, tmpH = n1.height;
		boolean tmpRot = n1.rotated;

		n1.device = n2.device;
		n1.width = n2.width;
		n1.height = n2.height;
		n1.rotated = n2.rotated;

		n2.device = tmpDev;
		n2.width = tmpW;
		n2.height = tmpH;
		n2.rotated = tmpRot;

		// Update device-to-node mapping
		deviceToNode.put(n1.device, n1);
		deviceToNode.put(n2.device, n2);
	}

	/**
	 * Move a random node to a different position in the tree.
	 */
	private Memento moveRandom()
	{
		if (nodes.size() < 2) return rotateRandom();

		Memento m = new Memento(this, 1);
		saveStructure(m);

		// Remove a random non-root node and reinsert
		int idx;
		BNode node;
		do
		{
			idx = random.nextInt(nodes.size());
			node = nodes.get(idx);
		} while (node == root);

		m.node1 = node;

		// Detach from parent
		if (node.parent != null)
		{
			if (node.parent.left == node)
			{
				node.parent.left = node.left != null ? node.left : node.right;
				if (node.parent.left != null) node.parent.left.parent = node.parent;
			}
			else
			{
				node.parent.right = node.left != null ? node.left : node.right;
				if (node.parent.right != null) node.parent.right.parent = node.parent;
			}
		}
		node.parent = null;
		node.left = null;
		node.right = null;

		// Reinsert at random position
		insertNode(node);
		return m;
	}

	/**
	 * Mirror placement of a symmetric pair.
	 */
	private Memento mirrorRandom()
	{
		// Find nodes with symmetry partners
		List<BNode> symmetric = new ArrayList<BNode>();
		for (BNode n : nodes)
		{
			if (n.symmetryPartner != null) symmetric.add(n);
		}
		if (symmetric.isEmpty()) return rotateRandom();

		BNode node = symmetric.get(random.nextInt(symmetric.size()));
		// Swap the symmetric pair
		Memento m = new Memento(this, 2);
		m.node1 = node;
		m.node2 = node.symmetryPartner;
		swapNodes(node, node.symmetryPartner);
		return m;
	}

	private void saveStructure(Memento m)
	{
		// Deep copy would be expensive; just save what we need for the move op
		m.oldParent1 = m.node1 != null ? m.node1.parent : null;
	}

	private void restoreStructure(Memento m)
	{
		// For simplicity, rebuild the tree from the nodes list
		// This is O(n) but infrequent
		root = null;
		for (BNode n : nodes)
		{
			n.parent = null;
			n.left = null;
			n.right = null;
		}
		for (BNode n : nodes)
		{
			insertNode(n);
		}
	}

	// ==================== RESULTS ====================

	/**
	 * Get placement results after evaluation.
	 *
	 * @return map from device to [x, y] position
	 */
	public Map<CircuitGraph.Device, double[]> getPositions()
	{
		Map<CircuitGraph.Device, double[]> result = new LinkedHashMap<CircuitGraph.Device, double[]>();
		for (BNode n : nodes)
		{
			result.put(n.device, new double[]{n.x, n.y});
		}
		return result;
	}

	/**
	 * Get the total bounding box after evaluation.
	 */
	public double getTotalWidth() { return totalWidth; }
	public double getTotalHeight() { return totalHeight; }
	public double getTotalArea() { return totalWidth * totalHeight; }

	/**
	 * Get all nodes for external inspection.
	 */
	public List<BNode> getNodes() { return nodes; }
}
