/* -*- tab-width: 4 -*-
 *
 * Electric(tm) VLSI Design System
 *
 * File: TruthTablePanel.java
 * Truth table analysis from simulation waveform data.
 *
 * Copyright (c) 2026, Static Free Software. All rights reserved.
 *
 * Electric(tm) is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 */
package com.sun.electric.tool.user.waveform;

import com.sun.electric.tool.simulation.Signal;
import com.sun.electric.tool.simulation.DigitalSample;
import com.sun.electric.tool.simulation.ScalarSample;
import com.sun.electric.tool.simulation.Sample;

import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;

/**
 * Panel that displays a truth table derived from simulation waveform data.
 * Features: column order selection, progress bar, background analysis, threshold config.
 */
public class TruthTablePanel extends JPanel
{
    private final WaveformWindow waveWindow;
    private JTable truthTable;
    private DefaultTableModel tableModel;
    private JLabel statusLabel;
    private JProgressBar progressBar;
    private JComboBox<String> thresholdSelector;
    private JButton analyzeBtn;
    private DefaultListModel<SignalEntry> inputListModel, outputListModel;
    private double threshold = 0.9;
    private volatile boolean analyzing = false;

    private static class SignalEntry
    {
        final Signal<?> signal;
        final String name;
        SignalEntry(Signal<?> s) { this.signal = s; this.name = s.getSignalName(); }
        @Override public String toString() { return name; }
    }

    public TruthTablePanel(WaveformWindow ww)
    {
        super(new BorderLayout(0, 4));
        this.waveWindow = ww;
        setPreferredSize(new Dimension(380, 400));
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        // Header with threshold
        JPanel headerPanel = new JPanel(new BorderLayout(4, 2));
        JLabel titleLabel = new JLabel("Truth Table");
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 13f));
        headerPanel.add(titleLabel, BorderLayout.WEST);

        JPanel threshPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        threshPanel.add(new JLabel("Vth:"));
        thresholdSelector = new JComboBox<>(new String[]{"0.9V", "1.65V", "2.5V"});
        thresholdSelector.setPreferredSize(new Dimension(70, 22));
        thresholdSelector.addActionListener(e -> {
            switch (thresholdSelector.getSelectedIndex())
            { case 0: threshold = 0.9; break; case 1: threshold = 1.65; break; case 2: threshold = 2.5; break; }
        });
        threshPanel.add(thresholdSelector);
        headerPanel.add(threshPanel, BorderLayout.EAST);
        add(headerPanel, BorderLayout.NORTH);

        // Center: signal lists + table in card layout
        JPanel centerPanel = new JPanel(new BorderLayout(0, 4));

        // Signal assignment panel (inputs on left, outputs on right)
        JPanel signalPanel = new JPanel(new GridLayout(1, 3, 4, 0));
        signalPanel.setPreferredSize(new Dimension(380, 120));
        signalPanel.setBorder(BorderFactory.createTitledBorder("Assign Signals (drag to reorder)"));

        inputListModel = new DefaultListModel<>();
        JList<SignalEntry> inputList = new JList<>(inputListModel);
        inputList.setDragEnabled(true);
        inputList.setDropMode(DropMode.INSERT);
        JPanel inputPanel = new JPanel(new BorderLayout());
        inputPanel.add(new JLabel("Inputs:", JLabel.CENTER), BorderLayout.NORTH);
        inputPanel.add(new JScrollPane(inputList), BorderLayout.CENTER);

        outputListModel = new DefaultListModel<>();
        JList<SignalEntry> outputList = new JList<>(outputListModel);
        outputList.setDragEnabled(true);
        outputList.setDropMode(DropMode.INSERT);
        JPanel outputPanel = new JPanel(new BorderLayout());
        outputPanel.add(new JLabel("Outputs:", JLabel.CENTER), BorderLayout.NORTH);
        outputPanel.add(new JScrollPane(outputList), BorderLayout.CENTER);

        // Transfer buttons (move between lists)
        JPanel transferPanel = new JPanel(new GridLayout(3, 1, 0, 4));
        JButton toOutput = new JButton("\u2192"); // →
        toOutput.setToolTipText("Move selected to Outputs");
        toOutput.addActionListener(e -> {
            for (SignalEntry se : inputList.getSelectedValuesList())
            { inputListModel.removeElement(se); outputListModel.addElement(se); }
        });
        JButton toInput = new JButton("\u2190"); // ←
        toInput.setToolTipText("Move selected to Inputs");
        toInput.addActionListener(e -> {
            for (SignalEntry se : outputList.getSelectedValuesList())
            { outputListModel.removeElement(se); inputListModel.addElement(se); }
        });
        JButton loadBtn = new JButton("Load");
        loadBtn.setToolTipText("Load signals from waveform panels");
        loadBtn.addActionListener(e -> loadSignalsFromPanels());
        transferPanel.add(toOutput);
        transferPanel.add(toInput);
        transferPanel.add(loadBtn);

        signalPanel.add(inputPanel);
        signalPanel.add(transferPanel);
        signalPanel.add(outputPanel);
        centerPanel.add(signalPanel, BorderLayout.NORTH);

        // Table
        tableModel = new DefaultTableModel();
        truthTable = new JTable(tableModel);
        truthTable.setFont(new Font("Monospaced", Font.PLAIN, 12));
        truthTable.setRowHeight(20);
        truthTable.getTableHeader().setReorderingAllowed(false);

        truthTable.setDefaultRenderer(Object.class, new DefaultTableCellRenderer()
        {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                boolean isSelected, boolean hasFocus, int row, int column)
            {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                setHorizontalAlignment(SwingConstants.CENTER);
                if (!isSelected && value != null)
                {
                    String v = value.toString();
                    if (v.equals("1")) setForeground(new Color(0x2E7D32));
                    else if (v.equals("0")) setForeground(new Color(0x1565C0));
                    else setForeground(Color.RED);
                    int nInputs = inputListModel.size();
                    setBackground(column >= nInputs ? new Color(0xFFF8E1) : Color.WHITE);
                }
                return c;
            }
        });
        centerPanel.add(new JScrollPane(truthTable), BorderLayout.CENTER);
        add(centerPanel, BorderLayout.CENTER);

        // Bottom: progress + analyze
        JPanel bottomPanel = new JPanel(new BorderLayout(4, 2));
        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        progressBar.setString("Ready");
        progressBar.setPreferredSize(new Dimension(200, 18));
        bottomPanel.add(progressBar, BorderLayout.CENTER);

        analyzeBtn = new JButton("Analyze");
        analyzeBtn.addActionListener(e -> startAnalysis());
        bottomPanel.add(analyzeBtn, BorderLayout.EAST);

        statusLabel = new JLabel(" ");
        statusLabel.setFont(statusLabel.getFont().deriveFont(10f));
        bottomPanel.add(statusLabel, BorderLayout.SOUTH);
        add(bottomPanel, BorderLayout.SOUTH);
    }

    /** Load signals from waveform panels into input/output lists. */
    public void loadSignalsFromPanels()
    {
        inputListModel.clear();
        outputListModel.clear();

        List<Signal<?>> allSignals = new ArrayList<>();
        for (Iterator<Panel> it = waveWindow.getPanels(); it.hasNext(); )
        {
            Panel panel = it.next();
            for (WaveSignal ws : panel.getSignals())
                allSignals.add(ws.getSignal());
        }

        for (Signal<?> s : allSignals)
        {
            String name = s.getSignalName().toLowerCase();
            if (name.contains("out") || name.contains("sum") || name.contains("carry") ||
                name.contains("cout") || name.equals("y") || name.equals("q") || name.equals("s"))
                outputListModel.addElement(new SignalEntry(s));
            else
                inputListModel.addElement(new SignalEntry(s));
        }

        if (outputListModel.isEmpty() && inputListModel.size() >= 2)
        {
            int half = inputListModel.size() / 2;
            for (int i = inputListModel.size() - 1; i >= half; i--)
            {
                SignalEntry se = inputListModel.remove(i);
                outputListModel.add(0, se);
            }
        }
        statusLabel.setText("Loaded " + allSignals.size() + " signals. Assign inputs/outputs, then Analyze.");
    }

    /** Start analysis in background thread. */
    private void startAnalysis()
    {
        if (analyzing) return;
        if (inputListModel.isEmpty() || outputListModel.isEmpty())
        {
            if (inputListModel.isEmpty() && outputListModel.isEmpty())
                loadSignalsFromPanels();
            if (inputListModel.isEmpty() || outputListModel.isEmpty())
            {
                statusLabel.setText("Need at least 1 input and 1 output signal.");
                return;
            }
        }

        analyzing = true;
        analyzeBtn.setEnabled(false);
        progressBar.setValue(0);
        progressBar.setString("Analyzing...");
        tableModel.setRowCount(0);
        tableModel.setColumnCount(0);

        // Collect signals in order from lists
        final List<Signal<?>> inputs = new ArrayList<>();
        for (int i = 0; i < inputListModel.size(); i++) inputs.add(inputListModel.get(i).signal);
        final List<Signal<?>> outputs = new ArrayList<>();
        for (int i = 0; i < outputListModel.size(); i++) outputs.add(outputListModel.get(i).signal);

        new SwingWorker<List<String[]>, Integer>()
        {
            String[] columns;

            @Override
            protected List<String[]> doInBackground()
            {
                columns = new String[inputs.size() + outputs.size()];
                for (int i = 0; i < inputs.size(); i++) columns[i] = inputs.get(i).getSignalName();
                for (int i = 0; i < outputs.size(); i++) columns[inputs.size() + i] = outputs.get(i).getSignalName();

                // Collect change times
                List<Signal<?>> all = new ArrayList<>(inputs);
                all.addAll(outputs);
                TreeSet<Double> changeTimes = new TreeSet<>();
                for (Signal<?> sig : all)
                {
                    Signal.View<? extends Sample> view = sig.getExactView();
                    if (view != null)
                        for (int i = 0; i < view.getNumEvents(); i++)
                            changeTimes.add(view.getTime(i));
                }

                publish(10); // 10% - times collected

                Set<String> seenInputs = new HashSet<>();
                List<String[]> rows = new ArrayList<>();
                int maxRows = 256;
                int total = changeTimes.size();
                int count = 0;

                for (double time : changeTimes)
                {
                    if (rows.size() >= maxRows) break;
                    count++;
                    if (count % 1000 == 0)
                        publish(10 + (int)(80.0 * count / total));

                    String[] row = new String[columns.length];
                    StringBuilder inputKey = new StringBuilder();
                    for (int i = 0; i < inputs.size(); i++)
                    {
                        row[i] = sampleSignal(inputs.get(i), time);
                        inputKey.append(row[i]);
                    }

                    String key = inputKey.toString();
                    if (seenInputs.contains(key)) continue;
                    seenInputs.add(key);

                    for (int i = 0; i < outputs.size(); i++)
                        row[inputs.size() + i] = sampleSignal(outputs.get(i), time);

                    rows.add(row);
                }

                // Sort by binary input value
                rows.sort((a, b) -> {
                    for (int i = 0; i < inputs.size(); i++)
                    {
                        int va = "1".equals(a[i]) ? 1 : 0;
                        int vb = "1".equals(b[i]) ? 1 : 0;
                        if (va != vb) return va - vb;
                    }
                    return 0;
                });

                publish(95);
                return rows;
            }

            @Override
            protected void process(List<Integer> chunks)
            {
                if (!chunks.isEmpty())
                {
                    int val = chunks.get(chunks.size() - 1);
                    progressBar.setValue(val);
                    progressBar.setString(val + "%");
                }
            }

            @Override
            protected void done()
            {
                try
                {
                    List<String[]> rows = get();
                    tableModel.setColumnIdentifiers(columns);
                    for (String[] row : rows) tableModel.addRow(row);
                    progressBar.setValue(100);
                    progressBar.setString("Done");
                    statusLabel.setText(inputs.size() + " inputs, " + outputs.size() +
                        " outputs, " + rows.size() + " rows (Vth=" + threshold + "V)");
                }
                catch (Exception e)
                {
                    progressBar.setString("Error");
                    statusLabel.setText("Error: " + e.getMessage());
                }
                analyzeBtn.setEnabled(true);
                analyzing = false;
            }
        }.execute();
    }

    @SuppressWarnings("unchecked")
    private String sampleSignal(Signal<?> sig, double time)
    {
        if (sig.isDigital())
        {
            Signal.View<DigitalSample> view = ((Signal<DigitalSample>) sig).getExactView();
            if (view == null || view.getNumEvents() == 0) return "X";
            DigitalSample sample = null;
            for (int i = view.getNumEvents() - 1; i >= 0; i--)
            {
                if (view.getTime(i) <= time + 1e-15) { sample = view.getSample(i); break; }
            }
            if (sample == null) sample = view.getSample(0);
            if (sample.isLogic1()) return "1";
            if (sample.isLogic0()) return "0";
            if (sample.isLogicX()) return "X";
            return "Z";
        }
        else
        {
            Signal.View<ScalarSample> view;
            try { view = ((Signal<ScalarSample>) sig).getExactView(); }
            catch (Exception e) { return "X"; }
            if (view == null || view.getNumEvents() == 0) return "X";
            double value = 0;
            for (int i = view.getNumEvents() - 1; i >= 0; i--)
            {
                if (view.getTime(i) <= time + 1e-15) { value = view.getSample(i).getValue(); break; }
            }
            if (Double.isNaN(value) || Double.isInfinite(value)) return "X";
            return value >= threshold ? "1" : "0";
        }
    }

    // Legacy method for compatibility
    public void analyzeFromPanels()
    {
        loadSignalsFromPanels();
        startAnalysis();
    }
}
