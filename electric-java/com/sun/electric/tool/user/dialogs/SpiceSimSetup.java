/* -*- tab-width: 4 -*-
 *
 * Electric(tm) VLSI Design System
 *
 * File: SpiceSimSetup.java
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
package com.sun.electric.tool.user.dialogs;

import com.sun.electric.database.hierarchy.Cell;
import com.sun.electric.database.hierarchy.Export;
import com.sun.electric.database.network.Netlist;
import com.sun.electric.database.network.Network;
import com.sun.electric.database.variable.Variable;
import com.sun.electric.tool.Job;
import com.sun.electric.tool.JobException;
import com.sun.electric.tool.io.FileType;
import com.sun.electric.tool.io.output.Spice;
import com.sun.electric.tool.user.User;
import com.sun.electric.tool.user.Highlighter;
import com.sun.electric.tool.user.menus.FileMenu;
import com.sun.electric.tool.user.ui.TopLevel;
import com.sun.electric.tool.user.ui.WindowFrame;
import com.sun.electric.tool.user.ui.EditWindow;
import com.sun.electric.tool.simulation.SimulationTool;

import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Point2D;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.event.*;
import javax.swing.table.*;

/**
 * Unified SPICE Simulation Setup panel.
 * Docks into the main window as a right-side panel.
 */
public class SpiceSimSetup extends JPanel
{
    /** Current simulation setup code, shared between panel and Spice writer */
    private static String currentSetupCode = null;

    /** The currently docked instance */
    private static SpiceSimSetup dockedInstance = null;
    private static JSplitPane dockSplit = null;
    private static JComponent originalContent = null;
    private static WindowFrame dockedFrame = null;

    // Source types for the combo box
    private static final String[] SOURCE_TYPES = {"(none)", "DC", "PULSE", "SIN", "PWL", "EXP", "SFFM"};

    // Analysis
    private JComboBox<String> analysisType;
    private JPanel paramPanel;
    private CardLayout paramCards;
    private JTextField tranStep, tranStop, tranStart;
    private JTextField dcSource, dcStart, dcStop, dcStep;
    private JComboBox<String> acVariation;
    private JTextField acPoints, acFStart, acFStop;
    private JTextField vddValue;
    private JCheckBox addVdd;

    // Net table
    private JTable netTable;
    private NetTableModel netTableModel;
    private Cell currentCell;

    // Code editor
    private JTextArea codeArea;
    private boolean codeManuallyEdited = false;

    public static String getCurrentSetupCode() { return currentSetupCode; }
    public static void setCurrentSetupCode(String code) { currentSetupCode = code; }

    /**
     * Toggle the simulation setup panel in the current window frame.
     * If already docked, undock it. If not docked, create and dock it.
     */
    public static void togglePanel()
    {
        if (dockedInstance != null)
        {
            undockPanel();
            return;
        }
        dockPanel();
    }

    private static void dockPanel()
    {
        WindowFrame wf = WindowFrame.getCurrentWindowFrame();
        if (wf == null) return;

        JFrame frame = (JFrame)SwingUtilities.getWindowAncestor(wf.getContent().getPanel());
        if (frame == null) return;

        // Find the main split pane (js field in WindowFrame)
        Container contentPane = frame.getContentPane();
        Component centerComp = ((BorderLayout)contentPane.getLayout()).getLayoutComponent(BorderLayout.CENTER);
        if (centerComp == null) return;

        // Create the setup panel
        dockedInstance = new SpiceSimSetup();
        dockedFrame = wf;
        originalContent = (JComponent)centerComp;

        // Wrap in a split pane: left=original content, right=sim setup
        dockSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        dockSplit.setLeftComponent(originalContent);
        dockSplit.setRightComponent(dockedInstance);
        dockSplit.setResizeWeight(0.6);

        contentPane.add(dockSplit, BorderLayout.CENTER);
        contentPane.validate();
        contentPane.repaint();

        // Set divider location after layout
        SwingUtilities.invokeLater(() -> dockSplit.setDividerLocation(0.6));
    }

    static void undockPanel()
    {
        if (dockedInstance == null || dockedFrame == null) return;

        JFrame frame = (JFrame)SwingUtilities.getWindowAncestor(dockSplit);
        if (frame == null) return;

        Container contentPane = frame.getContentPane();
        contentPane.remove(dockSplit);
        contentPane.add(originalContent, BorderLayout.CENTER);
        contentPane.validate();
        contentPane.repaint();

        dockedInstance = null;
        dockSplit = null;
        originalContent = null;
        dockedFrame = null;
    }

    public SpiceSimSetup()
    {
        currentCell = Job.getUserInterface().needCurrentCell();
        initUI();
        loadExistingSetup();
        regenerateCode();
        highlightSelectedNet();
    }

    // For backward compatibility - creates as docked panel
    public SpiceSimSetup(Frame parent)
    {
        this();
        togglePanel();
    }

    private void initUI()
    {
        setLayout(new BorderLayout(4, 4));
        setBorder(new EmptyBorder(4, 4, 4, 4));

        // Header with title and close button
        JPanel header = new JPanel(new BorderLayout());
        JLabel titleLabel = new JLabel("SPICE Simulation Setup");
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 13f));
        titleLabel.setBorder(new EmptyBorder(2, 4, 2, 0));
        header.add(titleLabel, BorderLayout.CENTER);
        JButton closeBtn = new JButton("\u2715"); // X character
        closeBtn.putClientProperty("JButton.buttonType", "toolBarButton");
        closeBtn.setFocusable(false);
        closeBtn.setToolTipText("Close simulation panel");
        closeBtn.addActionListener(e -> undockPanel());
        header.add(closeBtn, BorderLayout.EAST);
        header.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0,
            UIManager.getColor("Separator.foreground")));
        add(header, BorderLayout.NORTH);

        // Main content in a vertical split: top=net table, bottom=analysis+code
        JPanel topPanel = createNetPanel();
        JPanel bottomPanel = new JPanel(new BorderLayout(0, 4));

        // Analysis type
        JPanel analysisPanel = new JPanel(new BorderLayout(0, 2));
        JPanel typeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        typeRow.add(new JLabel("Analysis:"));
        analysisType = new JComboBox<>(new String[]{"Transient (.tran)", "DC Sweep (.dc)", "AC Analysis (.ac)"});
        analysisType.addActionListener(e -> { switchAnalysis(); regenerateCode(); });
        typeRow.add(analysisType);
        analysisPanel.add(typeRow, BorderLayout.NORTH);

        paramCards = new CardLayout();
        paramPanel = new JPanel(paramCards);
        paramPanel.setBorder(BorderFactory.createTitledBorder("Analysis Parameters"));
        paramPanel.add(createTranPanel(), "tran");
        paramPanel.add(createDCPanel(), "dc");
        paramPanel.add(createACPanel(), "ac");
        analysisPanel.add(paramPanel, BorderLayout.CENTER);

        // Power supply
        JPanel vddPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 1));
        vddPanel.setBorder(BorderFactory.createTitledBorder("Power Supply"));
        addVdd = new JCheckBox("VDD:", true);
        addVdd.addActionListener(e -> regenerateCode());
        vddPanel.add(addVdd);
        vddValue = createTextField("5", 4);
        vddPanel.add(vddValue);
        vddPanel.add(new JLabel("V"));
        analysisPanel.add(vddPanel, BorderLayout.SOUTH);

        bottomPanel.add(analysisPanel, BorderLayout.NORTH);

        // SPICE Code Editor
        JPanel codePanel = new JPanel(new BorderLayout(2, 2));
        codePanel.setBorder(BorderFactory.createTitledBorder("SPICE Code (editable)"));
        codeArea = new JTextArea(6, 30);
        codeArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        codeArea.getDocument().addDocumentListener(new SimpleDocListener(() -> {
            codeManuallyEdited = true;
        }));
        JScrollPane codeScroll = new JScrollPane(codeArea);
        codePanel.add(codeScroll, BorderLayout.CENTER);

        JPanel codeButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton regenerateBtn = new JButton("Regenerate");
        regenerateBtn.setToolTipText("Regenerate code from settings above");
        regenerateBtn.addActionListener(e -> { codeManuallyEdited = false; regenerateCode(); });
        codeButtons.add(regenerateBtn);
        codePanel.add(codeButtons, BorderLayout.SOUTH);

        bottomPanel.add(codePanel, BorderLayout.CENTER);

        JSplitPane mainSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, topPanel, bottomPanel);
        mainSplit.setResizeWeight(0.45);
        mainSplit.setDividerLocation(250);
        add(mainSplit, BorderLayout.CENTER);

        // Bottom action buttons
        JPanel actionPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 3));
        JButton applyBtn = new JButton("Apply");
        applyBtn.setToolTipText("Save simulation setup to the current cell");
        applyBtn.addActionListener(e -> applyToCell());

        JButton runBtn = new JButton("Write & Run");
        runBtn.setToolTipText("Save setup, write SPICE deck, and run simulation");
        runBtn.addActionListener(e -> applyAndRun());
        runBtn.putClientProperty("JButton.buttonType", "default");

        actionPanel.add(applyBtn);
        actionPanel.add(runBtn);
        add(actionPanel, BorderLayout.SOUTH);
    }

    // ==================== NET LIST PANEL ====================

    private JPanel createNetPanel()
    {
        JPanel panel = new JPanel(new BorderLayout(0, 2));
        panel.setBorder(BorderFactory.createTitledBorder("Circuit Nets"));

        netTableModel = new NetTableModel();
        populateNets();

        netTable = new JTable(netTableModel);
        netTable.setRowHeight(22);
        netTable.getColumnModel().getColumn(0).setPreferredWidth(100);
        netTable.getColumnModel().getColumn(1).setPreferredWidth(45);
        netTable.getColumnModel().getColumn(2).setPreferredWidth(60);
        netTable.getColumnModel().getColumn(3).setPreferredWidth(100);

        JComboBox<String> sourceCombo = new JComboBox<>(SOURCE_TYPES);
        netTable.getColumnModel().getColumn(2).setCellEditor(new DefaultCellEditor(sourceCombo));

        netTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) highlightSelectedNet();
        });

        netTableModel.addTableModelListener(e -> {
            if (e.getColumn() == 2 || e.getColumn() == 3)
            {
                regenerateCode();
                highlightSelectedNet();
            }
        });

        JScrollPane tableScroll = new JScrollPane(netTable);
        panel.add(tableScroll, BorderLayout.CENTER);

        JPanel quickPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 1));
        JButton pulseBtn = new JButton("PULSE");
        pulseBtn.setToolTipText("Set selected net to PULSE source");
        pulseBtn.addActionListener(e -> quickAssign("PULSE", "0 {vdd} 0 1n 1n 10n 20n"));
        quickPanel.add(pulseBtn);

        JButton dcBtn = new JButton("DC");
        dcBtn.addActionListener(e -> quickAssign("DC", "{vdd}"));
        quickPanel.add(dcBtn);

        JButton sinBtn = new JButton("SIN");
        sinBtn.addActionListener(e -> quickAssign("SIN", "0 1 1MEG"));
        quickPanel.add(sinBtn);

        JButton clearBtn = new JButton("Clear");
        clearBtn.addActionListener(e -> quickAssign("(none)", ""));
        quickPanel.add(clearBtn);

        panel.add(quickPanel, BorderLayout.SOUTH);

        return panel;
    }

    private void populateNets()
    {
        netTableModel.clear();
        if (currentCell == null) return;

        Netlist netlist = currentCell.getNetlist();
        if (netlist == null) return;

        Set<String> powerGroundNames = new HashSet<>(Arrays.asList("vdd", "gnd", "vss", "power", "ground"));

        for (Iterator<Network> it = netlist.getNetworks(); it.hasNext(); )
        {
            Network net = it.next();
            String name = net.getName();
            if (name == null || name.isEmpty()) continue;

            String type;
            if (net.isExported())
            {
                Iterator<Export> exports = net.getExports();
                boolean isPowerGround = false;
                while (exports.hasNext())
                {
                    Export exp = exports.next();
                    if (exp.isPower() || exp.isGround()) { isPowerGround = true; break; }
                }
                if (isPowerGround || powerGroundNames.contains(name.toLowerCase()))
                    type = "pwr/gnd";
                else
                    type = "export";
            }
            else
            {
                type = "internal";
            }

            netTableModel.addNet(name, type, "(none)", "");
        }
    }

    private void quickAssign(String sourceType, String params)
    {
        int[] rows = netTable.getSelectedRows();
        if (rows.length == 0) return;

        String vdd = vddValue.getText().trim();
        params = params.replace("{vdd}", vdd);

        for (int row : rows)
        {
            netTableModel.setValueAt(sourceType, row, 2);
            netTableModel.setValueAt(params, row, 3);
        }
        regenerateCode();
    }

    private void highlightSelectedNet()
    {
        int row = netTable.getSelectedRow();
        if (currentCell == null) return;

        WindowFrame wf = WindowFrame.getCurrentWindowFrame();
        if (wf == null || !(wf.getContent() instanceof EditWindow)) return;

        EditWindow ew = (EditWindow)wf.getContent();
        Highlighter highlighter = ew.getHighlighter();
        highlighter.clear();

        Netlist netlist = currentCell.getNetlist();
        if (netlist == null) return;

        if (row >= 0)
        {
            String netName = (String)netTableModel.getValueAt(row, 0);
            for (Iterator<Network> it = netlist.getNetworks(); it.hasNext(); )
            {
                Network net = it.next();
                if (net.getName().equals(netName))
                {
                    highlighter.addNetwork(net, currentCell);
                    break;
                }
            }
        }

        showSourceIndicators(highlighter, netlist);

        highlighter.finished();
        ew.repaint();
    }

    private void showSourceIndicators(Highlighter highlighter, Netlist netlist)
    {
        Color labelBg = new Color(40, 100, 200, 180);
        Color pulseBg = new Color(200, 80, 40, 180);
        Color sinBg = new Color(40, 160, 80, 180);

        for (int i = 0; i < netTableModel.getRowCount(); i++)
        {
            String netName = (String)netTableModel.getValueAt(i, 0);
            String srcType = (String)netTableModel.getValueAt(i, 2);
            String params = (String)netTableModel.getValueAt(i, 3);

            if (srcType.equals("(none)") || srcType.isEmpty()) continue;

            Point2D loc = findNetLocation(netlist, netName);
            if (loc == null) continue;

            String label;
            if (srcType.equals("DC"))
                label = "DC " + params;
            else if (srcType.equals("PULSE"))
                label = "\u2587\u2581 PULSE";
            else if (srcType.equals("SIN"))
                label = "\u223F SIN";
            else
                label = srcType;

            Color bg;
            if (srcType.equals("PULSE") || srcType.equals("PWL") || srcType.equals("EXP"))
                bg = pulseBg;
            else if (srcType.equals("SIN") || srcType.equals("SFFM"))
                bg = sinBg;
            else
                bg = labelBg;

            Point2D labelLoc = new Point2D.Double(loc.getX() + 2, loc.getY() + 1.5);
            highlighter.addMessage(currentCell, label, labelLoc, 0, bg);
        }
    }

    private Point2D findNetLocation(Netlist netlist, String netName)
    {
        for (Iterator<Network> it = netlist.getNetworks(); it.hasNext(); )
        {
            Network net = it.next();
            if (!net.getName().equals(netName)) continue;

            Iterator<Export> exports = net.getExports();
            if (exports.hasNext())
            {
                Export exp = exports.next();
                return new Point2D.Double(
                    exp.getOriginalPort().getPoly().getCenterX(),
                    exp.getOriginalPort().getPoly().getCenterY());
            }

            Iterator<com.sun.electric.database.topology.PortInst> portIt = net.getPorts();
            if (portIt.hasNext())
            {
                com.sun.electric.database.topology.PortInst pi = portIt.next();
                return new Point2D.Double(
                    pi.getPoly().getCenterX(),
                    pi.getPoly().getCenterY());
            }
            break;
        }
        return null;
    }

    // ==================== ANALYSIS PANELS ====================

    private JPanel createTranPanel()
    {
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 4, 2, 4);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0; gbc.gridy = 0; p.add(new JLabel("Step:"), gbc);
        gbc.gridx = 1; tranStep = createTextField("1n", 7); p.add(tranStep, gbc);
        gbc.gridx = 2; p.add(new JLabel("(e.g. 1n)"), gbc);

        gbc.gridx = 0; gbc.gridy = 1; p.add(new JLabel("Stop:"), gbc);
        gbc.gridx = 1; tranStop = createTextField("100n", 7); p.add(tranStop, gbc);

        gbc.gridx = 0; gbc.gridy = 2; p.add(new JLabel("Start:"), gbc);
        gbc.gridx = 1; tranStart = createTextField("0", 7); p.add(tranStart, gbc);
        return p;
    }

    private JPanel createDCPanel()
    {
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 4, 2, 4);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0; gbc.gridy = 0; p.add(new JLabel("Source:"), gbc);
        gbc.gridx = 1; dcSource = createTextField("Vin", 7); p.add(dcSource, gbc);

        gbc.gridx = 0; gbc.gridy = 1; p.add(new JLabel("Start:"), gbc);
        gbc.gridx = 1; dcStart = createTextField("0", 7); p.add(dcStart, gbc);

        gbc.gridx = 0; gbc.gridy = 2; p.add(new JLabel("Stop:"), gbc);
        gbc.gridx = 1; dcStop = createTextField("5", 7); p.add(dcStop, gbc);

        gbc.gridx = 0; gbc.gridy = 3; p.add(new JLabel("Step:"), gbc);
        gbc.gridx = 1; dcStep = createTextField("0.01", 7); p.add(dcStep, gbc);
        return p;
    }

    private JPanel createACPanel()
    {
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 4, 2, 4);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0; gbc.gridy = 0; p.add(new JLabel("Variation:"), gbc);
        gbc.gridx = 1; acVariation = new JComboBox<>(new String[]{"DEC", "OCT", "LIN"});
        acVariation.addActionListener(e -> regenerateCode()); p.add(acVariation, gbc);

        gbc.gridx = 0; gbc.gridy = 1; p.add(new JLabel("Points:"), gbc);
        gbc.gridx = 1; acPoints = createTextField("100", 7); p.add(acPoints, gbc);

        gbc.gridx = 0; gbc.gridy = 2; p.add(new JLabel("Start:"), gbc);
        gbc.gridx = 1; acFStart = createTextField("1", 7); p.add(acFStart, gbc);

        gbc.gridx = 0; gbc.gridy = 3; p.add(new JLabel("Stop:"), gbc);
        gbc.gridx = 1; acFStop = createTextField("1G", 7); p.add(acFStop, gbc);
        return p;
    }

    private JTextField createTextField(String defaultVal, int cols)
    {
        JTextField tf = new JTextField(defaultVal, cols);
        tf.getDocument().addDocumentListener(new SimpleDocListener(() -> regenerateCode()));
        return tf;
    }

    private void switchAnalysis()
    {
        int idx = analysisType.getSelectedIndex();
        switch (idx)
        {
            case 0: paramCards.show(paramPanel, "tran"); break;
            case 1: paramCards.show(paramPanel, "dc"); break;
            case 2: paramCards.show(paramPanel, "ac"); break;
        }
    }

    // ==================== CODE GENERATION ====================

    private void regenerateCode()
    {
        if (codeManuallyEdited) return;

        StringBuilder sb = new StringBuilder();
        sb.append("* Simulation Setup (Electric VLSI)\n");

        if (addVdd.isSelected() && !vddValue.getText().trim().isEmpty())
            sb.append("Vdd vdd gnd ").append(vddValue.getText().trim()).append("\n");

        for (int i = 0; i < netTableModel.getRowCount(); i++)
        {
            String netName = (String)netTableModel.getValueAt(i, 0);
            String srcType = (String)netTableModel.getValueAt(i, 2);
            String params = (String)netTableModel.getValueAt(i, 3);

            if (srcType.equals("(none)") || srcType.isEmpty()) continue;
            if (netName.equalsIgnoreCase("vdd") && addVdd.isSelected()) continue;

            String srcName = "V" + sanitizeName(netName);
            sb.append(srcName).append(" ").append(netName).append(" gnd ");

            if (srcType.equals("DC"))
                sb.append("DC ").append(params);
            else if (srcType.equals("PULSE"))
                sb.append("PULSE(").append(params).append(")");
            else if (srcType.equals("SIN"))
                sb.append("SIN(").append(params).append(")");
            else if (srcType.equals("PWL"))
                sb.append("PWL(").append(params).append(")");
            else if (srcType.equals("EXP"))
                sb.append("EXP(").append(params).append(")");
            else if (srcType.equals("SFFM"))
                sb.append("SFFM(").append(params).append(")");

            sb.append("\n");
        }

        sb.append("\n");

        int idx = analysisType.getSelectedIndex();
        switch (idx)
        {
            case 0:
                sb.append(".tran ").append(tranStep.getText().trim());
                sb.append(" ").append(tranStop.getText().trim());
                String start = tranStart.getText().trim();
                if (!start.isEmpty() && !start.equals("0")) sb.append(" ").append(start);
                sb.append("\n");
                break;
            case 1:
                sb.append(".dc ").append(dcSource.getText().trim());
                sb.append(" ").append(dcStart.getText().trim());
                sb.append(" ").append(dcStop.getText().trim());
                sb.append(" ").append(dcStep.getText().trim());
                sb.append("\n");
                break;
            case 2:
                sb.append(".ac ").append(acVariation.getSelectedItem());
                sb.append(" ").append(acPoints.getText().trim());
                sb.append(" ").append(acFStart.getText().trim());
                sb.append(" ").append(acFStop.getText().trim());
                sb.append("\n");
                break;
        }

        codeArea.setText(sb.toString());
    }

    private String sanitizeName(String name)
    {
        return name.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    private String getSpiceCode()
    {
        return codeArea.getText();
    }

    // ==================== LOAD / SAVE ====================

    private void loadExistingSetup()
    {
        if (currentCell == null) return;

        Variable var = currentCell.getVar(Spice.SPICE_SIM_SETUP_KEY);
        if (var == null) var = currentCell.getVar(Spice.SPICE_NG_TEMPLATE_KEY);
        if (var == null) var = currentCell.getVar(Spice.SPICE_TEMPLATE_KEY);
        if (var == null) return;

        Object obj = var.getObject();
        String existingCode;
        if (obj instanceof String[])
        {
            StringBuilder sb = new StringBuilder();
            for (String s : (String[]) obj) sb.append(s).append("\n");
            existingCode = sb.toString();
        }
        else if (obj instanceof String)
            existingCode = (String) obj;
        else return;

        List<String> extraLines = new ArrayList<>();
        for (String line : existingCode.split("\n"))
        {
            String trimmed = line.trim().toLowerCase();
            if (trimmed.startsWith(".tran "))
            {
                analysisType.setSelectedIndex(0);
                String[] parts = trimmed.split("\\s+");
                if (parts.length >= 3) { tranStep.setText(parts[1]); tranStop.setText(parts[2]); }
                if (parts.length >= 4) tranStart.setText(parts[3]);
            }
            else if (trimmed.startsWith(".dc "))
            {
                analysisType.setSelectedIndex(1);
                String[] parts = trimmed.split("\\s+");
                if (parts.length >= 5) { dcSource.setText(parts[1]); dcStart.setText(parts[2]); dcStop.setText(parts[3]); dcStep.setText(parts[4]); }
            }
            else if (trimmed.startsWith(".ac "))
            {
                analysisType.setSelectedIndex(2);
                String[] parts = trimmed.split("\\s+");
                if (parts.length >= 5) { acVariation.setSelectedItem(parts[1].toUpperCase()); acPoints.setText(parts[2]); acFStart.setText(parts[3]); acFStop.setText(parts[4]); }
            }
            else if (trimmed.startsWith("vdd ") && trimmed.contains("gnd"))
            {
                String[] parts = trimmed.split("\\s+");
                addVdd.setSelected(true);
                for (int i = 3; i < parts.length; i++)
                {
                    if (!parts[i].equals("dc")) { vddValue.setText(parts[i]); break; }
                }
            }
            else if (trimmed.startsWith("v") && (trimmed.contains("pulse(") || trimmed.contains("sin(") ||
                     trimmed.contains("dc ") || trimmed.contains("pwl(") || trimmed.contains("exp(") ||
                     trimmed.contains("sffm(")))
            {
                parseSourceLine(line.trim());
            }
            else if (!trimmed.startsWith("*") && !trimmed.isEmpty())
            {
                extraLines.add(line);
            }
        }
        switchAnalysis();
    }

    private void parseSourceLine(String line)
    {
        String lower = line.toLowerCase();
        String[] parts = line.split("\\s+", 4);
        if (parts.length < 3) return;

        String netName = parts[1];
        String rest = parts.length > 3 ? parts[3] : "";

        for (int i = 0; i < netTableModel.getRowCount(); i++)
        {
            String tableName = (String)netTableModel.getValueAt(i, 0);
            if (tableName.equalsIgnoreCase(netName))
            {
                if (lower.contains("pulse("))
                {
                    netTableModel.setValueAt("PULSE", i, 2);
                    netTableModel.setValueAt(extractParenContent(rest, "pulse"), i, 3);
                }
                else if (lower.contains("sin("))
                {
                    netTableModel.setValueAt("SIN", i, 2);
                    netTableModel.setValueAt(extractParenContent(rest, "sin"), i, 3);
                }
                else if (lower.contains("pwl("))
                {
                    netTableModel.setValueAt("PWL", i, 2);
                    netTableModel.setValueAt(extractParenContent(rest, "pwl"), i, 3);
                }
                else if (lower.contains("exp("))
                {
                    netTableModel.setValueAt("EXP", i, 2);
                    netTableModel.setValueAt(extractParenContent(rest, "exp"), i, 3);
                }
                else if (lower.contains("sffm("))
                {
                    netTableModel.setValueAt("SFFM", i, 2);
                    netTableModel.setValueAt(extractParenContent(rest, "sffm"), i, 3);
                }
                else if (rest.toLowerCase().startsWith("dc ") || rest.matches("[0-9].*"))
                {
                    netTableModel.setValueAt("DC", i, 2);
                    String val = rest.toLowerCase().startsWith("dc ") ? rest.substring(3).trim() : rest.trim();
                    netTableModel.setValueAt(val, i, 3);
                }
                break;
            }
        }
    }

    private String extractParenContent(String text, String keyword)
    {
        int start = text.toLowerCase().indexOf(keyword + "(");
        if (start < 0) return "";
        start = text.indexOf('(', start) + 1;
        int end = text.indexOf(')', start);
        if (end < 0) end = text.length();
        return text.substring(start, end).trim();
    }

    private void applyToCell()
    {
        if (currentCell == null)
        {
            JOptionPane.showMessageDialog(this, "No current cell", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        String code = getSpiceCode();
        currentSetupCode = code;
        new ApplySimSetupJob(currentCell, code);
        System.out.println("SPICE: Simulation setup applied to cell '" + currentCell.describe(false) + "'");
    }

    private void applyAndRun()
    {
        if (currentCell == null)
        {
            JOptionPane.showMessageDialog(this, "No current cell", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        String code = getSpiceCode();
        currentSetupCode = code;
        new ApplySimSetupJob(currentCell, code);

        String prevRunChoice = SimulationTool.getSpiceRunChoice();
        boolean prevRunProbe = SimulationTool.getSpiceRunProbe();
        SimulationTool.setSpiceRunChoice(SimulationTool.spiceRunChoiceRunReportOutput);
        SimulationTool.setSpiceRunProbe(true);
        FileMenu.exportCommand(FileType.SPICE, true);
        if (!prevRunChoice.equals(SimulationTool.spiceRunChoiceRunReportOutput))
            SimulationTool.setSpiceRunChoice(prevRunChoice);
        if (!prevRunProbe)
            SimulationTool.setSpiceRunProbe(false);
    }

    // ==================== NET TABLE MODEL ====================

    private static class NetTableModel extends AbstractTableModel
    {
        private final String[] columnNames = {"Net", "Type", "Source", "Parameters"};
        private final List<String[]> data = new ArrayList<>();

        void addNet(String name, String type, String source, String params)
        {
            data.add(new String[]{name, type, source, params});
        }

        void clear() { data.clear(); }

        public int getRowCount() { return data.size(); }
        public int getColumnCount() { return 4; }
        public String getColumnName(int col) { return columnNames[col]; }

        public Object getValueAt(int row, int col) { return data.get(row)[col]; }

        public boolean isCellEditable(int row, int col)
        {
            return col >= 2;
        }

        public void setValueAt(Object value, int row, int col)
        {
            data.get(row)[col] = (String)value;
            fireTableCellUpdated(row, col);
        }
    }

    // ==================== JOBS ====================

    private static class ApplySimSetupJob extends Job
    {
        private Cell cell;
        private String spiceCode;

        ApplySimSetupJob(Cell cell, String spiceCode)
        {
            super("Apply SPICE Simulation Setup", User.getUserTool(), Job.Type.CHANGE, null, null, Job.Priority.USER);
            this.cell = cell;
            this.spiceCode = spiceCode;
            startJob();
        }

        @Override
        public boolean doIt() throws JobException
        {
            String[] lines = spiceCode.split("\n");
            cell.newVar(Spice.SPICE_SIM_SETUP_KEY, lines, getEditingPreferences());
            if (cell.getVar(Spice.SPICE_NG_TEMPLATE_KEY) != null)
                cell.delVar(Spice.SPICE_NG_TEMPLATE_KEY);
            if (cell.getVar(Spice.SPICE_TEMPLATE_KEY) != null)
                cell.delVar(Spice.SPICE_TEMPLATE_KEY);
            return true;
        }
    }

    private static class SimpleDocListener implements DocumentListener
    {
        private Runnable action;
        SimpleDocListener(Runnable action) { this.action = action; }
        public void insertUpdate(DocumentEvent e) { action.run(); }
        public void removeUpdate(DocumentEvent e) { action.run(); }
        public void changedUpdate(DocumentEvent e) { action.run(); }
    }
}
