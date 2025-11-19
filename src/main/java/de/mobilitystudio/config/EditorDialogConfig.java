package de.mobilitystudio.config;


import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;

import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigGroup;
import org.matsim.core.config.ConfigReader;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.ConfigWriter;
import org.matsim.core.config.groups.ReplanningConfigGroup.StrategySettings;
import org.matsim.core.config.groups.RoutingConfigGroup.TeleportedModeParams;
import org.matsim.core.config.groups.ScoringConfigGroup.ActivityParams;
import org.matsim.core.config.groups.ScoringConfigGroup.ModeParams;
import org.matsim.core.config.groups.ScoringConfigGroup.ScoringParameterSet;
import org.reflections.Reflections;
import org.reflections.scanners.Scanners;


/**
 * A Swing dialog for viewing and editing a MATSim Config object.
 * This version provides immediate user feedback on invalid parameter values.
 */
public class EditorDialogConfig extends JDialog {
    private static final long serialVersionUID = 23L; // Version with simple error handling
    private final Config configToEdit;
    private final Config backupConfig;
    private boolean applied = false;
    private JTabbedPane tabbedPane;
    private Map<ConfigGroup, Map<String, JTextField>> paramGroupFieldsMap;
    private final String configFilePath;
    private JCheckBox showCommentsSwitch;
    private boolean showComments = true; // Comments are visible by default
    private boolean showReducedConfig = false;
    private JCheckBox showReducedConfigSwitch;
    private final Set<String> defaultModuleNames;

    public EditorDialogConfig(JFrame parent, Config originalConfig, String configFilePath) {
        super(parent, true);
        this.configToEdit = originalConfig;
        this.configFilePath = configFilePath;
        
        this.defaultModuleNames = ConfigUtils.createConfig().getModules().keySet();
        
        this.backupConfig = cloneConfig(originalConfig);
        this.paramGroupFieldsMap = new HashMap<>();
        initComponents();
        populateConfigData();
        setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() { @Override public void windowClosing(WindowEvent e) { onCancel(); }});
        pack();
        setSize(Math.min(getWidth() + 150, 1200), Math.min(getHeight() + 150, 900));
    }
    
    private void saveConfigToFile() {
        if (this.configFilePath == null || this.configFilePath.trim().isEmpty()) {
            // No file path was provided, so we cannot save.
            return;
        }
        try {
            new ConfigWriter(this.configToEdit).write(this.configFilePath);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this,
                "Could not save the configuration to the file:\n" + this.configFilePath + "\n\n" +
                "Reason: " + e.getMessage(),
                "File Save Error",
                JOptionPane.ERROR_MESSAGE);
        }
    }
    
    private boolean applyUiChanges() {
        for (ConfigGroup group : new ArrayList<>(paramGroupFieldsMap.keySet())) {
            Map<String, JTextField> paramFields = paramGroupFieldsMap.get(group);
            if (paramFields == null) continue;

            for (String paramName : new ArrayList<>(paramFields.keySet())) {
                JTextField field = paramFields.get(paramName);
                if (field == null) continue;

                String newValue = field.getText();
                String currentValue = group.getParams().get(paramName);

                // Only try to set the parameter if the value has actually changed
                // to avoid unnecessary processing and potential errors.
                if (currentValue == null || !currentValue.equals(newValue)) {
                    try {
                        group.addParam(paramName, newValue);
                    } catch (Exception e) {
                        // This is the direct feedback to the user.
                        JOptionPane.showMessageDialog(this,
                            "Could not apply change for parameter '" + paramName + "' in module '" + group.getName() + "'.\n" +
                            "Value: '" + newValue + "'\n\n" +
                            "Reason: " + e.getMessage(),
                            "Invalid Parameter",
                            JOptionPane.ERROR_MESSAGE);
                        
                        // Highlight the problematic field for the user
                        field.requestFocusInWindow();
                        field.selectAll();
                        
                        return false; // Abort the save operation
                    }
                }
            }
        }
        return true; // All changes were successful
    }
    
    /**
     * Called when the user clicks the "Apply" button.
     */
    private void onApply() {
    	if (applyUiChanges()) {
    	saveConfigToFile(); // Save the config to its file
    	JOptionPane.showMessageDialog(this, "Changes have been applied and saved.", "Apply", JOptionPane.INFORMATION_MESSAGE);
    	}
    }
    
    /**
     * Called when the user clicks the "OK" button.
     */
    private void onOK() {
    	// Try to apply changes. If it fails, the dialog stays open for the user to fix the error.
    	if (applyUiChanges()) {
    	
    		saveConfigToFile(); // Save the config to its file
    		this.applied = true;
    		dispose();
    	}
    }

    /**
     * A helper method to save any pending text edits before the UI is rebuilt.
     * If saving fails, the UI is NOT rebuilt, allowing the user to fix the error.
     */
    private void saveAndRefresh() {
        if (applyUiChanges()) {
            populateConfigData();
        }
    }

    private void initComponents() {
        setLayout(new BorderLayout(10, 10));
        ((JPanel) getContentPane()).setBorder(new EmptyBorder(10, 10, 10, 10));
                        
        tabbedPane = new JTabbedPane();

        // Explicitly set the multi-row layout policy ---
        // This ensures that tabs will wrap into new rows when they don't fit.
        tabbedPane.setTabLayoutPolicy(JTabbedPane.WRAP_TAB_LAYOUT);

        // Add separator lines between tabs for a cleaner look ---
        // This is a hint for modern Look-and-Feels to draw vertical lines.
        tabbedPane.putClientProperty("JTabbedPane.showTabSeparators", true);
        // Adds a 1-pixel gray line just below the tab area, separating it from the content.
        // tabbedPane.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Color.LIGHT_GRAY));
        
        add(tabbedPane, BorderLayout.CENTER);

        JButton okButton = new JButton("OK");
        JButton applyButton = new JButton("Apply");
        JButton cancelButton = new JButton("Cancel");
        JButton addCustomModuleButton = new JButton("Add MATSim Module...");
        JButton addModuleButton = new JButton("Add New Module...");
        JButton xmlViewButton = new JButton("View/Edit XML...");
        
        showCommentsSwitch = new JCheckBox("Show Comments", this.showComments);
        showCommentsSwitch.addActionListener(e -> {
            this.showComments = showCommentsSwitch.isSelected();

            if (this.showComments) {
                // If the user is turning comments ON, call our new method
                // to fetch the comments and then refresh the view.
                reloadCommentsAndRefresh();
            } else {
                // If the user is turning comments OFF, we just need to refresh
                // the view to hide them. saveAndRefresh also applies pending
                // edits, which is a good safety measure.
                saveAndRefresh();
            }
        });
        
        showReducedConfigSwitch = new JCheckBox("Show reduced config", this.showReducedConfig);
        showReducedConfigSwitch.addActionListener(e -> {
            this.showReducedConfig = showReducedConfigSwitch.isSelected();
            saveAndRefresh();
        });
                
        okButton.addActionListener(e -> onOK());
        applyButton.addActionListener(e -> onApply());
        cancelButton.addActionListener(e -> onCancel());
        addCustomModuleButton.addActionListener(e -> onAddCustomModule());
        addModuleButton.addActionListener(e -> onAddModule());
        xmlViewButton.addActionListener(e -> onViewXml());

        // The main container for the entire bottom section.
        // A vertical BoxLayout will stack our two rows cleanly.
        JPanel southPanel = new JPanel();
        southPanel.setLayout(new BoxLayout(southPanel, BoxLayout.Y_AXIS));

        // --- Row 0: Checkboxes ---
        // This panel holds the user options.
        JPanel optionsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        optionsPanel.add(showCommentsSwitch);
        optionsPanel.add(showReducedConfigSwitch);
        // Ensure this panel aligns to the left boundary of the BoxLayout.
        optionsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        // --- Row 1: Action Buttons ---
        JPanel buttonBar = new JPanel(new GridBagLayout());
        buttonBar.setAlignmentX(Component.LEFT_ALIGNMENT);
        GridBagConstraints gbc = new GridBagConstraints();

        // The left-side buttons are in their own FlowLayout panel.
        JPanel leftButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        leftButtons.add(addCustomModuleButton);
        leftButtons.add(addModuleButton);
        leftButtons.add(xmlViewButton);

        // The right-side buttons are also in their own FlowLayout panel.
        JPanel rightButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        rightButtons.add(applyButton);
        rightButtons.add(okButton);
        rightButtons.add(cancelButton);

        // Configure constraints for the left button panel
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 1.0; // This is crucial! It allows this cell to grow and shrink.
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        buttonBar.add(leftButtons, gbc);

        // Configure constraints for the right button panel
        gbc.gridx = 1;
        gbc.gridy = 0;
        gbc.weightx = 0; // This cell does not grow, pinning it to the right.
        gbc.anchor = GridBagConstraints.EAST;
        gbc.fill = GridBagConstraints.NONE; // Only take up its preferred size.
        buttonBar.add(rightButtons, gbc);

        // Add the two main rows to the south panel.
        southPanel.add(optionsPanel);
        southPanel.add(Box.createVerticalStrut(5)); // A small gap between the rows
        southPanel.add(buttonBar);

        // Add the final panel to the dialog.
        add(southPanel, BorderLayout.SOUTH);
        
    }
    
    private void onAddCustomModule() {
        // 1. Define sets for core and contrib modules
        Map<String, Class<? extends ConfigGroup>> availableCoreModules = new TreeMap<>();
        Map<String, Class<? extends ConfigGroup>> availableContribModules = new TreeMap<>();
        Map<String, Class<? extends ConfigGroup>> allDiscoverableModules = new TreeMap<>();

        // 2. Get the names of all standard modules that come with a default config.
        Set<String> defaultModuleNames = ConfigUtils.createConfig().getModules().keySet();

        // 3. Use Reflections to find all available ConfigGroup classes.
        try {
            Reflections reflections = new Reflections("org.matsim.contrib", "ch.sbb.matsim", "org.matsim.core.config.groups", Scanners.SubTypes);
            Set<Class<? extends ConfigGroup>> moduleClasses = reflections.getSubTypesOf(ConfigGroup.class);

            for (Class<? extends ConfigGroup> moduleClass : moduleClasses) {
                if (java.lang.reflect.Modifier.isAbstract(moduleClass.getModifiers())) {
                    continue;
                }
                try {
                    ConfigGroup instance = moduleClass.getDeclaredConstructor().newInstance();
                    allDiscoverableModules.put(instance.getName(), moduleClass);
                } catch (Exception e) {
                    // Silently ignore modules that cannot be instantiated.
                }
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this,
                "Could not scan for custom modules. The 'reflections' library might be missing.\n\n" + e.getMessage(),
                "Discovery Error",
                JOptionPane.WARNING_MESSAGE);
        }

        // 4. Filter out modules that are already present in the current config.
        allDiscoverableModules.keySet().removeAll(configToEdit.getModules().keySet());

        if (allDiscoverableModules.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No new modules are available to add.", "Add Custom Module", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        // 5. Partition the remaining modules into the two groups.
        for (Map.Entry<String, Class<? extends ConfigGroup>> entry : allDiscoverableModules.entrySet()) {
            if (defaultModuleNames.contains(entry.getKey())) {
                availableCoreModules.put(entry.getKey(), entry.getValue());
            } else {
                availableContribModules.put(entry.getKey(), entry.getValue());
            }
        }

        // --- REVISED UI CODE STARTS HERE ---

        // 6. Build the UI using GridBagLayout for better space distribution.
        JPanel mainDialogPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        
        List<JCheckBox> allCheckBoxes = new ArrayList<>();
        int gridY = 0;

        // --- Instructions Label ---
        gbc.gridx = 0;
        gbc.gridy = gridY++;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(0, 0, 10, 0);
        mainDialogPanel.add(new JLabel("Select one or more modules to add:"), gbc);

        // --- GBC for ScrollPanes ---
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weightx = 1.0; // Allow horizontal expansion
        gbc.weighty = 1.0; // Distribute vertical space equally
        gbc.insets = new Insets(0, 0, 0, 0);

        // --- Core Modules Panel ---
        if (!availableCoreModules.isEmpty()) {
            JPanel coreCheckPanel = new JPanel();
            coreCheckPanel.setLayout(new BoxLayout(coreCheckPanel, BoxLayout.Y_AXIS));
            for (String moduleName : availableCoreModules.keySet()) {
                JCheckBox checkBox = new JCheckBox(moduleName);
                allCheckBoxes.add(checkBox);
                coreCheckPanel.add(checkBox);
            }
            JScrollPane coreScrollPane = new JScrollPane(coreCheckPanel);
            coreScrollPane.setBorder(BorderFactory.createTitledBorder("Core Modules"));
            gbc.gridy = gridY++;
            mainDialogPanel.add(coreScrollPane, gbc);
        }

        // --- Contrib/Extension Modules Panel ---
        if (!availableContribModules.isEmpty()) {
            JPanel contribCheckPanel = new JPanel();
            contribCheckPanel.setLayout(new BoxLayout(contribCheckPanel, BoxLayout.Y_AXIS));
            for (String moduleName : availableContribModules.keySet()) {
                JCheckBox checkBox = new JCheckBox(moduleName);
                allCheckBoxes.add(checkBox);
                contribCheckPanel.add(checkBox);
            }
            JScrollPane contribScrollPane = new JScrollPane(contribCheckPanel);
            contribScrollPane.setBorder(BorderFactory.createTitledBorder("Contrib / Extension Modules"));
            gbc.gridy = gridY++;
            // Add spacing between the two panels if both exist
            if (gridY > 2) { 
                 gbc.insets = new Insets(10, 0, 0, 0);
            }
            mainDialogPanel.add(contribScrollPane, gbc);
        }
        
        // Set a reasonable overall size for the dialog content
        mainDialogPanel.setPreferredSize(new java.awt.Dimension(400, 450));
        
        // --- REVISED UI CODE ENDS HERE ---

        // 7. Show the custom dialog to the user.
        int result = JOptionPane.showConfirmDialog(this, mainDialogPanel, "Add Custom Modules", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        // 8. Add all the selected modules to the config.
        if (result == JOptionPane.OK_OPTION) {
            boolean modulesWereAdded = false;
            for (JCheckBox checkBox : allCheckBoxes) {
                if (checkBox.isSelected()) {
                    String selectedName = checkBox.getText();
                    Class<? extends ConfigGroup> selectedClass = allDiscoverableModules.get(selectedName);
                    if (selectedClass != null) {
                        try {
                            ConfigGroup newGroup = selectedClass.getDeclaredConstructor().newInstance();
                            if (!configToEdit.getModules().containsKey(newGroup.getName())) {
                                configToEdit.addModule(newGroup);
                                modulesWereAdded = true;
                                
                            }
                        } catch (Exception e) {
                            JOptionPane.showMessageDialog(this, "Failed to create and add module '" + selectedName + "'.\n\nError: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                        }
                    }
                }
            }
            
            // 9. Refresh the UI once after all modules have been added.
            if (modulesWereAdded) {
                saveAndRefresh();
            }
        }
    }
    
    private void onAddModule() {
        String moduleName = JOptionPane.showInputDialog(this, "Enter the name for the new module (e.g., 'myCustomModule'):", "Add New Module", JOptionPane.PLAIN_MESSAGE);
        if (moduleName != null && !moduleName.trim().isEmpty()) {
            moduleName = moduleName.trim();
            if (configToEdit.getModules().containsKey(moduleName)) {
                JOptionPane.showMessageDialog(this, "A module with the name '" + moduleName + "' already exists.", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            ConfigGroup newGroup = new ConfigGroup(moduleName);
            configToEdit.addModule(newGroup);
            saveAndRefresh();
            for (int i=0; i < tabbedPane.getTabCount(); i++) {
                if (tabbedPane.getTitleAt(i).equals(moduleName)) { tabbedPane.setSelectedIndex(i); break; }
            }
        }
    }
    
    private void onAddParameter(ConfigGroup group) {
        String paramName = JOptionPane.showInputDialog(this, "Enter parameter name (key):", "Add Parameter", JOptionPane.PLAIN_MESSAGE);
        if (paramName != null && !paramName.trim().isEmpty()) {
            paramName = paramName.trim();
            if (group.getParams().containsKey(paramName)) {
                JOptionPane.showMessageDialog(this, "A parameter with this name already exists in this group.", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            String paramValue = JOptionPane.showInputDialog(this, "Enter parameter value:", "Add Parameter", JOptionPane.PLAIN_MESSAGE);
            
            if (paramValue != null) {
                try {
                    // The addParam call itself can throw an exception if the group doesn't allow unknown parameters.
                    // We must wrap this specific call.
                    group.addParam(paramName, paramValue);

                    // If successful, then we save and refresh the UI.
                    saveAndRefresh();

                } catch (Exception e) {
                    // If it fails, we catch the exception and show the user a helpful error message.
                    JOptionPane.showMessageDialog(this,
                        "Could not add the parameter '" + paramName + "' to module '" + group.getName() + "'.\n\n" +
                        "Reason: " + e.getMessage(),
                        "Invalid Parameter",
                        JOptionPane.ERROR_MESSAGE);
                    // We DO NOT refresh the UI, as the change was not successful.
                }
            }
        }
    }
    
    private void onRemoveParameter(ConfigGroup group) {
        Set<String> paramKeySet = group.getParams().keySet();
        String[] paramKeys = paramKeySet.toArray(new String[0]);
        if (paramKeys.length == 0) return;
        
        JComboBox<String> comboBox = new JComboBox<>(paramKeys);
        int result = JOptionPane.showConfirmDialog(this, comboBox, "Select parameter to remove", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (result == JOptionPane.OK_OPTION) {
            String selectedKey = (String) comboBox.getSelectedItem();
            if (selectedKey != null) {
                group.getParams().remove(selectedKey);
                if (paramGroupFieldsMap.containsKey(group)) {
                    paramGroupFieldsMap.get(group).remove(selectedKey);
                }
                saveAndRefresh();
            }
        }
    }

    private JPanel createAddButtonsPanel(final ConfigGroup group, int level) {
        JPanel addButtonsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
        addButtonsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        
        Set<String> creatableSetTypes = discoverCreatableParameterSets(group);
        List<String> sortedTypes = new ArrayList<>(creatableSetTypes);
        sortedTypes.sort(String.CASE_INSENSITIVE_ORDER);
        
        for (final String type : sortedTypes) {
            JButton addButton = new JButton("Add " + type + "...");
            addButton.addActionListener(e -> onAddParameterSet(group, type));
            addButtonsPanel.add(addButton);
        }
        
        boolean allowParameterEditing = (level > 0) || (level == 0 && group.getClass().equals(ConfigGroup.class));
        
        if (allowParameterEditing) {
            JButton addParamButton = new JButton("Add Parameter...");
            addParamButton.addActionListener(e -> onAddParameter(group));
            addButtonsPanel.add(addParamButton);
            
            if (!group.getParams().isEmpty()) {
                JButton removeParamButton = new JButton("Remove Parameter...");
                removeParamButton.addActionListener(e -> onRemoveParameter(group));
                addButtonsPanel.add(removeParamButton);
            }
        }
        return addButtonsPanel;
    }
    
    private Set<String> discoverCreatableParameterSets(ConfigGroup group) {
        Set<String> creatableTypes = new HashSet<>();
        try {
            Method createMethod = group.getClass().getMethod("createParameterSet", String.class);
            for (Method method : group.getClass().getMethods()) {
                if (method.getName().startsWith("add") && method.getParameterCount() == 1 && ConfigGroup.class.isAssignableFrom(method.getParameterTypes()[0])) {
                    String potentialSetName = method.getName().substring(3);
                    if (!potentialSetName.isEmpty()) {
                        String paramSetName = Character.toLowerCase(potentialSetName.charAt(0)) + potentialSetName.substring(1);
                        try {
                            if (createMethod.invoke(group, paramSetName) instanceof ConfigGroup) {
                                creatableTypes.add(paramSetName);
                            }
                        } catch (Exception e) { /* Type not supported by createParameterSet */ }
                    }
                }
            }
        } catch (NoSuchMethodException e) { /* No parameter sets can be created */ }
        return creatableTypes;
    }

    private void onAddParameterSet(ConfigGroup parentGroup, String setType) {
        String newIdentifier = JOptionPane.showInputDialog(this, "Enter identifier for new '" + setType + "':", "Add Parameter Set", JOptionPane.PLAIN_MESSAGE);
        if (newIdentifier == null || newIdentifier.trim().isEmpty()) return;
        newIdentifier = newIdentifier.trim();

        try {
            ConfigGroup newSet = parentGroup.createParameterSet(setType);
            if (!setIdentifierForNewSet(newSet, newIdentifier)) {
                System.err.println("Could not set identifier for the new set of type '" + setType + "'.");
            }

            if (newSet instanceof ScoringParameterSet) {
                ScoringParameterSet newScoringSet = (ScoringParameterSet) newSet;
                for (String act : new String[]{"home", "work", "education", "shop", "other"}) newScoringSet.addActivityParams(new ActivityParams(act));
                for (String mode : new String[]{"car", "pt", "walk", "bike"}) newScoringSet.addModeParams(new ModeParams(mode));
            }
            parentGroup.addParameterSet(newSet);
            saveAndRefresh();
        } catch (Exception e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(this, "Failed to add parameter set of type '" + setType + "'.\nError: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void addConfigGroupToPanel(JPanel container, final ConfigGroup group, final ConfigGroup parentGroup, final ConfigGroup defaultGroup, int level) {
        JPanel groupPanel = new JPanel(new BorderLayout(5, 5));
        groupPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        if (level == 0) {
            JPanel headerPanel = new JPanel(new BorderLayout());
            JLabel moduleHeaderLabel = new JLabel("Module: " + group.getName() + " (" + group.getClass().getSimpleName() + ")");
            moduleHeaderLabel.setFont(moduleHeaderLabel.getFont().deriveFont(Font.BOLD, 14f));
            headerPanel.add(moduleHeaderLabel, BorderLayout.CENTER);
            
            if (!this.defaultModuleNames.contains(group.getName())) {
                JButton removeModuleButton = createRemoveButton("Remove this entire module");
                removeModuleButton.addActionListener(e -> {
                    if (JOptionPane.showConfirmDialog(this, "Permanently remove module '" + group.getName() + "' and all its settings?", "Confirm", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION) {
                        configToEdit.removeModule(group.getName());
                        saveAndRefresh();
                    }
                });
                headerPanel.add(removeModuleButton, BorderLayout.EAST);
            }
            
            headerPanel.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Color.GRAY));
            groupPanel.add(headerPanel, BorderLayout.NORTH);
            groupPanel.setBorder(new EmptyBorder(10, 0, 10, 0));
        } else {
            String title = " " + getSetIdentifier(group) + " ";
            groupPanel.setBorder(BorderFactory.createTitledBorder(BorderFactory.createEtchedBorder(), title, TitledBorder.LEADING, TitledBorder.TOP, null, Color.BLUE.darker()));
            JButton removeSetButton = createRemoveButton("Remove this parameter set");
            removeSetButton.addActionListener(e -> {
                if (JOptionPane.showConfirmDialog(this, "Remove set '" + getSetIdentifier(group) + "'?", "Confirm", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                    parentGroup.removeParameterSet(group);
                    saveAndRefresh();
                }
            });
            JPanel headerPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
            headerPanel.setOpaque(false);
            headerPanel.add(removeSetButton);
            groupPanel.add(headerPanel, BorderLayout.NORTH);
        }

        // Pass the corresponding 'defaultGroup' to the panel creator.
        JPanel simpleParamsPanel = createSimpleParamsPanel(group, defaultGroup);
        if (simpleParamsPanel.getComponentCount() > 0) groupPanel.add(simpleParamsPanel, BorderLayout.CENTER);
        
        JPanel childrenAndButtonsPanel = new JPanel();
        childrenAndButtonsPanel.setLayout(new BoxLayout(childrenAndButtonsPanel, BoxLayout.Y_AXIS));
        childrenAndButtonsPanel.setBorder(new EmptyBorder(0, 20, 0, 0));
        childrenAndButtonsPanel.setOpaque(false);
        
        JPanel addButtonsPanel = createAddButtonsPanel(group, level);
        
        childrenAndButtonsPanel.add(addButtonsPanel);
        // Pass the 'defaultGroup' (which acts as the default parent) to the child finder.
        findAndDisplayChildSets(childrenAndButtonsPanel, group, defaultGroup, level);
        if (childrenAndButtonsPanel.getComponentCount() > 0) groupPanel.add(childrenAndButtonsPanel, BorderLayout.SOUTH);
        
        container.add(groupPanel);
    }
    
    private Config cloneConfig(Config original) {
        try {
            StringWriter stringWriter = new StringWriter();
            new ConfigWriter(original).writeStream(stringWriter);
            String configAsString = stringWriter.toString();
            ByteArrayInputStream bais = new ByteArrayInputStream(configAsString.getBytes(StandardCharsets.UTF_8));
//            Config clonedConfig = ConfigUtils.createConfig();
            Config clonedConfig = new Config(); 

            new ConfigReader(clonedConfig).parse(bais);
            return clonedConfig;
        } catch (Exception e) { throw new RuntimeException("Could not clone MATSim config object.", e); }
    }
    
    private void onViewXml() {
        if (!applyUiChanges()) {
            JOptionPane.showMessageDialog(this, "Please fix the invalid parameters before viewing the XML.", "Action Blocked", JOptionPane.WARNING_MESSAGE);
            return;
        }

        EditorDialogConfigXml xmlDialog = new EditorDialogConfigXml(this, this.configToEdit);
        xmlDialog.setVisible(true);
        String updatedXml = xmlDialog.getUpdatedXmlText();

        if (updatedXml != null) {
            try {
                
                Config newConfig = ConfigUtils.createConfig();

                ByteArrayInputStream bais = new ByteArrayInputStream(updatedXml.getBytes(StandardCharsets.UTF_8));
                new ConfigReader(newConfig).parse(bais);

                // Rebuild the main config object. It now contains the user's values
                // AND all the original comments.
                configToEdit.getModules().clear();
                for (Map.Entry<String, ConfigGroup> entry : newConfig.getModules().entrySet()) {
                    configToEdit.addModule(entry.getValue());
                }

                // With the comments preserved, ensure the UI reflects the current
                // state of the "Show Comments" switch.
                this.showComments = this.showCommentsSwitch.isSelected();
                
                // Refresh the UI. The comments will now be present and will be
                // displayed or hidden correctly based on the switch state.
                populateConfigData();

            } catch (Exception e) {
                JOptionPane.showMessageDialog(this, "Could not parse XML source. The changes from the XML editor were NOT applied.\n\nError: " + e.getMessage(), "XML Parsing Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }
    
    /**
     * Fetches default comments from a fresh Config instance and applies them
     * to the currently loaded configuration. Then, it refreshes the UI.
     * This is used to "reload" comments on demand.
     */
    private void reloadCommentsAndRefresh() {
        // It's crucial to apply any pending text field edits before we proceed.
        if (!applyUiChanges()) {
            // If applying changes fails, the user has an error. Abort the operation
            // and revert the checkbox state, as we could not fulfill the request.
            this.showComments = false;
            this.showCommentsSwitch.setSelected(false);
            return;
        }

        // 1. Create a comprehensive map of all known default modules with their comments.
        Map<String, ConfigGroup> defaultConfigGroups = new HashMap<>();

        // 2. First, populate it with the standard modules.
        for (ConfigGroup group : ConfigUtils.createConfig().getModules().values()) {
            defaultConfigGroups.put(group.getName(), group);
        }

        // 3. Next, discover all other available modules (contribs, extensions)
        //    using the same reflection mechanism as the "Add Custom Module" feature.
        try {
            Reflections reflections = new Reflections("org.matsim.contrib", "ch.sbb.matsim", "org.matsim.core.config.groups", Scanners.SubTypes);
            Set<Class<? extends ConfigGroup>> moduleClasses = reflections.getSubTypesOf(ConfigGroup.class);

            for (Class<? extends ConfigGroup> moduleClass : moduleClasses) {
                // Skip abstract classes and modules we already have from the default config.
                if (java.lang.reflect.Modifier.isAbstract(moduleClass.getModifiers())) {
                    continue;
                }
                try {
                    ConfigGroup instance = moduleClass.getDeclaredConstructor().newInstance();
                    // Add it if it's not already in our map.
                    if (!defaultConfigGroups.containsKey(instance.getName())) {
                        defaultConfigGroups.put(instance.getName(), instance);
                    }
                } catch (Exception e) {
                    // Silently ignore modules that cannot be instantiated.
                }
            }
        } catch (Exception e) {
            System.err.println("Warning: Could not scan for all module types to reload comments. " + e.getMessage());
        }

        // 4. Iterate through the modules in our *current* config and restore comments.
        for (ConfigGroup currentGroup : configToEdit.getModules().values()) {
            // Find the corresponding module in our comprehensive map of default configs.
            ConfigGroup defaultGroup = defaultConfigGroups.get(currentGroup.getName());

            if (defaultGroup != null) {
                // Copy the comments from the default module into our current module.
                currentGroup.getComments().clear();
                currentGroup.getComments().putAll(defaultGroup.getComments());
            }
        }

        // 5. Refresh the entire UI to display the newly added comments.
        populateConfigData();
    }

    private void populateConfigData() {
        int selectedIndex = tabbedPane.getSelectedIndex();
        tabbedPane.removeAll();
        paramGroupFieldsMap.clear();
        List<String> moduleNames = new ArrayList<>(configToEdit.getModules().keySet());
        moduleNames.sort(String.CASE_INSENSITIVE_ORDER);
        int tabIndex = 0;
        for (String moduleName : moduleNames) {
            ConfigGroup group = configToEdit.getModules().get(moduleName);
            if (group == null) continue;
            try {
                JPanel moduleTabContentPanel = createModuleTabPanel(group);
                JScrollPane scrollPane = new JScrollPane(moduleTabContentPanel, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
                scrollPane.getVerticalScrollBar().setUnitIncrement(16);
                scrollPane.setBorder(BorderFactory.createEmptyBorder());
                tabbedPane.addTab(moduleName, scrollPane);

                if (this.showReducedConfig) {
                    if (hasChanges(group)) {
                        // This tab has changes, so we can give it a distinct color (e.g., blue)
                        // to indicate that it has modified parameters.
                        tabbedPane.setForegroundAt(tabIndex, Color.BLUE);
                    } else {
                        // This tab has no changes from the default, so we gray it out
                        // to indicate that it contains only default values.
                        tabbedPane.setForegroundAt(tabIndex, Color.LIGHT_GRAY);
                    }
                } else {
                    // If not in reduced config mode, all tabs are black.
                    tabbedPane.setForegroundAt(tabIndex, Color.BLACK);
                }
                tabIndex++;
            } catch (Exception ex) {
                addErrorTab(moduleName, group, ex);
            }
        }
        if (selectedIndex >= 0 && selectedIndex < tabbedPane.getTabCount()) {
            tabbedPane.setSelectedIndex(selectedIndex);
        }
        revalidate();
        repaint();
    }
    
    private JButton createRemoveButton(String tooltip) {
        JButton removeButton;
        java.net.URL iconURL = getClass().getResource("/org/matsim/studio/icons/remove.png");
        if (iconURL != null) removeButton = new JButton(new ImageIcon(iconURL));
        else { removeButton = new JButton("X"); removeButton.setForeground(Color.RED); }
        removeButton.setToolTipText(tooltip);
        removeButton.setMargin(new Insets(0, 2, 0, 2));
        removeButton.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        removeButton.setContentAreaFilled(false);
        return removeButton;
    }

    private void onCancel() {
        configToEdit.getModules().clear();
        for (Map.Entry<String, ConfigGroup> entry : backupConfig.getModules().entrySet()) configToEdit.addModule(entry.getValue());
        this.applied = false;
        dispose();
    }
    
    private void findAndDisplayChildSets(JPanel container, ConfigGroup group, ConfigGroup defaultParent, int level) {
        Map<String, ? extends Collection<? extends ConfigGroup>> paramSets = group.getParameterSets();
        if (paramSets == null) return;

        List<ConfigGroup> allSets = new ArrayList<>();
        paramSets.values().forEach(allSets::addAll);
        allSets.sort(Comparator.comparing(this::getSetIdentifier));

        // For comparison, create a map of the default children for easy lookup.
        // This is only needed in reduced config mode.
        Map<String, ConfigGroup> defaultChildrenById = new HashMap<>();
        if (this.showReducedConfig && defaultParent != null) {
            for (Collection<? extends ConfigGroup> setCollection : defaultParent.getParameterSets().values()) {
                for (ConfigGroup g : setCollection) {
                    defaultChildrenById.put(getSetIdentifier(g), g);
                }
            }
        }

        for (ConfigGroup paramSet : allSets) {
            // Find the corresponding default child for this specific paramSet.
            ConfigGroup defaultChild = defaultChildrenById.get(getSetIdentifier(paramSet));

            // A set has changes if it's new (no default child) or if it's different from the default.
            // We display the parameter set if we are not in reduced mode OR if it has changes.
            if (!this.showReducedConfig || defaultChild == null || areDifferent(paramSet, defaultChild)) {
                // Note the last parameter in the recursive call: we pass the found 'defaultChild'.
                addConfigGroupToPanel(container, paramSet, group, defaultChild, level + 1);
            }
        }
    }

    private JPanel createModuleTabPanel(ConfigGroup group) {
        JPanel containerPanel = new JPanel();
        containerPanel.setLayout(new BoxLayout(containerPanel, BoxLayout.Y_AXIS));
        containerPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

        ConfigGroup defaultGroup = null;
        // We only need to create the default group if we are in reduced config mode.
        if (this.showReducedConfig) {
            try {
                // This is safe here, as top-level modules have a no-arg constructor.
                defaultGroup = group.getClass().getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                System.err.println("Could not create default instance for module " + group.getName() +
                                   ". Reduced config view may not be accurate for this tab. Error: " + e.getMessage());
            }
        }

        // Pass the newly created defaultGroup into the main panel builder.
        addConfigGroupToPanel(containerPanel, group, null, defaultGroup, 0);
        containerPanel.add(Box.createVerticalGlue());
        return containerPanel;
    }

    private JPanel createSimpleParamsPanel(ConfigGroup group, final ConfigGroup defaultConfig) {
        JPanel panel = new JPanel(new GridBagLayout());
        if (group.getParams().isEmpty()) return panel;

        // This method now uses the passed-in 'defaultConfig' for comparison.

        Map<String, String> comments = group.getComments();
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 4, 2, 4);
        gbc.anchor = GridBagConstraints.WEST;
        int gridY = 0;
        Map<String, JTextField> fieldsForThisGroup = new HashMap<>();
        Map<String, String> sortedParams = new TreeMap<>(group.getParams());

        for (Map.Entry<String, String> entry : sortedParams.entrySet()) {
            String paramName = entry.getKey();
            String paramValue = entry.getValue();

            if (this.showReducedConfig && defaultConfig != null) {
                String defaultValue = defaultConfig.getParams().get(paramName);
                // If a default value exists for this parameter AND it's the same as the current value, skip rendering it.
                if (defaultValue != null && defaultValue.equals(paramValue)) {
                    continue; // Go to the next parameter in the loop
                }
            }

            gbc.gridy = gridY;
            gbc.gridwidth = 1;
            JLabel paramNameLabel = new JLabel(paramName + ":");
            gbc.gridx = 0;
            gbc.fill = GridBagConstraints.NONE;
            gbc.weightx = 0.3;
            panel.add(paramNameLabel, gbc);
            JTextField valueField = new JTextField(paramValue != null ? paramValue : "", 35);
            gbc.gridx = 1;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.weightx = 0.7;
            panel.add(valueField, gbc);
            paramNameLabel.setLabelFor(valueField);
            fieldsForThisGroup.put(paramName, valueField);

            gridY++; 

            String comment = comments.get(paramName);
            if (this.showComments && comment != null && !comment.trim().isEmpty()) {
                gbc.gridy = gridY;
                gbc.gridx = 0;
                gbc.gridwidth = 2;
                gbc.insets = new Insets(0, 4, 8, 4);
                String formattedComment = "<html><p style=\"width:500px;\"><i>" + comment.replace("\n", "<br>") + "</i></p></html>";
                JLabel commentLabel = new JLabel(formattedComment);
                commentLabel.setForeground(Color.GRAY);
                panel.add(commentLabel, gbc);
                gridY++;
            }

            gbc.insets = new Insets(2, 4, 2, 4);
            gbc.gridwidth = 1;
        }
        paramGroupFieldsMap.put(group, fieldsForThisGroup);

        if (gridY == 0 && this.showReducedConfig) {
            panel.add(new JLabel("No parameters have been changed from their default values."));
        }

        return panel;
    }
    
    private String getSetIdentifier(ConfigGroup set) {
        if (set instanceof ActivityParams) return ((ActivityParams) set).getActivityType();
        if (set instanceof ModeParams) return ((ModeParams) set).getMode();
        if (set instanceof StrategySettings) return ((StrategySettings) set).getStrategyName();
        if (set instanceof TeleportedModeParams) return ((TeleportedModeParams) set).getMode();
        if (set instanceof ScoringParameterSet) return ((ScoringParameterSet) set).getSubpopulation();
        return set.getName();
    }
    
    private boolean setIdentifierForNewSet(ConfigGroup set, String identifier) {
        if (set instanceof ModeParams) { ((ModeParams) set).setMode(identifier); return true; }
        if (set instanceof ActivityParams) { ((ActivityParams) set).setActivityType(identifier); return true; }
        if (set instanceof StrategySettings) { ((StrategySettings) set).setStrategyName(identifier); return true; }
        if (set instanceof TeleportedModeParams) { ((TeleportedModeParams) set).setMode(identifier); return true; }
        if (set instanceof ScoringParameterSet) { ((ScoringParameterSet) set).setSubpopulation(identifier); return true; }
        if (set.getParams().containsKey("name")) { set.addParam("name", identifier); return true; }
        if (set.getParams().containsKey("mode")) { set.addParam("mode", identifier); return true; }
        return false;
    }
    
    /**
     * Checks if a ConfigGroup has any parameters or parameter sets that differ
     * from a freshly created default instance of itself. This is the entry point
     * for the recursive check.
     *
     * @param group The ConfigGroup to check.
     * @return true if there are any changes, false otherwise.
     */
    private boolean hasChanges(ConfigGroup group) {
        if (group == null) return false;
        try {
            // Create the corresponding default config to compare against.
            ConfigGroup defaultConfig = group.getClass().getDeclaredConstructor().newInstance();
            // Call the recursive helper to perform the detailed comparison.
            return areDifferent(group, defaultConfig);
        } catch (Exception e) {
            System.err.println("Could not create default instance for " + group.getClass().getName() + ". Assuming it has changes. Error: " + e.getMessage());
            return true; // Fail safe: if we can't create a default, assume it's changed.
        }
    }
    
    /**
     * Recursively compares two ConfigGroup objects (a current one and a default one)
     * to see if there are any differences in their parameters or child parameter sets.
     *
     * @param current The current ConfigGroup with user values.
     * @param defaultGroup The default ConfigGroup to compare against.
     * @return true if any differences are found, false otherwise.
     */
    private boolean areDifferent(ConfigGroup current, ConfigGroup defaultGroup) {
        // 1. Compare simple parameters using Map.equals(), which is robust.
        if (!current.getParams().equals(defaultGroup.getParams())) {
            return true;
        }

        // 2. Compare the structure of the nested parameter sets.
        Map<String, ? extends Collection<? extends ConfigGroup>> currentSetsMap = current.getParameterSets();
        Map<String, ? extends Collection<? extends ConfigGroup>> defaultSetsMap = defaultGroup.getParameterSets();

        // Check if the types of available sets are different (e.g., one has 'activityParams' and the other doesn't).
        if (!currentSetsMap.keySet().equals(defaultSetsMap.keySet())) {
            return true;
        }

        // 3. For each type of parameter set (e.g., "strategySettings"), compare the individual sets.
        for (String setType : currentSetsMap.keySet()) {
            Collection<? extends ConfigGroup> currentSets = currentSetsMap.get(setType);
            Collection<? extends ConfigGroup> defaultSets = defaultSetsMap.get(setType);

            // Check if the number of sets is different (e.g., user added a new strategy).
            if (currentSets.size() != defaultSets.size()) {
                return true;
            }

            // Map sets by their unique identifier for direct comparison.
            Map<String, ConfigGroup> currentChildrenById = new HashMap<>();
            for (ConfigGroup g : currentSets) {
                currentChildrenById.put(getSetIdentifier(g), g);
            }
            
            Map<String, ConfigGroup> defaultChildrenById = new HashMap<>();
            for (ConfigGroup g : defaultSets) {
                defaultChildrenById.put(getSetIdentifier(g), g);
            }

            // Check if the set of identifiers has changed (e.g., default has mode 'car', current has 'truck').
            if (!currentChildrenById.keySet().equals(defaultChildrenById.keySet())) {
                return true;
            }

            // 4. If structures match, recursively check each corresponding child pair for changes.
            for (String id : currentChildrenById.keySet()) {
                ConfigGroup currentChild = currentChildrenById.get(id);
                ConfigGroup defaultChild = defaultChildrenById.get(id); // This will exist due to the keySet check above.

                if (areDifferent(currentChild, defaultChild)) { // The recursive call
                    return true;
                }
            }
        }
        
        // If we've gotten this far, no differences were found at this level or any level below it.
        return false;
    }
    
    private void addErrorTab(String moduleName, ConfigGroup group, Exception ex) {
        JPanel errorPanel = new JPanel(new BorderLayout());
        errorPanel.setBorder(new EmptyBorder(15, 15, 15, 15));
        JTextArea errorArea = new JTextArea("Warning: The configuration module '" + moduleName + "' could not be processed correctly.\n\nDetails: " + ex.toString());
        errorArea.setEditable(false);
        errorArea.setLineWrap(true);
        errorArea.setWrapStyleWord(true);
        errorArea.setForeground(Color.RED.darker());
        errorArea.setBackground(getBackground());
        errorPanel.add(new JScrollPane(errorArea), BorderLayout.CENTER);
        tabbedPane.addTab("<html><font color='red'>" + moduleName + " (Error)</font></html>", errorPanel);
    }
    
    public boolean isApplied() { return applied; }
    public Config getUpdatedConfig() { return configToEdit; }
}