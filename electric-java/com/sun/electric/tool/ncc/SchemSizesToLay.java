/* -*- tab-width: 4 -*-
 *
 * Electric(tm) VLSI Design System
 *
 * File: SchemSizesToLay.java
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
package com.sun.electric.tool.ncc;

import com.sun.electric.database.EditingPreferences;
import com.sun.electric.database.hierarchy.Cell;
import com.sun.electric.database.hierarchy.View;
import com.sun.electric.database.topology.NodeInst;
import com.sun.electric.database.variable.VarContext;
import com.sun.electric.technology.TransistorSize;
import com.sun.electric.technology.technologies.Schematics;
import com.sun.electric.database.variable.Variable;
import com.sun.electric.tool.Job;
import com.sun.electric.tool.JobException;
import com.sun.electric.tool.user.User;
import com.sun.electric.tool.user.ui.WindowFrame;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Propagate transistor W/L sizes from schematic to layout.
 * For each transistor in layout, finds the matching schematic transistor
 * (by name) and copies its W/L attributes.
 */
public class SchemSizesToLay
{
	public static class PropagateJob extends Job
	{
		public PropagateJob()
		{
			super("Propagate Schematic Sizes to Layout", User.getUserTool(),
				Job.Type.CHANGE, null, null, Job.Priority.USER);
			startJob();
		}

		public boolean doIt() throws JobException
		{
			EditingPreferences ep = getEditingPreferences();
			Cell curCell = WindowFrame.needCurCell();
			if (curCell == null)
			{
				System.out.println("No current cell");
				return false;
			}

			// Find schematic and layout views
			Cell schemCell = null;
			Cell layCell = null;

			if (curCell.isSchematic())
			{
				schemCell = curCell;
				layCell = curCell.getCellGroup().getMainSchematics() != null ?
					curCell.otherView(View.LAYOUT) : null;
			}
			else if (curCell.getView() == View.LAYOUT)
			{
				layCell = curCell;
				schemCell = curCell.otherView(View.SCHEMATIC);
			}

			if (schemCell == null)
			{
				System.out.println("Cannot find schematic view");
				return false;
			}
			if (layCell == null)
			{
				System.out.println("Cannot find layout view");
				return false;
			}

			System.out.println("Propagating sizes from " + schemCell.describe(false) +
				" to " + layCell.describe(false));

			// Collect schematic transistor sizes by name
			Map<String, double[]> schemSizes = new HashMap<String, double[]>();
			for (Iterator<NodeInst> it = schemCell.getNodes(); it.hasNext(); )
			{
				NodeInst ni = it.next();
				if (!ni.isPrimitiveTransistor()) continue;
				TransistorSize ts = ni.getTransistorSize(VarContext.globalContext);
				if (ts == null) continue;
				double w = ts.getDoubleWidth();
				double l = ts.getDoubleLength();
				if (w <= 0 && l <= 0) continue;
				String name = ni.getName();
				schemSizes.put(name, new double[] { w, l });
			}

			if (schemSizes.isEmpty())
			{
				System.out.println("No transistor sizes found in schematic");
				return false;
			}

			// Apply to layout transistors
			int updated = 0;
			int matched = 0;
			for (Iterator<NodeInst> it = layCell.getNodes(); it.hasNext(); )
			{
				NodeInst ni = it.next();
				if (!ni.isPrimitiveTransistor()) continue;
				String name = ni.getName();
				double[] sizes = schemSizes.get(name);
				if (sizes == null) continue;
				matched++;

				TransistorSize curSize = ni.getTransistorSize(VarContext.globalContext);
				if (curSize == null) continue;
				double curW = curSize.getDoubleWidth();
				double curL = curSize.getDoubleLength();

				double targetW = sizes[0] > 0 ? sizes[0] : curW;
				double targetL = sizes[1] > 0 ? sizes[1] : curL;

				if (Math.abs(targetW - curW) > 0.001 || Math.abs(targetL - curL) > 0.001)
				{
					ni.setPrimitiveNodeSize(targetW, targetL, ep);
					updated++;
					System.out.println("  Updated " + name + ": W=" + targetW + " L=" + targetL +
						" (was W=" + curW + " L=" + curL + ")");
				}
			}

			System.out.println("Size propagation complete: " + matched + " matched, " +
				updated + " updated out of " + schemSizes.size() + " schematic transistors");
			return true;
		}
	}
}
