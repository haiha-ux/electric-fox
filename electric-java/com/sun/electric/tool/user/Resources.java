/* -*- tab-width: 4 -*-
 *
 * Electric(tm) VLSI Design System
 *
 * File: Resources.java
 *
 * Copyright (c) 2004, Static Free Software. All rights reserved.
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
package com.sun.electric.tool.user;

import com.sun.electric.database.text.TextUtils;
import com.sun.electric.Launcher;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.net.URL;

import javax.swing.Icon;
import javax.swing.ImageIcon;

import com.formdev.flatlaf.extras.FlatSVGIcon;

/**
 * public class to handle resources like icons/images.
 */
public class Resources {
	private static final String resourceLocation = "resources/";

	// Location of valid 3D plugin
	private static final String plugin3D = "com.sun.electric.plugins.j3d";
    private static final String pluginJython = "org.python.util";

    /**
	 * Method to load an icon, preferring SVG (modern) over GIF/PNG (legacy).
	 * SVG icons are rendered to ImageIcon at 16x16 for full compatibility.
	 * @param theClass class path where the icon resource is stored under
	 * @param baseName icon name WITHOUT extension (e.g., "ButtonUndo")
	 * @return ImageIcon (rendered from SVG or loaded from GIF/PNG)
	 */
	public static ImageIcon getIcon(Class<?> theClass, String baseName)
	{
		// Try SVG first (modern, scalable)
		URL svgUrl = getURLResource(theClass, baseName + ".svg");
		if (svgUrl != null)
		{
			try
			{
				String pkg = theClass.getPackage().getName().replace('.', '/');
				String svgPath = pkg + "/" + resourceLocation + baseName + ".svg";
				FlatSVGIcon svgIcon = new FlatSVGIcon(svgPath, 16, 16);
				if (svgIcon.hasFound())
				{
					// Render SVG to BufferedImage and wrap as ImageIcon
					BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
					Graphics2D g2 = img.createGraphics();
					svgIcon.paintIcon(null, g2, 0, 0);
					g2.dispose();
					return new ImageIcon(img);
				}
			}
			catch (Exception e) { /* fall through to legacy */ }
		}
		// Fallback to GIF/PNG
		return getResource(theClass, baseName + ".gif");
	}

    /**
	 * Method to load a valid icon stored in resources package under the given class.
	 * @param theClass class path where the icon resource is stored under
	 * @param iconName icon name
	 */
	public static ImageIcon getResource(Class<?> theClass, String iconName)
	{
		URL url = getURLResource(theClass, iconName);
		if (url != null)
			return (new ImageIcon(url));
		else
			return (new ImageIcon());
	}

	/**
	 * Method to get URL path for a resource stored in resources package under the given class.
	 * @param theClass class path where resource is stored under
	 * @param resourceName resource name
	 * @return a URL for the requested resource.
	 */
	public static URL getURLResource(Class<?> theClass, String resourceName)
	{
		return (theClass.getResource(resourceLocation+resourceName));
	}

    public static Class<?> get3DClass(String name)
    {
        // Testing first if Java3D plugin exists
        Class<?> java3DClass = getClass("SimpleUniverse", "com.sun.j3d.utils.universe");
        if (java3DClass == null) return null; // Java3D not available
        return (getClass(name, plugin3D));
    }

    public static Class<?> getJythonClass(String name)
    {
        return (getClass(name, pluginJython));
    }

    private static Class<?> getClass(String name, String plugin)
    {
        Class<?> theClass = null;
		try
        {
            theClass = Launcher.classFromPlugins(plugin + "." + name);
        } catch (ClassNotFoundException e)
        {
        	TextUtils.recordMissingComponent(name);
        } catch (Error e)
        {
            System.out.println("Error accessing plugin '" + plugin + "': " + e.getMessage());
        }
		return (theClass);
    }
}
