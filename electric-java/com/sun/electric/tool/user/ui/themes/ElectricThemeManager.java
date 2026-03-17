/* -*- tab-width: 4 -*-
 *
 * Electric(tm) VLSI Design System
 *
 * File: ElectricThemeManager.java
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
package com.sun.electric.tool.user.ui.themes;

import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.FlatDarkLaf;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Window;
import java.util.prefs.Preferences;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the FlatLaf-based themes for Electric VLSI.
 * Provides Claude-inspired light and dark themes with warm brown/cream palette.
 */
public class ElectricThemeManager {

    private static final Logger logger = LoggerFactory.getLogger(ElectricThemeManager.class);
    private static final String PREF_THEME = "electricTheme";
    private static final String THEME_LIGHT = "light";
    private static final String THEME_DARK = "dark";
    private static final String CUSTOM_DEFAULTS_PACKAGE = "com.sun.electric.tool.user.ui.themes";

    private static String currentTheme = THEME_LIGHT;
    private static boolean customDefaultsRegistered = false;

    /**
     * Initialize the FlatLaf look and feel with Electric's custom theme.
     * Must be called BEFORE any Swing components are created.
     */
    public static void initialize() {
        // Register custom defaults source once
        if (!customDefaultsRegistered) {
            FlatLaf.registerCustomDefaultsSource(CUSTOM_DEFAULTS_PACKAGE);
            customDefaultsRegistered = true;
        }

        // Load saved preference
        currentTheme = getPreferences().get(PREF_THEME, THEME_LIGHT);
        applyTheme(currentTheme, false);
    }

    /**
     * Apply a theme by name.
     * @param themeName "light" or "dark"
     * @param updateWindows if true, updates all existing windows
     */
    public static void applyTheme(String themeName, boolean updateWindows) {
        try {
            if (THEME_DARK.equals(themeName)) {
                currentTheme = THEME_DARK;
                FlatDarkLaf.setup();
            } else {
                currentTheme = THEME_LIGHT;
                FlatLightLaf.setup();
            }

            // Save preference
            getPreferences().put(PREF_THEME, currentTheme);

            if (updateWindows) {
                for (Window window : Window.getWindows()) {
                    SwingUtilities.updateComponentTreeUI(window);
                    window.repaint();
                }
            }

            logger.info("Applied Electric theme: {}", currentTheme);

        } catch (Exception e) {
            logger.error("Failed to apply Electric theme, falling back to system L&F", e);
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ex) {
                logger.error("Failed to set system L&F", ex);
            }
        }
    }

    /**
     * Toggle between light and dark themes.
     */
    public static void toggleTheme() {
        if (THEME_LIGHT.equals(currentTheme)) {
            applyTheme(THEME_DARK, true);
        } else {
            applyTheme(THEME_LIGHT, true);
        }
    }

    /**
     * @return true if current theme is dark
     */
    public static boolean isDarkTheme() {
        return THEME_DARK.equals(currentTheme);
    }

    /**
     * @return the current theme name ("light" or "dark")
     */
    public static String getCurrentTheme() {
        return currentTheme;
    }

    /**
     * Set to light theme.
     */
    public static void setLightTheme() {
        applyTheme(THEME_LIGHT, true);
    }

    /**
     * Set to dark theme.
     */
    public static void setDarkTheme() {
        applyTheme(THEME_DARK, true);
    }

    private static Preferences getPreferences() {
        return Preferences.userNodeForPackage(ElectricThemeManager.class);
    }
}
