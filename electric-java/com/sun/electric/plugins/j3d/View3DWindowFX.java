/* -*- tab-width: 4 -*-
 *
 * Electric(tm) VLSI Design System
 *
 * File: View3DWindowFX.java
 *
 * Copyright (c) 2024, Static Free Software. All rights reserved.
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
package com.sun.electric.plugins.j3d;

import com.sun.electric.database.geometry.EGraphics;
import com.sun.electric.database.geometry.Poly;
import com.sun.electric.database.hierarchy.Cell;
import com.sun.electric.database.topology.ArcInst;
import com.sun.electric.database.topology.NodeInst;
import com.sun.electric.technology.Layer;
import com.sun.electric.technology.PrimitiveNode;
import com.sun.electric.technology.Technology;

import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.scene.AmbientLight;
import javafx.scene.Group;
import javafx.scene.PerspectiveCamera;
import javafx.scene.PointLight;
import javafx.scene.Scene;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SubScene;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Box;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.DrawMode;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Translate;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Modern JavaFX 3D replacement for the Java3D-based View3DWindow.
 * Displays a 3D layer stack visualization of an Electric cell using
 * JavaFX 3D shapes (Box) embedded in a Swing JPanel via JFXPanel.
 *
 * THREADING: All Electric database access happens on the Swing/calling
 * thread (constructor). Only pure JavaFX scene graph operations run on
 * the FX Application Thread.
 */
public class View3DWindowFX extends JPanel
{
    private static final double Z_SCALE = 5.0;
    private static final double DEFAULT_ROTATE_X = 30.0;
    private static final double DEFAULT_ROTATE_Y = 30.0;
    private static final double DEFAULT_THICKNESS = 1.0;
    private static final double LAYER_OPACITY = 0.7;

    private final Cell cell;
    private JFXPanel jfxPanel;
    private boolean fxAvailable;
    private Group contentGroup;
    private PerspectiveCamera camera;
    private Rotate rotateX, rotateY;
    private Translate translate;
    private double mousePosX, mousePosY, mouseOldX, mouseOldY;

    // Pre-extracted geometry data (built on Swing thread, consumed on FX thread)
    private final List<BoxData> extractedBoxes = new ArrayList<>();
    private double bndMinX = Double.MAX_VALUE, bndMaxX = -Double.MAX_VALUE;
    private double bndMinY = Double.MAX_VALUE, bndMaxY = -Double.MAX_VALUE;
    private double bndMinZ = Double.MAX_VALUE, bndMaxZ = -Double.MAX_VALUE;
    private String cellName;

    /** Intermediate data structure: geometry extracted on Swing thread */
    private static class BoxData
    {
        final double width, height, depth;
        final double tx, ty, tz;
        final double red, green, blue, opacity;

        BoxData(double w, double h, double d, double tx, double ty, double tz,
                double r, double g, double b, double opacity)
        {
            this.width = w; this.height = h; this.depth = d;
            this.tx = tx; this.ty = ty; this.tz = tz;
            this.red = r; this.green = g; this.blue = b;
            this.opacity = opacity;
        }
    }

    /**
     * Creates a new 3D JavaFX viewer for the given cell.
     * MUST be called on the Swing EDT (or any thread with Electric DB access).
     */
    public View3DWindowFX(Cell cellToView)
    {
        super(new BorderLayout());
        this.cell = cellToView;
        setPreferredSize(new Dimension(800, 600));

        // Step 1: Extract ALL geometry from Electric database on THIS thread
        cellName = cellToView.noLibDescribe();
        extractGeometry(cellToView);
        System.out.println("View3DWindowFX: Extracted " + extractedBoxes.size() +
            " boxes from cell '" + cellName + "'");

        if (bndMinX <= bndMaxX)
        {
            System.out.println("View3DWindowFX: Bounds X[" + fmt(bndMinX) + ".." + fmt(bndMaxX) +
                "] Y[" + fmt(bndMinY) + ".." + fmt(bndMaxY) + "] Z[" + fmt(bndMinZ) + ".." + fmt(bndMaxZ) + "]");
        }

        // Step 2: Initialize JavaFX panel
        fxAvailable = false;
        try
        {
            jfxPanel = new JFXPanel();
            fxAvailable = true;
        }
        catch (Throwable t)
        {
            add(new JLabel("JavaFX is not available: " + t.getMessage(), SwingConstants.CENTER),
                BorderLayout.CENTER);
            System.out.println("View3DWindowFX: JavaFX not available - " + t.getMessage());
            return;
        }

        add(jfxPanel, BorderLayout.CENTER);

        // Step 3: Build the JavaFX scene on the FX thread (no DB access needed)
        Platform.runLater(() -> buildFXScene());
    }

    // ==================== STEP 1: EXTRACT GEOMETRY (Swing Thread) ====================

    /**
     * Extract all polygon geometry from the cell. This accesses the Electric
     * database and MUST run on a thread with database access (Swing EDT).
     */
    private void extractGeometry(Cell cell)
    {
        if (cell == null) return;
        Technology tech = cell.getTechnology();
        if (tech == null)
        {
            System.out.println("View3DWindowFX: Cell has no technology");
            return;
        }

        int nodeCount = 0, arcCount = 0;

        // Process nodes
        for (Iterator<NodeInst> it = cell.getNodes(); it.hasNext(); )
        {
            NodeInst ni = it.next();
            if (ni.isCellInstance()) continue;
            if (ni.getProto() instanceof PrimitiveNode)
            {
                PrimitiveNode pn = (PrimitiveNode) ni.getProto();
                if (pn.isNotUsed()) continue;
                // Skip pins and other non-visual nodes
                if (pn.getFunction() == PrimitiveNode.Function.PIN) continue;
            }

            try
            {
                Technology nodeTech = ni.getProto().getTechnology();
                Poly[] polys = nodeTech.getShapeOfNode(ni);
                if (polys == null || polys.length == 0) continue;
                nodeCount++;
                for (Poly poly : polys) extractPoly(poly);
            }
            catch (Exception e) { /* skip */ }
        }

        // Process arcs
        for (Iterator<ArcInst> it = cell.getArcs(); it.hasNext(); )
        {
            ArcInst ai = it.next();
            try
            {
                Technology arcTech = ai.getProto().getTechnology();
                Poly[] polys = arcTech.getShapeOfArc(ai);
                if (polys == null || polys.length == 0) continue;
                arcCount++;
                for (Poly poly : polys) extractPoly(poly);
            }
            catch (Exception e) { /* skip */ }
        }

        System.out.println("View3DWindowFX: Processed " + nodeCount + " nodes, " +
            arcCount + " arcs");
    }

    /** Extract a single polygon into BoxData. */
    private void extractPoly(Poly poly)
    {
        if (poly == null) return;
        Layer layer = poly.getLayer();
        if (layer == null) return;

        Rectangle2D bounds = poly.getBounds2D();
        double w = bounds.getWidth();
        double d = bounds.getHeight();
        if (w <= 0 && d <= 0) return;

        // Layer Z positioning
        double thickness = layer.getThickness();
        if (thickness <= 0) thickness = DEFAULT_THICKNESS;
        double distance = layer.getDistance();
        double zCenter = -(distance + thickness / 2.0) * Z_SCALE;
        double h = thickness * Z_SCALE;

        if (w < 0.1) w = 0.1;
        if (d < 0.1) d = 0.1;

        double cx = bounds.getCenterX();
        double cy = bounds.getCenterY();

        // Get layer color
        double red = 0.5, green = 0.5, blue = 0.5;
        EGraphics graphics = layer.getGraphics();
        if (graphics != null)
        {
            java.awt.Color c = graphics.getColor();
            if (c != null)
            {
                red = c.getRed() / 255.0;
                green = c.getGreen() / 255.0;
                blue = c.getBlue() / 255.0;
            }
        }

        extractedBoxes.add(new BoxData(w, h, d, cx, zCenter, -cy, red, green, blue, LAYER_OPACITY));

        // Update bounds
        bndMinX = Math.min(bndMinX, cx - w / 2);
        bndMaxX = Math.max(bndMaxX, cx + w / 2);
        bndMinY = Math.min(bndMinY, zCenter - h / 2);
        bndMaxY = Math.max(bndMaxY, zCenter + h / 2);
        bndMinZ = Math.min(bndMinZ, -cy - d / 2);
        bndMaxZ = Math.max(bndMaxZ, -cy + d / 2);
    }

    // ==================== STEP 2: BUILD FX SCENE (FX Thread) ====================

    /**
     * Build the JavaFX 3D scene from pre-extracted geometry.
     * No Electric database access here — only JavaFX API calls.
     */
    private void buildFXScene()
    {
        Group root = new Group();
        contentGroup = new Group();

        // Rotation + translation transforms
        rotateX = new Rotate(DEFAULT_ROTATE_X, Rotate.X_AXIS);
        rotateY = new Rotate(DEFAULT_ROTATE_Y, Rotate.Y_AXIS);
        translate = new Translate(0, 0, 0);
        contentGroup.getTransforms().addAll(rotateX, rotateY, translate);

        // Create Box for each extracted polygon
        Map<Long, PhongMaterial> matCache = new HashMap<>();
        for (BoxData bd : extractedBoxes)
        {
            Box box = new Box(bd.width, bd.height, bd.depth);
            box.setTranslateX(bd.tx);
            box.setTranslateY(bd.ty);
            box.setTranslateZ(bd.tz);
            box.setCullFace(CullFace.BACK);
            box.setDrawMode(DrawMode.FILL);

            // Material (cache by color)
            long colorKey = ((long)(bd.red * 255) << 16) |
                            ((long)(bd.green * 255) << 8) |
                            (long)(bd.blue * 255);
            PhongMaterial mat = matCache.get(colorKey);
            if (mat == null)
            {
                Color color = Color.color(bd.red, bd.green, bd.blue, bd.opacity);
                mat = new PhongMaterial();
                mat.setDiffuseColor(color);
                mat.setSpecularColor(Color.color(
                    Math.min(1.0, bd.red * 1.3),
                    Math.min(1.0, bd.green * 1.3),
                    Math.min(1.0, bd.blue * 1.3)));
                mat.setSpecularPower(20.0);
                matCache.put(colorKey, mat);
            }
            box.setMaterial(mat);
            contentGroup.getChildren().add(box);
        }

        // Set rotation pivot to content center
        if (!extractedBoxes.isEmpty() && bndMinX <= bndMaxX)
        {
            double cx = (bndMinX + bndMaxX) / 2.0;
            double cy = (bndMinY + bndMaxY) / 2.0;
            double cz = (bndMinZ + bndMaxZ) / 2.0;
            rotateX.setPivotX(cx); rotateX.setPivotY(cy); rotateX.setPivotZ(cz);
            rotateY.setPivotX(cx); rotateY.setPivotY(cy); rotateY.setPivotZ(cz);
        }

        root.getChildren().add(contentGroup);

        // Lighting
        AmbientLight ambient = new AmbientLight(Color.color(0.4, 0.4, 0.4));
        root.getChildren().add(ambient);

        PointLight key = new PointLight(Color.WHITE);
        key.setTranslateX(200); key.setTranslateY(-300); key.setTranslateZ(-500);
        root.getChildren().add(key);

        PointLight fill = new PointLight(Color.color(0.6, 0.6, 0.7));
        fill.setTranslateX(-200); fill.setTranslateY(100); fill.setTranslateZ(-300);
        root.getChildren().add(fill);

        // SubScene
        SubScene subScene = new SubScene(root, 800, 600, true, SceneAntialiasing.BALANCED);
        subScene.setFill(Color.color(0.12, 0.12, 0.18));

        // Camera
        camera = new PerspectiveCamera(true);
        camera.setNearClip(0.1);
        camera.setFarClip(50000.0);
        camera.setFieldOfView(45.0);
        subScene.setCamera(camera);
        autoFitCamera();

        // Mouse handlers
        setupMouseHandlers(subScene);

        // Layout: StackPane so SubScene fills all available space
        StackPane pane = new StackPane(subScene);
        pane.setStyle("-fx-background-color: #1e1e2e;");
        subScene.widthProperty().bind(pane.widthProperty());
        subScene.heightProperty().bind(pane.heightProperty());

        Scene fxScene = new Scene(pane, 800, 600, true, SceneAntialiasing.BALANCED);
        fxScene.setFill(Color.color(0.12, 0.12, 0.18));
        jfxPanel.setScene(fxScene);

        System.out.println("View3DWindowFX: Scene built with " + extractedBoxes.size() + " boxes");
    }

    private void autoFitCamera()
    {
        if (bndMinX > bndMaxX)
        {
            camera.setTranslateZ(-500);
            System.out.println("View3DWindowFX: No geometry - empty scene");
            return;
        }

        double spanX = bndMaxX - bndMinX;
        double spanY = bndMaxY - bndMinY;
        double spanZ = bndMaxZ - bndMinZ;
        double maxSpan = Math.max(spanX, Math.max(spanY, spanZ));

        double distance = maxSpan * 2.5;
        if (distance < 100) distance = 100;
        if (distance > 20000) distance = 20000;

        double cx = (bndMinX + bndMaxX) / 2.0;
        double cy = (bndMinY + bndMaxY) / 2.0;

        camera.setTranslateX(cx);
        camera.setTranslateY(cy);
        camera.setTranslateZ(-distance);

        System.out.println("View3DWindowFX: Camera distance=" + fmt(distance) +
            " center=(" + fmt(cx) + "," + fmt(cy) + ")");
    }

    private void setupMouseHandlers(SubScene subScene)
    {
        subScene.setOnMousePressed((MouseEvent e) -> {
            mousePosX = e.getSceneX(); mousePosY = e.getSceneY();
            mouseOldX = mousePosX; mouseOldY = mousePosY;
        });

        subScene.setOnMouseDragged((MouseEvent e) -> {
            mouseOldX = mousePosX; mouseOldY = mousePosY;
            mousePosX = e.getSceneX(); mousePosY = e.getSceneY();
            double dx = mousePosX - mouseOldX, dy = mousePosY - mouseOldY;

            if (e.getButton() == MouseButton.PRIMARY) {
                double ax = rotateX.getAngle() + dy * 0.5;
                double ay = rotateY.getAngle() - dx * 0.5;
                if (ax > 90) ax = 90; if (ax < -90) ax = -90;
                rotateX.setAngle(ax); rotateY.setAngle(ay);
            } else if (e.getButton() == MouseButton.SECONDARY) {
                translate.setX(translate.getX() + dx * 0.5);
                translate.setY(translate.getY() + dy * 0.5);
            } else if (e.getButton() == MouseButton.MIDDLE) {
                camera.setTranslateZ(camera.getTranslateZ() + dy * 2.0);
            }
        });

        subScene.setOnScroll((ScrollEvent e) -> {
            double z = camera.getTranslateZ();
            double factor = z * 0.1;
            double nz = z + (e.getDeltaY() > 0 ? Math.abs(factor) : -Math.abs(factor));
            if (nz > -5) nz = -5; if (nz < -50000) nz = -50000;
            camera.setTranslateZ(nz);
        });
    }

    public Cell getCell() { return cell; }
    public boolean isFXAvailable() { return fxAvailable; }

    public void resetView() {
        if (!fxAvailable) return;
        Platform.runLater(() -> {
            rotateX.setAngle(DEFAULT_ROTATE_X); rotateY.setAngle(DEFAULT_ROTATE_Y);
            translate.setX(0); translate.setY(0);
            autoFitCamera();
        });
    }

    public void setWireframe(boolean wireframe) {
        if (!fxAvailable) return;
        DrawMode mode = wireframe ? DrawMode.LINE : DrawMode.FILL;
        Platform.runLater(() -> {
            for (javafx.scene.Node n : contentGroup.getChildren())
                if (n instanceof Box) ((Box) n).setDrawMode(mode);
        });
    }

    private static String fmt(double v) { return String.format("%.1f", v); }
}
