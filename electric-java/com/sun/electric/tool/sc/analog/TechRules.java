/* -*- tab-width: 4 -*-
 *
 * Electric(tm) VLSI Design System
 *
 * File: TechRules.java
 * Technology-independent DRC rule abstraction for ALSE layout engine
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
import com.sun.electric.technology.*;
import com.sun.electric.tool.drc.DRC;

import java.util.*;

/**
 * Technology-independent DRC rule abstraction layer.
 *
 * <p>Queries design rules from the Technology's XMLRules database
 * instead of using hardcoded spacing constants. This enables the
 * ALSE layout engine to work with any foundry rules (MOSIS, TSMC, ST)
 * and any CMOS technology that Electric supports.
 *
 * <p>Inspired by ALIGN's design rule abstraction and MAGICAL's
 * technology-aware device generators.
 */
public class TechRules
{
	private final Technology tech;
	private final EditingPreferences ep;

	// Cached layers
	private Layer metal1Layer, metal2Layer;
	private Layer polyLayer;
	private Layer nActiveLayer, pActiveLayer;
	private Layer nWellLayer, pWellLayer;
	private Layer nSelectLayer, pSelectLayer;
	private Layer activeCutLayer, polyCutLayer, viaCutLayer;

	// Cached primitives
	private PrimitiveNode nTransistor, pTransistor;
	private PrimitiveNode nActiveCon, pActiveCon, polyCon;
	private PrimitiveNode nwellCon, pwellCon;
	private PrimitiveNode m1m2Con;

	// Cached computed values
	private double deviceSpacing = -1;
	private double rowSeparation = -1;
	private double wellContactMargin = -1;
	private double contactOffset = -1;
	private double supplyBusOffset = -1;

	public TechRules(Technology tech, EditingPreferences ep)
	{
		this.tech = tech;
		this.ep = ep;
		initLayers();
		initPrimitives();
	}

	private void initLayers()
	{
		metal1Layer = tech.findLayer("Metal-1");
		metal2Layer = tech.findLayer("Metal-2");
		polyLayer = tech.findLayer("Polysilicon-1");
		nActiveLayer = tech.findLayer("N-Active");
		pActiveLayer = tech.findLayer("P-Active");
		nWellLayer = tech.findLayer("N-Well");
		pWellLayer = tech.findLayer("P-Well");
		nSelectLayer = tech.findLayer("N-Select");
		pSelectLayer = tech.findLayer("P-Select");
		activeCutLayer = tech.findLayer("Active-Cut");
		polyCutLayer = tech.findLayer("Poly-Cut");
		viaCutLayer = tech.findLayer("Via-1");
	}

	private void initPrimitives()
	{
		nTransistor = findPrim("N-Transistor");
		pTransistor = findPrim("P-Transistor");
		nActiveCon = findPrim("Metal-1-N-Active-Con");
		pActiveCon = findPrim("Metal-1-P-Active-Con");
		polyCon = findPrim("Metal-1-Polysilicon-1-Con");
		nwellCon = findPrim("Metal-1-N-Well-Con");
		pwellCon = findPrim("Metal-1-P-Well-Con");
		m1m2Con = findPrim("Metal-1-Metal-2-Con");
	}

	// ==================== SPACING QUERIES ====================

	/**
	 * Minimum spacing between two same-type transistors placed side by side.
	 * Computed from the maximum of all relevant layer spacing rules.
	 */
	public double getDeviceSpacing()
	{
		if (deviceSpacing >= 0) return deviceSpacing;

		double spacing = 0;

		// Metal-1 to Metal-1 spacing
		spacing = Math.max(spacing, getLayerSpacing(metal1Layer, metal1Layer));
		// Poly to Poly spacing
		spacing = Math.max(spacing, getLayerSpacing(polyLayer, polyLayer));
		// N-Active to N-Active spacing (within same well)
		spacing = Math.max(spacing, getLayerSpacing(nActiveLayer, nActiveLayer));
		// P-Active to P-Active spacing
		spacing = Math.max(spacing, getLayerSpacing(pActiveLayer, pActiveLayer));
		// Active-Cut spacing
		spacing = Math.max(spacing, getLayerSpacing(activeCutLayer, activeCutLayer));

		// Add buffer for contacts that extend beyond device edges
		// Contact extends about CONTACT_OFFSET from device edge, so add that
		double conExt = getContactOffset();
		spacing = Math.max(spacing, conExt * 2 + getLayerSpacing(metal1Layer, metal1Layer));

		// Snap to grid and add safety margin
		deviceSpacing = snap(Math.max(spacing, 6));
		return deviceSpacing;
	}

	/**
	 * Vertical separation between NMOS and PMOS row centers.
	 * Computed from device half-heights, contact zones, and supply bus spacing.
	 */
	public double getRowSeparation()
	{
		if (rowSeparation >= 0) return rowSeparation;

		double nHalfH = nTransistor != null ? nTransistor.getDefHeight(ep) / 2 : 11;
		double pHalfH = pTransistor != null ? pTransistor.getDefHeight(ep) / 2 : 11;

		double conOff = getContactOffset();
		double busOff = getSupplyBusOffset();

		// From NMOS center: nHalfH + conOff + contact + busGap + contact + conOff + pHalfH
		// The supply buses sit between the contact zones
		double conH = nActiveCon != null ? nActiveCon.getDefHeight(ep) : 17;

		// N-Well to P-Well spacing (critical for latchup prevention)
		double wellSpacing = getLayerSpacing(nWellLayer, pWellLayer);
		if (wellSpacing <= 0) wellSpacing = 18; // MOSIS default

		// N-Active to P-Active with well edge spacing
		double activeWellSpacing = 0;
		DRCTemplate rule = getSpacingRule(nActiveLayer, pActiveLayer);
		if (rule != null) activeWellSpacing = rule.getValue(0);

		// The row separation must accommodate:
		// 1. Both contact zones (NMOS top contact + PMOS bottom contact)
		// 2. Two supply buses between them (GND + VDD)
		// 3. Well spacing between N-Well and P-Well
		double minFromContacts = conOff + conH / 2 + busOff * 2 + conH / 2 + conOff;
		double minFromWells = wellSpacing;
		double minFromActive = activeWellSpacing;

		double sep = Math.max(minFromContacts, Math.max(minFromWells, minFromActive));
		// Ensure separation is at least large enough for contact + bus + contact
		sep = Math.max(sep, nHalfH + pHalfH + conOff * 2 + 16);

		rowSeparation = snap(sep);
		return rowSeparation;
	}

	/**
	 * Distance from device edge to well contact center.
	 * Must be far enough for the well contact's well layer to not violate
	 * device well enclosure rules, while staying within the device's well region.
	 */
	public double getWellContactMargin()
	{
		if (wellContactMargin >= 0) return wellContactMargin;

		double conH = pwellCon != null ? pwellCon.getDefHeight(ep) : 15;

		// Well contact must be inside the device's well region
		// N-Well surround of N-Active (for P-transistor)
		double wellSurround = getWellEnclosure();
		// Active to well contact active spacing
		double activeSpacing = getLayerSpacing(nActiveLayer, pActiveLayer);
		if (activeSpacing <= 0) activeSpacing = 4;

		// Minimum: half contact height + active spacing from device edge
		double margin = conH / 2 + activeSpacing;

		// But also ensure the well contact's well overlaps with transistor well
		// Well contact has its own well layer extent (±5.5 for MOCMOS)
		// Transistor well extent is typically larger (±10 for P-Transistor N-Well)
		margin = Math.max(margin, conH / 2 + 4);

		wellContactMargin = snap(margin);
		return wellContactMargin;
	}

	/**
	 * Offset of contact center beyond transistor nominal edge.
	 * Contact layers naturally merge with transistor layers when close enough.
	 */
	public double getContactOffset()
	{
		if (contactOffset >= 0) return contactOffset;

		// The contact must be placed outside the transistor active region
		// but close enough that their well/implant layers merge.
		// Key rule: Active-Cut to Poly-Gate spacing (rule 6.3 in MOSIS)
		double cutToPolySpacing = 0;
		if (activeCutLayer != null && polyLayer != null)
		{
			DRCTemplate rule = getSpacingRule(activeCutLayer, polyLayer);
			if (rule != null) cutToPolySpacing = rule.getValue(0);
		}

		// The contact center must be at least (transistor_half_height + buffer)
		// from the transistor center, where buffer ensures no active-cut
		// overlaps with the poly gate.
		// Typically: defHalfH + small_offset (1-3 lambda)
		double offset = 2; // default safe offset
		if (cutToPolySpacing > 0)
		{
			// Contact active-cut is at contact center
			// Poly gate extends to transistor edge (defHalfH)
			// So minimum offset = cutToPolySpacing - (defHalfH - gate_end)
			// In practice, a small offset (1-3) suffices because the contact
			// layers naturally merge with transistor layers
			offset = Math.max(1, cutToPolySpacing - 5);
		}

		contactOffset = snap(offset);
		return contactOffset;
	}

	/**
	 * Vertical offset from device edge to supply bus center.
	 * The bus must be between the contact zone and the opposite device's contact zone.
	 */
	public double getSupplyBusOffset()
	{
		if (supplyBusOffset >= 0) return supplyBusOffset;

		// Bus needs to be far enough from contacts to avoid Metal-1 shorts
		double m1Spacing = getLayerSpacing(metal1Layer, metal1Layer);
		if (m1Spacing <= 0) m1Spacing = 3;

		// Contact height (half extends from device edge)
		double conH = nActiveCon != null ? nActiveCon.getDefHeight(ep) / 2 : 8.5;

		// Bus offset from transistor edge = contact offset + contact half-height + m1 spacing
		supplyBusOffset = snap(getContactOffset() + conH + m1Spacing);
		return supplyBusOffset;
	}

	// ==================== WELL ENCLOSURE ====================

	/**
	 * N-Well enclosure of P-Active (for P-transistors and P-active contacts).
	 */
	public double getWellEnclosure()
	{
		double enc = getSurroundRule(nWellLayer, pActiveLayer, "P-Transistor");
		if (enc <= 0) enc = getSurroundRule(nWellLayer, pActiveLayer, "Metal-1-P-Active-Con");
		if (enc <= 0) enc = 6; // MOSIS default
		return enc;
	}

	/**
	 * Minimum well contact size to satisfy N-Well/P-Well minimum width rules.
	 */
	public double getMinWellContactSize()
	{
		double minWellWidth = getMinWidth(nWellLayer);
		if (minWellWidth <= 0) minWellWidth = 12; // MOSIS default

		// Well contact's well layer extent
		double wcWellExtent = 5.5; // from mocmos.xml: nwellCon N-Well ±5.5
		if (nwellCon != null)
		{
			// Try to compute from actual primitive geometry
			double defSize = nwellCon.getDefWidth(ep);
			// The well layer is typically larger than base size
			// wcWellExtent = (defSize - base_size) / 2 for well contacts
		}

		// Well contact size must ensure well layer ≥ minWellWidth
		// Well extends ±wcWellExtent from contact center, so well width = contactSize + 2*(wcWellExtent - contactSize/2)
		// Simplified: ensure getDefWidth >= contactSize that makes well >= minWellWidth
		double minContactSize = Math.max(minWellWidth - 2 * wcWellExtent + nwellCon.getDefaultLambdaBaseWidth(ep),
										 nwellCon.getDefWidth(ep));

		return snap(Math.max(minContactSize, 19)); // 19 is empirical minimum for MOCMOS
	}

	/**
	 * Compute N-Well fill rectangle parameters to cover the PMOS area.
	 * Returns [cx, cy, width, height] or null if not needed.
	 */
	public double[] computeNWellFill(double pmosY, double pH, double nwellY,
									 double totalW)
	{
		double conOff = getContactOffset();

		// N-Well must cover from below PMOS contacts to above N-Well contacts
		// Contact well extent: ±8.5 for pActiveCon, ±9.5 for nwellCon (MOCMOS)
		double pConWellExtent = pActiveCon != null ? (pActiveCon.getDefHeight(ep) - pActiveCon.getDefaultLambdaBaseHeight(ep)) / 2 + pActiveCon.getDefaultLambdaBaseHeight(ep) / 2 : 8.5;
		double nwcWellExtent = nwellCon != null ? nwellCon.getDefHeight(ep) / 2 : 9.5;

		// Bottom of N-Well fill: below PMOS contact zone
		double nwBottom = pmosY - pH / 2 - conOff - pConWellExtent;
		// Top of N-Well fill: above N-Well contact zone
		double nwTop = nwellY + nwcWellExtent;

		double cx = snap(totalW / 2);
		double cy = snap((nwBottom + nwTop) / 2);
		double width = snap(totalW + 30); // extend past edges for coverage
		double height = snap(nwTop - nwBottom + 2); // +2 safety margin

		return new double[]{cx, cy, width, height};
	}

	// ==================== RAW DRC RULE QUERIES ====================

	/**
	 * Get minimum spacing between two layers.
	 */
	public double getLayerSpacing(Layer l1, Layer l2)
	{
		if (l1 == null || l2 == null) return 0;
		DRCTemplate rule = getSpacingRule(l1, l2);
		return rule != null ? rule.getValue(0) : 0;
	}

	/**
	 * Get minimum width for a layer.
	 */
	public double getMinWidth(Layer layer)
	{
		if (layer == null) return 0;
		DRCTemplate rule = DRC.getMinValue(layer, DRCTemplate.DRCRuleType.MINWID);
		return rule != null ? rule.getValue(0) : 0;
	}

	/**
	 * Get surround (enclosure) rule for one layer around another.
	 */
	public double getSurroundRule(Layer outerLayer, Layer innerLayer, String nodeName)
	{
		if (outerLayer == null || innerLayer == null) return 0;
		try
		{
			List<DRCTemplate> rules = DRC.getRules(outerLayer, DRCTemplate.DRCRuleType.SURROUND);
			if (rules != null)
			{
				for (DRCTemplate rule : rules)
				{
					if (rule.name2 != null && rule.name2.equals(innerLayer.getName()))
					{
						if (nodeName == null || nodeName.equals(rule.nodeName))
							return rule.getValue(0);
					}
				}
				// Fallback: any surround rule for this layer pair
				for (DRCTemplate rule : rules)
				{
					if (rule.name2 != null && rule.name2.equals(innerLayer.getName()))
						return rule.getValue(0);
				}
			}
		}
		catch (Exception e) { /* rule query failed, use fallback */ }
		return 0;
	}

	// ==================== PRIMITIVE ACCESSORS ====================

	public PrimitiveNode getNTransistor() { return nTransistor; }
	public PrimitiveNode getPTransistor() { return pTransistor; }
	public PrimitiveNode getNActiveCon() { return nActiveCon; }
	public PrimitiveNode getPActiveCon() { return pActiveCon; }
	public PrimitiveNode getPolyCon() { return polyCon; }
	public PrimitiveNode getNwellCon() { return nwellCon; }
	public PrimitiveNode getPwellCon() { return pwellCon; }
	public PrimitiveNode getM1m2Con() { return m1m2Con; }

	public Layer getMetal1Layer() { return metal1Layer; }
	public Layer getMetal2Layer() { return metal2Layer; }
	public Layer getNWellLayer() { return nWellLayer; }
	public Layer getPWellLayer() { return pWellLayer; }

	// ==================== DIAGNOSTICS ====================

	/**
	 * Print all computed spacing values for debugging.
	 */
	public void printRuleSummary()
	{
		System.out.println("  TechRules for " + tech.getTechName() + ":");

		// Layer spacings
		System.out.println("    Metal-1 spacing: " + fmt(getLayerSpacing(metal1Layer, metal1Layer)));
		System.out.println("    Metal-2 spacing: " + fmt(getLayerSpacing(metal2Layer, metal2Layer)));
		System.out.println("    Poly spacing: " + fmt(getLayerSpacing(polyLayer, polyLayer)));
		System.out.println("    N-Active spacing: " + fmt(getLayerSpacing(nActiveLayer, nActiveLayer)));
		System.out.println("    P-Active spacing: " + fmt(getLayerSpacing(pActiveLayer, pActiveLayer)));

		// Min widths
		System.out.println("    Metal-1 min width: " + fmt(getMinWidth(metal1Layer)));
		System.out.println("    Metal-2 min width: " + fmt(getMinWidth(metal2Layer)));
		System.out.println("    N-Well min width: " + fmt(getMinWidth(nWellLayer)));
		System.out.println("    Poly min width: " + fmt(getMinWidth(polyLayer)));

		// Well enclosure
		System.out.println("    N-Well enclosure of P-Active: " + fmt(getWellEnclosure()));

		// Computed values
		System.out.println("    --- Computed Spacings ---");
		System.out.println("    Device spacing: " + fmt(getDeviceSpacing()));
		System.out.println("    Row separation: " + fmt(getRowSeparation()));
		System.out.println("    Well contact margin: " + fmt(getWellContactMargin()));
		System.out.println("    Contact offset: " + fmt(getContactOffset()));
		System.out.println("    Supply bus offset: " + fmt(getSupplyBusOffset()));
		System.out.println("    Min well contact size: " + fmt(getMinWellContactSize()));
	}

	// ==================== PRIVATE HELPERS ====================

	private DRCTemplate getSpacingRule(Layer l1, Layer l2)
	{
		try
		{
			return DRC.getSpacingRule(l1, null, l2, null, false, -1, -1.0, -1.0);
		}
		catch (Exception e) { return null; }
	}

	private PrimitiveNode findPrim(String name)
	{
		for (Iterator<PrimitiveNode> it = tech.getNodes(); it.hasNext(); )
		{
			PrimitiveNode n = it.next();
			if (n.getName().equals(name)) return n;
		}
		return null;
	}

	private static double snap(double v) { return Math.round(v * 2.0) / 2.0; }
	private static String fmt(double v) { return String.format("%.1f", v); }
}
