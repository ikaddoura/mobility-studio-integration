/* *********************************************************************** *
 * project: org.matsim.*
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2008 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */

package de.mobilitystudio.gui;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RunnableFuture;

import javax.swing.GroupLayout;
import javax.swing.GroupLayout.Alignment;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuBar;
import javax.swing.JOptionPane;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.LayoutStyle.ComponentPlacement;
import javax.swing.SwingUtilities;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.ConfigWriter;
import org.matsim.core.gbl.Gbl;
import org.matsim.core.utils.io.IOUtils;

import de.mobilitystudio.config.EditorDialogConfig;

/**
 * A GUI for running MATSim simulations, with an integrated configuration editor.
 * This class enhances the base MATSim GUI by adding the ability to create new
 * configuration files from scratch and edit existing ones using a structured editor dialog.
 *
 * @author mrieser / Senozon AG (Original)
 * @author Gemini (Enhancements)
 * @author ikaddoura (Enhancements)
 */
public class GuiWithConfigEditor extends JFrame {
	
	private static final Logger log = LogManager.getLogger(GuiWithConfigEditor.class);

	private static final long serialVersionUID = 2L;

	private static final JLabel lblFilepaths = new JLabel(
			"Filepaths must either be absolute or relative to the location of the config file.");
	private static final JLabel lblJavaLocation = new JLabel("Java Location:");
	private static final JLabel lblConfigurationFile = new JLabel("Configuration file:");
	private static final JLabel lblOutputDirectory = new JLabel("Output Directory:");
	private static final JLabel lblMemory = new JLabel("Memory:");
	private static final JLabel lblMb = new JLabel("MB");
	private static final JLabel lblYouAreUsingJavaVersion = new JLabel("You are using Java version:");
	private static final JLabel lblYouAreUsingMATSimVersion = new JLabel("You are using MATSim version:");

	private JTextField txtConfigfilename;
	private JTextField txtMatsimversion;
	private JTextField txtRam;
	private JTextField txtJvmversion;
	private JTextField txtJvmlocation;
	private JTextField txtOutput;

	private JButton btnStartMatsim;
	private JProgressBar progressBar;

	private JTextArea textStdOut;
	private JScrollPane scrollPane;
	private JButton btnEdit;

	Map<String, JButton> preprocessButtons = new LinkedHashMap<>();
	Map<String, JButton> postprocessButtons = new LinkedHashMap<>();

	private volatile ExeRunner exeRunner = null;

	private JMenuBar menuBar;

	private JTextArea textErrOut;
	private final String mainClass;

	private File configFile;
	private File lastUsedDirectory;

	/**
	 * This is the working directory for the simulation. If it is null, the working directory is the directory of the config file.
	 */
	private File workingDirectory = null;

	private GuiWithConfigEditor(final String title, final Class<?> mainClass) {
		setTitle(title);
		this.mainClass = mainClass.getCanonicalName();
		setDefaultCloseOperation(DISPOSE_ON_CLOSE);
	}

	private void createLayout() {
		this.lastUsedDirectory = new File(".");

		txtConfigfilename = new JTextField();
		txtConfigfilename.setText("");
		txtConfigfilename.setColumns(10);

		btnStartMatsim = new JButton("Start MATSim");
		btnStartMatsim.setEnabled(false);

		for (JButton button : preprocessButtons.values()) {
			button.setEnabled(false);
		}
		for (JButton button : postprocessButtons.values()) {
			button.setEnabled(false);
		}

		JButton btnChoose = new JButton("Choose");
		btnChoose.addActionListener(e -> {
			JFileChooser chooser = new JFileChooser();
			chooser.setCurrentDirectory(lastUsedDirectory);
			int result = chooser.showOpenDialog(null);
			if (result == JFileChooser.APPROVE_OPTION) {
				File f = chooser.getSelectedFile();
				lastUsedDirectory = f.getParentFile();
				loadConfigFile(f);
			}
		});

		JButton btnCreateConfig = new JButton("Create Config");
		btnCreateConfig.addActionListener(e -> {
			JFileChooser chooser = new JFileChooser();
			chooser.setCurrentDirectory(lastUsedDirectory);
			chooser.setSelectedFile(new File("config.xml"));
			chooser.setDialogTitle("Save New Config File");
			int result = chooser.showSaveDialog(GuiWithConfigEditor.this);

			if (result == JFileChooser.APPROVE_OPTION) {
				File selectedFile = chooser.getSelectedFile();
				try {
					Config newConfig = ConfigUtils.createConfig();
					// a new config file was created
					// add the input files we just may have written into the workind directory
					
					File parentDir = selectedFile.getParentFile();
					if (parentDir != null && parentDir.isDirectory()) {
						
						String networkFileName = "network.xml.gz";
						File networkFile = new File(parentDir, networkFileName);
						if (networkFile.exists()) {
					    	log.info("Adding network.xml.gz to config.");
							newConfig.network().setInputFile(networkFileName);
						}
						
						String scheduleFileName = "schedule.xml.gz";
						String vehiclesFileName = "vehicles.xml.gz";
						File scheduleFile = new File(parentDir, scheduleFileName);
						File vehiclesFile = new File(parentDir, vehiclesFileName);
						if (scheduleFile.exists() && vehiclesFile.exists()) {
					    	log.info("Adding schedule.xml.gz and vehicles.xml.gz to transit config group.");
							newConfig.transit().setTransitScheduleFile(scheduleFileName);
							newConfig.transit().setVehiclesFile(vehiclesFileName);
							newConfig.transit().setUsingTransitInMobsim(true);
							newConfig.transit().setUseTransit(true);
						}
						
						String populationFileName = "population.xml.gz";
						String facilitiesFileName = "facilities.xml.gz";

						File populationFile = new File(parentDir, populationFileName);
						File facilitiesFile = new File(parentDir, facilitiesFileName);

						if (populationFile.exists()) {
					    	log.info("Adding population.xml.gz to config.");
							newConfig.plans().setInputFile(populationFileName);
							
							if (facilitiesFile.exists()) {
						    	log.info("Adding facilities.xml.gz to config.");
								newConfig.facilities().setInputFile(facilitiesFileName);
							}
						}
					}
					
					new ConfigWriter(newConfig).write(selectedFile.getAbsolutePath());
					loadConfigFile(selectedFile);
					lastUsedDirectory = selectedFile.getParentFile();
				} catch (Exception ex) {
					JOptionPane.showMessageDialog(GuiWithConfigEditor.this,
							"Could not save the config file.\n\nError: " + ex.getMessage(),
							"Save Error",
							JOptionPane.ERROR_MESSAGE);
				}
			}
		});

		this.btnEdit = new JButton("Edit…");
		this.btnEdit.setEnabled(false);
		this.btnEdit.addActionListener(e -> {
			if (configFile == null || !configFile.exists()) {
				JOptionPane.showMessageDialog(GuiWithConfigEditor.this,
						"Please load a configuration file first.",
						"No File Loaded",
						JOptionPane.WARNING_MESSAGE);
				return;
			}
			try {
				Config config = ConfigUtils.createConfig();
				ConfigUtils.loadConfig(config, configFile.getAbsolutePath());
				EditorDialogConfig dialog = new EditorDialogConfig(GuiWithConfigEditor.this, config, configFile.getAbsolutePath());
				dialog.setTitle("Editing: " + configFile.getName());
				dialog.setLocationRelativeTo(GuiWithConfigEditor.this);
				dialog.setVisible(true);

				if (dialog.isApplied()) {
		            Config updatedConfig = dialog.getUpdatedConfig();
		            new ConfigWriter(updatedConfig).write(configFile.getAbsolutePath());
		            // Update the GUI directly from the 'updatedConfig' object
		            // to avoid file system cache/latency issues.
		            
		            updateGuiWithConfig(updatedConfig, configFile);
		        }
				
			} catch (Exception ex) {
				JOptionPane.showMessageDialog(GuiWithConfigEditor.this,
						"Could not load or process the configuration file.\n\nError: " + ex.getMessage(),
						"Editor Error",
						JOptionPane.ERROR_MESSAGE);
			}
		});

		txtMatsimversion = new JTextField();
		txtMatsimversion.setEditable(false);
		txtMatsimversion.setText(Gbl.getBuildInfoString());
		txtMatsimversion.setColumns(10);

		txtRam = new JTextField();
		txtRam.setText("1024");
		txtRam.setColumns(10);

		String javaVersion = System.getProperty("java.version")
				+ "; "
				+ System.getProperty("java.vm.vendor")
				+ "; "
				+ System.getProperty("java.vm.info")
				+ "; "
				+ System.getProperty("sun.arch.data.model")
				+ "-bit";

		txtJvmversion = new JTextField();
		txtJvmversion.setEditable(false);
		txtJvmversion.setText(javaVersion);
		txtJvmversion.setColumns(10);

		String jvmLocation;
		if (System.getProperty("os.name").startsWith("Win")) {
			jvmLocation = System.getProperties().getProperty("java.home")
					+ File.separator
					+ "bin"
					+ File.separator
					+ "java.exe";
		} else {
			jvmLocation = System.getProperties().getProperty("java.home")
					+ File.separator
					+ "bin"
					+ File.separator
					+ "java";
		}

		txtJvmlocation = new JTextField();
		txtJvmlocation.setEditable(false);
		txtJvmlocation.setText(jvmLocation);
		txtJvmlocation.setColumns(10);

		txtOutput = new JTextField();
		txtOutput.setEditable(false);
		txtOutput.setText("");
		txtOutput.setColumns(10);

		progressBar = new JProgressBar();
		progressBar.setEnabled(false);
		progressBar.setIndeterminate(true);
		progressBar.setVisible(false);

		btnStartMatsim.addActionListener(e -> {
			if (exeRunner == null) {
				startMATSim();
			} else {
				stopMATSim();
			}
		});

		JButton btnOpen = new JButton("Open");
		btnOpen.addActionListener(e -> {
			if (!txtOutput.getText().isEmpty()) {
				File f = new File(txtOutput.getText());
				if (f.exists() && f.isDirectory()) {
					try {
						Desktop.getDesktop().open(f);
					} catch (IOException ex) {
						JOptionPane.showMessageDialog(GuiWithConfigEditor.this,
								"Could not open the directory.\n\nError: " + ex.getMessage(),
								"Open Error",
								JOptionPane.ERROR_MESSAGE);
						ex.printStackTrace();
					}
				} else {
					JOptionPane.showMessageDialog(GuiWithConfigEditor.this,
							"The output directory does not (yet) exist.",
							"Directory Not Found",
							JOptionPane.WARNING_MESSAGE);
				}
			}
		});

		JButton btnDelete = new JButton("Delete");
		btnDelete.addActionListener(e -> {
			if (!txtOutput.getText().isEmpty()) {
				File f = new File(txtOutput.getText());
				if (f.exists() && f.isDirectory()) {
					int i = JOptionPane.showOptionDialog(GuiWithConfigEditor.this,
							"Do you really want to delete the output directory? This action cannot be undone.",
							"Delete Output Directory", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null,
							new String[] { "Cancel", "Delete" }, "Cancel");
					if (i == 1) { // User selected "Delete"
						try {
							IOUtils.deleteDirectoryRecursively(f.toPath());
						} catch (Exception ex) {
							JOptionPane.showMessageDialog(GuiWithConfigEditor.this,
									"Could not delete the directory.\n\nError: " + ex.getMessage(),
									"Delete Error",
									JOptionPane.ERROR_MESSAGE);
							ex.printStackTrace();
						}
					}
				} else {
					JOptionPane.showMessageDialog(GuiWithConfigEditor.this,
							"The output directory does not exist.",
							"Directory Not Found",
							JOptionPane.WARNING_MESSAGE);
				}
			}
		});
		
		JTabbedPane tabbedPane = new JTabbedPane(JTabbedPane.TOP);

		GroupLayout groupLayout = new GroupLayout(getContentPane());

		final GroupLayout.SequentialGroup prebuttonsSequentialGroup = groupLayout.createSequentialGroup();
		final GroupLayout.ParallelGroup prebuttonsParallelGroup = groupLayout.createParallelGroup();
		for (JButton button : preprocessButtons.values()) {
			prebuttonsSequentialGroup.addComponent(button);
			prebuttonsParallelGroup.addComponent(button);
		}

		final GroupLayout.SequentialGroup postbuttonsSequentialGroup = groupLayout.createSequentialGroup();
		final GroupLayout.ParallelGroup postbuttonsParallelGroup = groupLayout.createParallelGroup();
		for (JButton button : postprocessButtons.values()) {
			postbuttonsSequentialGroup.addComponent(button);
			postbuttonsParallelGroup.addComponent(button);
		}

		groupLayout.setHorizontalGroup(groupLayout.createParallelGroup(Alignment.TRAILING)
				.addGroup(Alignment.LEADING, groupLayout.createSequentialGroup()
						.addContainerGap()
						.addGroup(groupLayout.createParallelGroup(Alignment.LEADING)
								.addComponent(tabbedPane, GroupLayout.DEFAULT_SIZE, 729, Short.MAX_VALUE)
								.addComponent(lblFilepaths)
								.addGroup(prebuttonsSequentialGroup)
								.addGroup(postbuttonsSequentialGroup)
								.addGroup(groupLayout.createSequentialGroup()
										.addGroup(groupLayout.createParallelGroup(Alignment.LEADING)
												.addComponent(lblYouAreUsingMATSimVersion)
												.addComponent(lblYouAreUsingJavaVersion)
												.addComponent(lblJavaLocation)
												.addComponent(lblConfigurationFile)
												.addComponent(lblOutputDirectory)
												.addComponent(lblMemory)
												.addComponent(btnStartMatsim))
										.addPreferredGap(ComponentPlacement.RELATED)
										.addGroup(groupLayout.createParallelGroup(Alignment.LEADING)
												.addGroup(groupLayout.createSequentialGroup()
														.addComponent(txtRam, GroupLayout.PREFERRED_SIZE, 69,
																GroupLayout.PREFERRED_SIZE)
														.addPreferredGap(ComponentPlacement.RELATED)
														.addComponent(lblMb))
												.addComponent(txtMatsimversion, GroupLayout.DEFAULT_SIZE, 285,
														Short.MAX_VALUE)
												.addComponent(txtJvmversion, GroupLayout.DEFAULT_SIZE, 285,
														Short.MAX_VALUE)
												.addComponent(txtJvmlocation, GroupLayout.DEFAULT_SIZE, 285,
														Short.MAX_VALUE)
												// --- LAYOUT MODIFICATION ---
												.addGroup(groupLayout.createSequentialGroup()
														.addComponent(txtConfigfilename, GroupLayout.DEFAULT_SIZE, 188, Short.MAX_VALUE)
														.addPreferredGap(ComponentPlacement.RELATED)
														.addComponent(btnChoose)
														.addPreferredGap(ComponentPlacement.RELATED)
														.addComponent(btnCreateConfig) // Added Button
														.addPreferredGap(ComponentPlacement.RELATED)
														.addComponent(btnEdit))
												.addComponent(progressBar, GroupLayout.DEFAULT_SIZE, 285,
														Short.MAX_VALUE)
												.addGroup(groupLayout.createSequentialGroup()
														.addComponent(txtOutput, GroupLayout.DEFAULT_SIZE, 112,
																Short.MAX_VALUE)
														.addPreferredGap(ComponentPlacement.RELATED)
														.addComponent(btnOpen)
														.addPreferredGap(ComponentPlacement.RELATED)
														.addComponent(btnDelete)))))
						.addContainerGap()));
		groupLayout.setVerticalGroup(groupLayout.createParallelGroup(Alignment.LEADING)
				.addGroup(groupLayout.createSequentialGroup()
						.addContainerGap()
						.addGroup(groupLayout.createParallelGroup(Alignment.BASELINE)
								.addComponent(lblYouAreUsingMATSimVersion)
								.addComponent(txtMatsimversion, GroupLayout.PREFERRED_SIZE, GroupLayout.DEFAULT_SIZE,
										GroupLayout.PREFERRED_SIZE))
						.addPreferredGap(ComponentPlacement.RELATED)
						.addGroup(groupLayout.createParallelGroup(Alignment.BASELINE)
								.addComponent(lblYouAreUsingJavaVersion)
								.addComponent(txtJvmversion, GroupLayout.PREFERRED_SIZE, GroupLayout.DEFAULT_SIZE,
										GroupLayout.PREFERRED_SIZE))
						.addPreferredGap(ComponentPlacement.RELATED)
						.addGroup(groupLayout.createParallelGroup(Alignment.BASELINE)
								.addComponent(lblJavaLocation)
								.addComponent(txtJvmlocation, GroupLayout.PREFERRED_SIZE, GroupLayout.DEFAULT_SIZE,
										GroupLayout.PREFERRED_SIZE))
						.addPreferredGap(ComponentPlacement.RELATED)
						// --- LAYOUT MODIFICATION ---
						.addGroup(groupLayout.createParallelGroup(Alignment.BASELINE)
								.addComponent(lblConfigurationFile)
								.addComponent(txtConfigfilename, GroupLayout.PREFERRED_SIZE, GroupLayout.DEFAULT_SIZE,
										GroupLayout.PREFERRED_SIZE)
								.addComponent(btnChoose)
								.addComponent(btnCreateConfig) // Added Button
								.addComponent(btnEdit))
						.addPreferredGap(ComponentPlacement.RELATED)
						.addComponent(lblFilepaths)
						.addPreferredGap(ComponentPlacement.RELATED)
						.addGroup(groupLayout.createParallelGroup(Alignment.BASELINE)
								.addComponent(lblOutputDirectory)
								.addComponent(txtOutput, GroupLayout.PREFERRED_SIZE, GroupLayout.DEFAULT_SIZE,
										GroupLayout.PREFERRED_SIZE)
								.addComponent(btnDelete)
								.addComponent(btnOpen))
						.addPreferredGap(ComponentPlacement.RELATED)
						.addGroup(groupLayout.createParallelGroup(Alignment.BASELINE)
								.addComponent(lblMemory)
								.addComponent(txtRam, GroupLayout.PREFERRED_SIZE, GroupLayout.DEFAULT_SIZE,
										GroupLayout.PREFERRED_SIZE)
								.addComponent(lblMb))
						.addPreferredGap(ComponentPlacement.UNRELATED)
						.addGroup(prebuttonsParallelGroup)
						.addPreferredGap(ComponentPlacement.UNRELATED)
						.addGroup(groupLayout.createParallelGroup(Alignment.LEADING)
								.addComponent(btnStartMatsim)
								.addComponent(progressBar, GroupLayout.PREFERRED_SIZE, GroupLayout.DEFAULT_SIZE,
										GroupLayout.PREFERRED_SIZE))
						.addPreferredGap(ComponentPlacement.UNRELATED)
						.addGroup(postbuttonsParallelGroup)
						.addPreferredGap(ComponentPlacement.RELATED)
						.addComponent(tabbedPane, GroupLayout.DEFAULT_SIZE, 180, Short.MAX_VALUE)
						.addContainerGap()));

		textStdOut = new JTextArea();
		textStdOut.setWrapStyleWord(true);
		textStdOut.setTabSize(4);
		textStdOut.setEditable(false);
		scrollPane = new JScrollPane(textStdOut);
		tabbedPane.addTab("Output", null, scrollPane, null);

		JScrollPane scrollPane_1 = new JScrollPane();
		tabbedPane.addTab("Warnings & Errors", null, scrollPane_1, null);

		textErrOut = new JTextArea();
		textErrOut.setWrapStyleWord(true);
		textErrOut.setTabSize(4);
		textErrOut.setEditable(false);
		scrollPane_1.setViewportView(textErrOut);

		getContentPane().setLayout(groupLayout);

		menuBar = new JMenuBar();
		setJMenuBar(menuBar);
	}
	
	private void updateGuiWithConfig(Config config, File configFile) {
		
	    txtConfigfilename.setText(configFile.getAbsolutePath());

	    File par = configFile.getParentFile();
	    // Use the provided config object to get the output directory
	    File outputDir = new File(par, config.controller().getOutputDirectory());
	    try {
	        txtOutput.setText(outputDir.getCanonicalPath());
	    } catch (IOException e1) {
	        txtOutput.setText(outputDir.getAbsolutePath());
	    }

	    btnStartMatsim.setEnabled(true);
	    btnEdit.setEnabled(true);
	    for (JButton button : preprocessButtons.values()) {
	        button.setEnabled(true);
	    }
	    for (JButton button : postprocessButtons.values()) {
	        button.setEnabled(true);
	    }
	}

	private void startMATSim() {
		progressBar.setVisible(true);
		progressBar.setEnabled(true);
		this.btnStartMatsim.setEnabled(false);

		textStdOut.setText("");
		textErrOut.setText("");

		String cwd = workingDirectory == null ? new File(txtConfigfilename.getText()).getParent() : workingDirectory.getAbsolutePath();

		new Thread(() -> {
			String classpath = System.getProperty("java.class.path");
			String[] cpParts = classpath.split(File.pathSeparator);
			StringBuilder absoluteClasspath = new StringBuilder();
			for (String cpPart : cpParts) {
				if (absoluteClasspath.length() > 0) {
					absoluteClasspath.append(File.pathSeparatorChar);
				}
				absoluteClasspath.append(new File(cpPart).getAbsolutePath());
			}
			String[] cmdArgs = new String[] { txtJvmlocation.getText(),
					"-cp", absoluteClasspath.toString(),
					"-Xmx" + txtRam.getText() + "m",
					"--add-exports", "java.base/java.lang=ALL-UNNAMED",
					"--add-exports", "java.desktop/sun.awt=ALL-UNNAMED",
					"--add-exports", "java.desktop/sun.java2d=ALL-UNNAMED",
					mainClass, txtConfigfilename.getText() };

			exeRunner = ExeRunner.run(cmdArgs, textStdOut, textErrOut, cwd);
			int exitcode = exeRunner.waitForFinish();
			exeRunner = null;

			SwingUtilities.invokeLater(() -> {
				progressBar.setVisible(false);
				btnStartMatsim.setText("Start MATSim");
				btnStartMatsim.setEnabled(true);
				if (exitcode != 0) {
					textStdOut.append("\n");
					textStdOut.append("The simulation did not run properly. Error/Exit code: " + exitcode);
					textStdOut.setCaretPosition(textStdOut.getDocument().getLength());
					textErrOut.append("\n");
					textErrOut.append("The simulation did not run properly. Error/Exit code: " + exitcode);
					textErrOut.setCaretPosition(textErrOut.getDocument().getLength());
				}
			});

			if (exitcode != 0) {
				throw new RuntimeException("There was a problem running MATSim. exit code: " + exitcode);
			}

		}).start();

		btnStartMatsim.setText("Stop MATSim");
		btnStartMatsim.setEnabled(true);
	}

	public void loadConfigFile(final File configFile) {
	    this.configFile = configFile;
	    String configFilename = configFile.getAbsolutePath();

	    Config config = ConfigUtils.createConfig();
	    try {
	        ConfigUtils.loadConfig(config, configFilename);
	    } catch (Exception e) {
	        textStdOut.setText("");
	        textStdOut.append("The configuration file could not be loaded. Error message:\n");
	        textStdOut.append(e.getMessage());
	        textErrOut.setText("");
	        textErrOut.append("The configuration file could not be loaded. Error message:\n");
	        textErrOut.append(e.getMessage());
	        return;
	    }
	    // Call the new method to update the GUI from the loaded config
	    updateGuiWithConfig(config, this.configFile);
	}

	private void stopMATSim() {
		ExeRunner runner = this.exeRunner;
		if (runner != null) {
			runner.killProcess();
			exeRunner = null;
			progressBar.setVisible(false);
			btnStartMatsim.setText("Start MATSim");
			btnStartMatsim.setEnabled(true);
			textStdOut.append("\n");
			textStdOut.append("The simulation was stopped forcefully.");
			textStdOut.setCaretPosition(textStdOut.getDocument().getLength());
			textErrOut.append("\n");
			textErrOut.append("The simulation was stopped forcefully.");
			textErrOut.setCaretPosition(textErrOut.getDocument().getLength());
		}
	}

	public static Future<GuiWithConfigEditor> show(final String title, final Class<?> mainClass) {
		return show(title, mainClass, null);
	}

	public static Future<GuiWithConfigEditor> show(final String title, final Class<?> mainClass, File configFile) {
		System.setProperty("apple.laf.useScreenMenuBar", "true");
		RunnableFuture<GuiWithConfigEditor> rf = new FutureTask<>(() -> {
			GuiWithConfigEditor gui = new GuiWithConfigEditor(title, mainClass);
			gui.createLayout();
			gui.pack();
			gui.setLocationByPlatform(true);
			gui.setVisible(true);
			if (configFile != null && configFile.exists()) {
				gui.loadConfigFile(configFile);
			}
			return gui;
		});
		SwingUtilities.invokeLater(rf);
		return rf;
	}

	public void setWorkingDirectory(File cwd) {
		this.workingDirectory = cwd;
	}

}