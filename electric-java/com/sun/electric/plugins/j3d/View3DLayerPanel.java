/* -*- tab-width: 4 -*-
 *
 * Electric(tm) VLSI Design System
 *
 * File: View3DLayerPanel.java
 *
 * Copyright (c) 2024, Static Free Software. All rights reserved.
 *
 * Electric(tm) is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 */
package com.sun.electric.plugins.j3d;

import com.sun.electric.database.geometry.EGraphics;
import com.sun.electric.database.geometry.Poly;
import com.sun.electric.database.hierarchy.Cell;
import com.sun.electric.database.topology.ArcInst;
import com.sun.electric.database.topology.NodeInst;
import com.sun.electric.technology.Layer;
import com.sun.electric.technology.PrimitiveNode;
import com.sun.electric.technology.Technology;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.util.*;
import java.util.List;

/**
 * Pure Java2D isometric 3D layer stack visualization.
 * No GPU/JavaFX/Java3D required — works everywhere.
 *
 * Uses isometric projection to render IC layers as colored
 * parallelograms stacked at their technology Z distances.
 */
public class View3DLayerPanel extends JPanel
{
    private static final double Z_SCALE = 8.0;
    private static final double ISO_ANGLE = Math.toRadians(30);
    private static final double COS_A = Math.cos(ISO_ANGLE);
    private static final double SIN_A = Math.sin(ISO_ANGLE);

    private final Cell cell;
    private final List<LayerBox> layerBoxes = new ArrayList<>();

    // View transforms
    private double viewScale = 1.0;
    private double viewOffsetX = 0, viewOffsetY = 0;
    private double rotationAngle = 45.0; // degrees around vertical axis
    private boolean needsAutoFit = true;

    // Mouse state
    private int lastMouseX, lastMouseY;
    private boolean dragging = false;

    // Bounds
    private double dataMinX, dataMaxX, dataMinY, dataMaxY, dataMinZ, dataMaxZ;

    /** Pre-extracted polygon data */
    private static class LayerBox implements Comparable<LayerBox>
    {
        final double x, y, w, h;       // Electric coordinates
        final double z, thickness;     // Layer Z
        final Color color;
        final String layerName;
        double sortKey;                // For painter's algorithm

        LayerBox(double x, double y, double w, double h, double z, double thickness,
                 Color color, String layerName)
        {
            this.x = x; this.y = y; this.w = w; this.h = h;
            this.z = z; this.thickness = thickness;
            this.color = color; this.layerName = layerName;
        }

        @Override
        public int compareTo(LayerBox o)
        {
            return Double.compare(this.sortKey, o.sortKey);
        }
    }

    public View3DLayerPanel(Cell cellToView)
    {
        this.cell = cellToView;
        setPreferredSize(new Dimension(800, 600));
        setBackground(new Color(30, 30, 46));

        // Extract geometry on current thread (has DB access)
        extractGeometry();

        System.out.println("View3DLayerPanel: " + layerBoxes.size() + " layer polygons from '" +
            cell.noLibDescribe() + "'");

        // Mouse handlers
        addMouseListener(new MouseAdapter()
        {
            @Override
            public void mousePressed(MouseEvent e)
            {
                lastMouseX = e.getX();
                lastMouseY = e.getY();
                dragging = true;
            }

            @Override
            public void mouseReleased(MouseEvent e) { dragging = false; }
        });

        addMouseMotionListener(new MouseMotionAdapter()
        {
            @Override
            public void mouseDragged(MouseEvent e)
            {
                int dx = e.getX() - lastMouseX;
                int dy = e.getY() - lastMouseY;
                lastMouseX = e.getX();
                lastMouseY = e.getY();

                if (SwingUtilities.isLeftMouseButton(e))
                {
                    // Rotate
                    rotationAngle += dx * 0.5;
                    repaint();
                }
                else if (SwingUtilities.isRightMouseButton(e))
                {
                    // Pan
                    viewOffsetX += dx;
                    viewOffsetY += dy;
                    repaint();
                }
            }
        });

        addMouseWheelListener(e ->
        {
            double factor = e.getWheelRotation() < 0 ? 1.1 : 0.9;
            viewScale *= factor;
            if (viewScale < 0.01) viewScale = 0.01;
            if (viewScale > 100) viewScale = 100;
            repaint();
        });

        // Keyboard
        setFocusable(true);
        addKeyListener(new KeyAdapter()
        {
            @Override
            public void keyPressed(KeyEvent e)
            {
                if (e.getKeyCode() == KeyEvent.VK_R)
                {
                    rotationAngle = 45;
                    needsAutoFit = true;
                    repaint();
                }
                else if (e.getKeyCode() == KeyEvent.VK_W)
                {
                    // Toggle wireframe - not applicable in 2D mode
                }
            }
        });
    }

    private void extractGeometry()
    {
        if (cell == null) return;
        Technology tech = cell.getTechnology();
        if (tech == null) return;

        dataMinX = Double.MAX_VALUE; dataMaxX = -Double.MAX_VALUE;
        dataMinY = Double.MAX_VALUE; dataMaxY = -Double.MAX_VALUE;
        dataMinZ = Double.MAX_VALUE; dataMaxZ = -Double.MAX_VALUE;

        // Nodes
        for (Iterator<NodeInst> it = cell.getNodes(); it.hasNext(); )
        {
            NodeInst ni = it.next();
            if (ni.isCellInstance()) continue;
            if (ni.getProto() instanceof PrimitiveNode)
            {
                PrimitiveNode pn = (PrimitiveNode) ni.getProto();
                if (pn.isNotUsed() || pn.getFunction() == PrimitiveNode.Function.PIN) continue;
            }
            try
            {
                Poly[] polys = ni.getProto().getTechnology().getShapeOfNode(ni);
                if (polys != null)
                    for (Poly p : polys) extractPoly(p);
            }
            catch (Exception e) { /* skip */ }
        }

        // Arcs
        for (Iterator<ArcInst> it = cell.getArcs(); it.hasNext(); )
        {
            ArcInst ai = it.next();
            try
            {
                Poly[] polys = ai.getProto().getTechnology().getShapeOfArc(ai);
                if (polys != null)
                    for (Poly p : polys) extractPoly(p);
            }
            catch (Exception e) { /* skip */ }
        }
    }

    private void extractPoly(Poly poly)
    {
        if (poly == null) return;
        Layer layer = poly.getLayer();
        if (layer == null) return;

        Rectangle2D b = poly.getBounds2D();
        double w = b.getWidth(), h = b.getHeight();
        if (w <= 0 && h <= 0) return;
        if (w < 0.1) w = 0.1;
        if (h < 0.1) h = 0.1;

        double thickness = layer.getThickness();
        if (thickness <= 0) thickness = 1.0;
        double distance = layer.getDistance();

        // Get color
        Color color = Color.GRAY;
        EGraphics graphics = layer.getGraphics();
        if (graphics != null)
        {
            java.awt.Color c = graphics.getColor();
            if (c != null) color = new Color(c.getRed(), c.getGreen(), c.getBlue(), 180);
        }

        double cx = b.getCenterX(), cy = b.getCenterY();
        layerBoxes.add(new LayerBox(cx, cy, w, h, distance, thickness, color, layer.getName()));

        dataMinX = Math.min(dataMinX, cx - w / 2);
        dataMaxX = Math.max(dataMaxX, cx + w / 2);
        dataMinY = Math.min(dataMinY, cy - h / 2);
        dataMaxY = Math.max(dataMaxY, cy + h / 2);
        dataMinZ = Math.min(dataMinZ, distance);
        dataMaxZ = Math.max(dataMaxZ, distance + thickness);
    }

    private void autoFit()
    {
        if (layerBoxes.isEmpty()) return;
        double spanX = dataMaxX - dataMinX;
        double spanY = dataMaxY - dataMinY;
        double maxSpan = Math.max(spanX, spanY);
        if (maxSpan <= 0) maxSpan = 100;
        viewScale = Math.min(getWidth(), getHeight()) / (maxSpan * 2.0);
        if (viewScale <= 0 || Double.isNaN(viewScale)) viewScale = 1.0;
        viewOffsetX = getWidth() / 2.0;
        viewOffsetY = getHeight() / 2.0;
    }

    /**
     * Project 3D point (x, y, z) to 2D screen using isometric projection.
     * x, y = Electric layout coords, z = layer height
     */
    private double[] projectIso(double x, double y, double z)
    {
        double radians = Math.toRadians(rotationAngle);
        double cosR = Math.cos(radians), sinR = Math.sin(radians);

        // Rotate around vertical (Z) axis
        double rx = x * cosR - y * sinR;
        double ry = x * sinR + y * cosR;

        // Center on data
        double dcx = (dataMinX + dataMaxX) / 2.0;
        double dcy = (dataMinY + dataMaxY) / 2.0;
        rx -= dcx * cosR - dcy * sinR;
        ry -= dcx * sinR + dcy * cosR;

        // Isometric projection
        double screenX = (rx - ry) * COS_A * viewScale + viewOffsetX;
        double screenY = (rx + ry) * SIN_A * viewScale - z * Z_SCALE * viewScale + viewOffsetY;

        return new double[] { screenX, screenY };
    }

    @Override
    protected void paintComponent(Graphics g)
    {
        super.paintComponent(g);

        // Defer autoFit until we actually have a size
        if (needsAutoFit && getWidth() > 0 && getHeight() > 0)
        {
            autoFit();
            needsAutoFit = false;
        }

        if (layerBoxes.isEmpty())
        {
            g.setColor(Color.WHITE);
            g.drawString("No geometry to display", 20, 30);
            return;
        }

        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Sort by depth (painter's algorithm)
        double radians = Math.toRadians(rotationAngle);
        double cosR = Math.cos(radians), sinR = Math.sin(radians);
        for (LayerBox lb : layerBoxes)
        {
            lb.sortKey = (lb.x * sinR + lb.y * cosR) - lb.z * 10;
        }
        List<LayerBox> sorted = new ArrayList<>(layerBoxes);
        Collections.sort(sorted);

        // Draw each layer box as isometric parallelogram
        for (LayerBox lb : sorted)
        {
            drawIsoBox(g2, lb);
        }

        // Info text
        g2.setColor(Color.WHITE);
        g2.setFont(new Font("SansSerif", Font.PLAIN, 12));
        g2.drawString("3D Layer View: " + cell.noLibDescribe() +
            " (" + layerBoxes.size() + " polygons)", 10, 20);
        g2.drawString("Left-drag: rotate | Right-drag: pan | Scroll: zoom | R: reset", 10, 36);

        g2.dispose();
    }

    /**
     * Draw a single layer box in isometric projection.
     * Draws top face + two visible side faces.
     */
    private void drawIsoBox(Graphics2D g2, LayerBox lb)
    {
        double hw = lb.w / 2, hh = lb.h / 2;
        double zBot = lb.z;
        double zTop = lb.z + lb.thickness;

        // 8 corners of the box
        double x0 = lb.x - hw, y0 = lb.y - hh;
        double x1 = lb.x + hw, y1 = lb.y + hh;

        // Project all 8 corners
        double[] p0b = projectIso(x0, y0, zBot);
        double[] p1b = projectIso(x1, y0, zBot);
        double[] p2b = projectIso(x1, y1, zBot);
        double[] p3b = projectIso(x0, y1, zBot);
        double[] p0t = projectIso(x0, y0, zTop);
        double[] p1t = projectIso(x1, y0, zTop);
        double[] p2t = projectIso(x1, y1, zTop);
        double[] p3t = projectIso(x0, y1, zTop);

        // TOP face
        int[] topX = { (int) p0t[0], (int) p1t[0], (int) p2t[0], (int) p3t[0] };
        int[] topY = { (int) p0t[1], (int) p1t[1], (int) p2t[1], (int) p3t[1] };
        g2.setColor(lb.color);
        g2.fillPolygon(topX, topY, 4);
        g2.setColor(darken(lb.color, 0.7));
        g2.drawPolygon(topX, topY, 4);

        // RIGHT side face (x1 edge)
        int[] rX = { (int) p1b[0], (int) p2b[0], (int) p2t[0], (int) p1t[0] };
        int[] rY = { (int) p1b[1], (int) p2b[1], (int) p2t[1], (int) p1t[1] };
        g2.setColor(darken(lb.color, 0.6));
        g2.fillPolygon(rX, rY, 4);
        g2.setColor(darken(lb.color, 0.4));
        g2.drawPolygon(rX, rY, 4);

        // FRONT side face (y1 edge)
        int[] fX = { (int) p2b[0], (int) p3b[0], (int) p3t[0], (int) p2t[0] };
        int[] fY = { (int) p2b[1], (int) p3b[1], (int) p3t[1], (int) p2t[1] };
        g2.setColor(darken(lb.color, 0.75));
        g2.fillPolygon(fX, fY, 4);
        g2.setColor(darken(lb.color, 0.5));
        g2.drawPolygon(fX, fY, 4);
    }

    private static Color darken(Color c, double factor)
    {
        return new Color(
            Math.max(0, (int)(c.getRed() * factor)),
            Math.max(0, (int)(c.getGreen() * factor)),
            Math.max(0, (int)(c.getBlue() * factor)),
            c.getAlpha());
    }
}
