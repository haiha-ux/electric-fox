/* -*- tab-width: 4 -*-
 *
 * Electric(tm) VLSI Design System
 *
 * File: LayoutCompactor.java
 * Analog Layout Synthesis Engine: 1D constraint-graph layout compaction
 *
 * Copyright (c) 2026, Static Free Software. All rights reserved.
 *
 * Electric(tm) is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 */
package com.sun.electric.tool.sc.analog;

import java.util.*;

/**
 * 1D constraint-graph layout compaction for area minimization.
 *
 * Algorithm:
 * 1. Build a directed constraint graph where nodes are layout elements
 *    and edges represent minimum spacing requirements (from DRC rules)
 * 2. Find longest path in the constraint graph using topological sort + DP
 * 3. The longest path determines the minimum chip dimension
 * 4. Apply in X then Y direction iteratively
 *
 * Based on: "1-D Compaction" algorithm from VLSI Physical Design textbooks.
 * Complexity: O(V + E) per direction, where V = elements, E = constraint edges.
 */
public class LayoutCompactor
{
    /** An element in the layout with position and size */
    public static class Element
    {
        final String name;
        double x, y, width, height;
        int id; // index in element list

        public Element(String name, double x, double y, double w, double h)
        {
            this.name = name;
            this.x = x; this.y = y;
            this.width = w; this.height = h;
        }
    }

    /** A spacing constraint between two elements */
    private static class Constraint
    {
        final int from, to;       // element indices
        final double minSpacing;  // minimum distance from right edge of 'from' to left edge of 'to'

        Constraint(int from, int to, double minSpacing)
        {
            this.from = from; this.to = to;
            this.minSpacing = minSpacing;
        }
    }

    private final List<Element> elements;
    private final double defaultSpacing;

    public LayoutCompactor(double defaultSpacing)
    {
        this.elements = new ArrayList<>();
        this.defaultSpacing = defaultSpacing;
    }

    public Element addElement(String name, double x, double y, double w, double h)
    {
        Element e = new Element(name, x, y, w, h);
        e.id = elements.size();
        elements.add(e);
        return e;
    }

    /**
     * Compact layout in both X and Y directions.
     * Returns the area reduction percentage.
     */
    public double compact()
    {
        if (elements.size() < 2) return 0;

        double oldArea = getBoundingArea();
        compactX();
        compactY();
        double newArea = getBoundingArea();

        double reduction = oldArea > 0 ? (1.0 - newArea / oldArea) * 100 : 0;
        if (reduction > 0)
        {
            System.out.println("  ALSE Compaction: area " + String.format("%.1f", oldArea) +
                " -> " + String.format("%.1f", newArea) +
                " (" + String.format("%.1f%%", reduction) + " reduction)");
        }
        return reduction;
    }

    /**
     * Compact in X direction: push elements as far left as possible
     * while maintaining minimum spacing constraints.
     */
    private void compactX()
    {
        // Build constraint graph for X direction
        List<Constraint> constraints = buildXConstraints();

        // Topological sort by current X position
        List<Element> sorted = new ArrayList<>(elements);
        sorted.sort(Comparator.comparingDouble(e -> e.x));

        // Longest path computation (forward pass)
        double[] earliest = new double[elements.size()];
        Arrays.fill(earliest, 0);

        for (Element e : sorted)
        {
            for (Constraint c : constraints)
            {
                if (c.to == e.id)
                {
                    double required = earliest[c.from] + elements.get(c.from).width + c.minSpacing;
                    if (required > earliest[e.id])
                        earliest[e.id] = required;
                }
            }
        }

        // Apply compacted positions
        for (Element e : elements)
            e.x = earliest[e.id];
    }

    /**
     * Compact in Y direction: push elements as far down as possible
     * while maintaining minimum spacing constraints.
     */
    private void compactY()
    {
        List<Constraint> constraints = buildYConstraints();

        List<Element> sorted = new ArrayList<>(elements);
        sorted.sort(Comparator.comparingDouble(e -> e.y));

        double[] earliest = new double[elements.size()];
        Arrays.fill(earliest, 0);

        for (Element e : sorted)
        {
            for (Constraint c : constraints)
            {
                if (c.to == e.id)
                {
                    double required = earliest[c.from] + elements.get(c.from).height + c.minSpacing;
                    if (required > earliest[e.id])
                        earliest[e.id] = required;
                }
            }
        }

        for (Element e : elements)
            e.y = earliest[e.id];
    }

    /**
     * Build X-direction constraints: for every pair of elements that
     * overlap in Y, add a left-to-right spacing constraint.
     */
    private List<Constraint> buildXConstraints()
    {
        List<Constraint> constraints = new ArrayList<>();

        for (int i = 0; i < elements.size(); i++)
        {
            Element a = elements.get(i);
            for (int j = i + 1; j < elements.size(); j++)
            {
                Element b = elements.get(j);

                // Check Y overlap
                if (a.y + a.height <= b.y || b.y + b.height <= a.y) continue;

                // Elements overlap in Y — need X spacing constraint
                if (a.x <= b.x)
                    constraints.add(new Constraint(i, j, defaultSpacing));
                else
                    constraints.add(new Constraint(j, i, defaultSpacing));
            }
        }
        return constraints;
    }

    /**
     * Build Y-direction constraints: for every pair of elements that
     * overlap in X, add a bottom-to-top spacing constraint.
     */
    private List<Constraint> buildYConstraints()
    {
        List<Constraint> constraints = new ArrayList<>();

        for (int i = 0; i < elements.size(); i++)
        {
            Element a = elements.get(i);
            for (int j = i + 1; j < elements.size(); j++)
            {
                Element b = elements.get(j);

                // Check X overlap
                if (a.x + a.width <= b.x || b.x + b.width <= a.x) continue;

                // Elements overlap in X — need Y spacing constraint
                if (a.y <= b.y)
                    constraints.add(new Constraint(i, j, defaultSpacing));
                else
                    constraints.add(new Constraint(j, i, defaultSpacing));
            }
        }
        return constraints;
    }

    private double getBoundingArea()
    {
        if (elements.isEmpty()) return 0;
        double maxX = 0, maxY = 0;
        for (Element e : elements)
        {
            double right = e.x + e.width;
            double top = e.y + e.height;
            if (right > maxX) maxX = right;
            if (top > maxY) maxY = top;
        }
        return maxX * maxY;
    }

    /** Get compacted positions as a map of element name -> [x, y] */
    public Map<String, double[]> getPositions()
    {
        Map<String, double[]> result = new LinkedHashMap<>();
        for (Element e : elements)
            result.put(e.name, new double[]{e.x, e.y});
        return result;
    }
}
