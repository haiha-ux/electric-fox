/* -*- tab-width: 4 -*-
 *
 * Electric(tm) VLSI Design System
 *
 * File: SpiceModelManager.java
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
import com.sun.electric.database.hierarchy.Library;
import com.sun.electric.tool.Job;
import com.sun.electric.tool.JobException;
import com.sun.electric.tool.user.UserInterfaceMain;
import com.sun.electric.tool.user.ui.TopLevel;

import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.*;
import java.awt.event.*;
import java.io.*;
import java.util.List;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.event.*;

/**
 * Dialog for managing SPICE models embedded in the current library.
 * Models are stored in a special "SpiceModels{doc}" cell within the library,
 * so they travel with the .jelib file and work on any machine.
 */
public class SpiceModelManager extends EDialog
{
    private static final String SPICE_MODELS_CELL = "SpiceModels{doc}";
    private JTextArea modelTextArea;
    private JList<String> modelList;
    private DefaultListModel<String> listModel;
    private Library currentLib;

    /** Creates the SPICE Model Manager dialog */
    public SpiceModelManager(Frame parent)
    {
        super(parent, false);
        currentLib = Library.getCurrent();
        initUI();
        loadModels();
        setVisible(true);
    }

    private void initUI()
    {
        setTitle("SPICE Model Manager - " + currentLib.getName());
        setPreferredSize(new Dimension(750, 520));

        JPanel mainPanel = new JPanel(new BorderLayout(8, 8));
        mainPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

        // Top info panel
        JPanel infoPanel = new JPanel(new BorderLayout());
        JLabel infoLabel = new JLabel("<html>Define SPICE models here. They are saved with the library " +
            "and included automatically in SPICE decks.<br>" +
            "Drag & drop <b>.lib</b> or <b>.model</b> files to import, or type models directly.</html>");
        infoLabel.setBorder(new EmptyBorder(0, 0, 8, 0));
        infoPanel.add(infoLabel, BorderLayout.CENTER);
        mainPanel.add(infoPanel, BorderLayout.NORTH);

        // Left panel - model list
        JPanel leftPanel = new JPanel(new BorderLayout(0, 4));
        leftPanel.setPreferredSize(new Dimension(180, 0));

        JLabel listLabel = new JLabel("Models in Library:");
        leftPanel.add(listLabel, BorderLayout.NORTH);

        listModel = new DefaultListModel<>();
        modelList = new JList<>(listModel);
        modelList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        modelList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) onModelSelected();
        });
        JScrollPane listScroll = new JScrollPane(modelList);
        leftPanel.add(listScroll, BorderLayout.CENTER);

        // List buttons
        JPanel listButtons = new JPanel(new GridLayout(1, 2, 4, 0));
        JButton addBtn = new JButton("Add");
        addBtn.addActionListener(e -> addNewModel());
        JButton removeBtn = new JButton("Remove");
        removeBtn.addActionListener(e -> removeSelectedModel());
        listButtons.add(addBtn);
        listButtons.add(removeBtn);
        leftPanel.add(listButtons, BorderLayout.SOUTH);

        mainPanel.add(leftPanel, BorderLayout.WEST);

        // Center panel - model editor
        JPanel centerPanel = new JPanel(new BorderLayout(0, 4));
        JLabel editorLabel = new JLabel("Model Definition:");
        centerPanel.add(editorLabel, BorderLayout.NORTH);

        modelTextArea = new JTextArea();
        modelTextArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        modelTextArea.setTabSize(4);
        JScrollPane editorScroll = new JScrollPane(modelTextArea);
        centerPanel.add(editorScroll, BorderLayout.CENTER);

        mainPanel.add(centerPanel, BorderLayout.CENTER);

        // Bottom buttons
        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton importBtn = new JButton("Import .lib File...");
        importBtn.addActionListener(e -> importLibFile());
        JButton saveBtn = new JButton("Save to Library");
        saveBtn.addActionListener(e -> saveModels());
        JButton closeBtn = new JButton("Close");
        closeBtn.addActionListener(e -> dispose());

        bottomPanel.add(importBtn);
        bottomPanel.add(Box.createHorizontalStrut(20));
        bottomPanel.add(saveBtn);
        bottomPanel.add(closeBtn);
        mainPanel.add(bottomPanel, BorderLayout.SOUTH);

        getContentPane().add(mainPanel);

        // Enable drag & drop
        setupDragAndDrop();

        pack();
        setLocationRelativeTo(getOwner());
    }

    private void setupDragAndDrop()
    {
        new DropTarget(modelTextArea, new DropTargetAdapter()
        {
            @Override
            public void drop(DropTargetDropEvent dtde)
            {
                try
                {
                    dtde.acceptDrop(DnDConstants.ACTION_COPY);
                    @SuppressWarnings("unchecked")
                    List<File> files = (List<File>) dtde.getTransferable()
                        .getTransferData(DataFlavor.javaFileListFlavor);
                    for (File file : files)
                    {
                        importFile(file);
                    }
                    dtde.dropComplete(true);
                } catch (Exception e)
                {
                    dtde.dropComplete(false);
                }
            }
        });
    }

    /**
     * Load models from the SpiceModels{doc} cell in the current library.
     */
    private void loadModels()
    {
        listModel.clear();
        String[] contents = getSpiceModelsContents(currentLib);
        if (contents == null)
        {
            // No models yet - show placeholder
            modelTextArea.setText("* No SPICE models defined yet.\n" +
                "* Add models using the buttons below, or drag & drop .lib files here.\n" +
                "*\n" +
                "* Example:\n" +
                "* .MODEL NMOS1 NMOS LEVEL=1 VTO=0.7 KP=110U GAMMA=0.4 LAMBDA=0.04\n" +
                "* .MODEL PMOS1 PMOS LEVEL=1 VTO=-0.7 KP=50U GAMMA=0.57 LAMBDA=0.05\n");
            listModel.addElement("(no models)");
            return;
        }

        // Parse model names from contents
        StringBuilder fullText = new StringBuilder();
        for (String line : contents)
        {
            fullText.append(line).append("\n");
            String trimmed = line.trim().toUpperCase();
            if (trimmed.startsWith(".MODEL "))
            {
                String[] parts = trimmed.split("\\s+");
                if (parts.length >= 3)
                {
                    listModel.addElement(parts[1] + " (" + parts[2] + ")");
                }
            }
            else if (trimmed.startsWith(".SUBCKT "))
            {
                String[] parts = trimmed.split("\\s+");
                if (parts.length >= 2)
                {
                    listModel.addElement(parts[1] + " (subckt)");
                }
            }
        }
        modelTextArea.setText(fullText.toString());
        if (listModel.isEmpty())
        {
            listModel.addElement("(no .model/.subckt found)");
        }
    }

    private void onModelSelected()
    {
        String selected = modelList.getSelectedValue();
        if (selected == null || selected.startsWith("(")) return;

        // Find the model in the text and scroll to it
        String modelName = selected.split("\\s")[0];
        String text = modelTextArea.getText().toUpperCase();
        int idx = text.indexOf(".MODEL " + modelName);
        if (idx < 0) idx = text.indexOf(".SUBCKT " + modelName);
        if (idx >= 0)
        {
            modelTextArea.setCaretPosition(idx);
            try
            {
                Rectangle rect = modelTextArea.modelToView(idx);
                if (rect != null) modelTextArea.scrollRectToVisible(rect);
            } catch (Exception e) { /* ignore */ }
            modelTextArea.requestFocus();
        }
    }

    private void addNewModel()
    {
        String[] options = {"NMOS", "PMOS", "NPN", "PNP", "NJFET", "PJFET", "Diode", "Subcircuit"};
        String choice = (String) JOptionPane.showInputDialog(this,
            "Select model type:", "Add SPICE Model",
            JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
        if (choice == null) return;

        String name = JOptionPane.showInputDialog(this, "Model name:", choice.toUpperCase() + "1");
        if (name == null || name.trim().isEmpty()) return;
        name = name.trim().toUpperCase();

        String template;
        switch (choice)
        {
            case "NMOS":
                template = ".MODEL " + name + " NMOS LEVEL=1 VTO=0.7 KP=110U GAMMA=0.4 LAMBDA=0.04\n" +
                    "+TOX=9E-9 CGSO=0.2N CGDO=0.2N\n";
                break;
            case "PMOS":
                template = ".MODEL " + name + " PMOS LEVEL=1 VTO=-0.7 KP=50U GAMMA=0.57 LAMBDA=0.05\n" +
                    "+TOX=9E-9 CGSO=0.2N CGDO=0.2N\n";
                break;
            case "NPN":
                template = ".MODEL " + name + " NPN BF=100 IS=1E-16 VAF=100 IKF=0.3\n" +
                    "+CJE=20F CJC=20F TF=0.4N TR=40N\n";
                break;
            case "PNP":
                template = ".MODEL " + name + " PNP BF=50 IS=1E-16 VAF=80 IKF=0.1\n" +
                    "+CJE=20F CJC=20F TF=0.8N TR=60N\n";
                break;
            case "NJFET":
                template = ".MODEL " + name + " NJF VTO=-2.0 BETA=1.0E-4 LAMBDA=0.01\n";
                break;
            case "PJFET":
                template = ".MODEL " + name + " PJF VTO=2.0 BETA=5.0E-5 LAMBDA=0.01\n";
                break;
            case "Diode":
                template = ".MODEL " + name + " D IS=1E-14 N=1.05 BV=100 RS=10\n";
                break;
            case "Subcircuit":
                template = ".SUBCKT " + name + " in out\n* Add your subcircuit definition here\n.ENDS " + name + "\n";
                break;
            default:
                return;
        }

        String text = modelTextArea.getText();
        if (text.startsWith("* No SPICE models"))
        {
            modelTextArea.setText("");
            text = "";
        }
        modelTextArea.append("\n" + template);
        listModel.addElement(name + " (" + choice.toLowerCase() + ")");
    }

    private void removeSelectedModel()
    {
        String selected = modelList.getSelectedValue();
        if (selected == null || selected.startsWith("(")) return;

        int result = JOptionPane.showConfirmDialog(this,
            "Remove model '" + selected + "' from the text?",
            "Remove Model", JOptionPane.YES_NO_OPTION);
        if (result != JOptionPane.YES_OPTION) return;

        // Remove the model definition from text
        String modelName = selected.split("\\s")[0];
        String text = modelTextArea.getText();
        String[] lines = text.split("\n");
        StringBuilder sb = new StringBuilder();
        boolean skipping = false;
        for (String line : lines)
        {
            String trimmed = line.trim().toUpperCase();
            if (trimmed.startsWith(".MODEL " + modelName + " ") || trimmed.startsWith(".MODEL " + modelName + "\t"))
            {
                skipping = true;
                continue;
            }
            if (trimmed.startsWith(".SUBCKT " + modelName + " ") || trimmed.startsWith(".SUBCKT " + modelName + "\t"))
            {
                skipping = true;
                continue;
            }
            if (skipping)
            {
                // Continuation lines start with +
                if (trimmed.startsWith("+"))
                    continue;
                if (trimmed.startsWith(".ENDS"))
                {
                    skipping = false;
                    continue;
                }
                skipping = false;
            }
            sb.append(line).append("\n");
        }
        modelTextArea.setText(sb.toString());

        int idx = modelList.getSelectedIndex();
        listModel.remove(idx);
    }

    private void importLibFile()
    {
        String fileName = OpenFile.chooseInputFile(null, "Import SPICE Model File", null);
        if (fileName == null) return;
        importFile(new File(fileName));
    }

    private void importFile(File file)
    {
        try (BufferedReader reader = new BufferedReader(new FileReader(file)))
        {
            StringBuilder sb = new StringBuilder();
            sb.append("\n* Imported from: ").append(file.getName()).append("\n");
            String line;
            while ((line = reader.readLine()) != null)
            {
                sb.append(line).append("\n");
            }

            String text = modelTextArea.getText();
            if (text.startsWith("* No SPICE models"))
            {
                modelTextArea.setText("");
            }
            modelTextArea.append(sb.toString());

            // Refresh the model list
            refreshModelList();

            System.out.println("SPICE: Imported models from " + file.getName());
        } catch (IOException e)
        {
            JOptionPane.showMessageDialog(this,
                "Error reading file: " + e.getMessage(),
                "Import Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void refreshModelList()
    {
        listModel.clear();
        String text = modelTextArea.getText();
        String[] lines = text.split("\n");
        for (String line : lines)
        {
            String trimmed = line.trim().toUpperCase();
            if (trimmed.startsWith(".MODEL "))
            {
                String[] parts = trimmed.split("\\s+");
                if (parts.length >= 3)
                    listModel.addElement(parts[1] + " (" + parts[2] + ")");
            }
            else if (trimmed.startsWith(".SUBCKT "))
            {
                String[] parts = trimmed.split("\\s+");
                if (parts.length >= 2)
                    listModel.addElement(parts[1] + " (subckt)");
            }
        }
        if (listModel.isEmpty())
            listModel.addElement("(no .model/.subckt found)");
    }

    /**
     * Save the model text to the SpiceModels{doc} cell in the library.
     */
    private void saveModels()
    {
        String text = modelTextArea.getText().trim();
        if (text.startsWith("* No SPICE models"))
        {
            text = "";
        }
        new SaveModelsJob(currentLib, text);
    }

    /**
     * Job to save SPICE models into the library's SpiceModels{doc} cell.
     */
    private static class SaveModelsJob extends Job
    {
        private Library lib;
        private String modelText;

        SaveModelsJob(Library lib, String modelText)
        {
            super("Save SPICE Models", null, Job.Type.CHANGE, null, null, Job.Priority.USER);
            this.lib = lib;
            this.modelText = modelText;
            startJob();
        }

        @Override
        public boolean doIt() throws JobException
        {
            saveModelsToLibrary(lib, modelText, getEditingPreferences());
            System.out.println("SPICE: Models saved to library '" + lib.getName() + "'");
            return true;
        }
    }

    /**
     * Save model text into the SpiceModels{doc} cell of a library.
     * Must be called within a Job.
     */
    public static void saveModelsToLibrary(Library lib, String modelText,
        com.sun.electric.database.EditingPreferences ep)
    {
        Cell modelsCell = lib.findNodeProto(SPICE_MODELS_CELL);
        if (modelText.isEmpty())
        {
            // If empty and cell exists, remove it
            if (modelsCell != null)
            {
                modelsCell.kill();
            }
            return;
        }

        if (modelsCell == null)
        {
            modelsCell = Cell.newInstance(lib, SPICE_MODELS_CELL);
        }
        String[] lines = modelText.split("\n");
        modelsCell.setTextViewContents(lines, ep);
    }

    /**
     * Get the SPICE model definitions stored in a library.
     * @param lib the library to check
     * @return the model text lines, or null if no models are stored
     */
    public static String[] getSpiceModelsContents(Library lib)
    {
        Cell modelsCell = lib.findNodeProto(SPICE_MODELS_CELL);
        if (modelsCell == null) return null;
        return modelsCell.getTextViewContents();
    }

    /**
     * Check if a library has embedded SPICE models.
     */
    public static boolean hasEmbeddedModels(Library lib)
    {
        return lib.findNodeProto(SPICE_MODELS_CELL) != null;
    }

    /**
     * Get the full model text as a single string.
     */
    public static String getSpiceModelsText(Library lib)
    {
        String[] contents = getSpiceModelsContents(lib);
        if (contents == null) return null;
        StringBuilder sb = new StringBuilder();
        for (String line : contents)
        {
            sb.append(line).append("\n");
        }
        return sb.toString();
    }
}
