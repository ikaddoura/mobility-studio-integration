/* *********************************************************************** *
 * project: org.matsim.*
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2026 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** */

package de.mobilitystudio.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.prefs.Preferences;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextPane;
import javax.swing.KeyStroke;
import javax.swing.SwingWorker;
import javax.swing.text.AttributeSet;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * "MATSim Copilot" – a chat-based AI assistant that reads the current
 * stdout / stderr (log) of the running MATSim simulation and helps the user
 * understand error messages and warnings.
 *
 * <p>Supported providers (current as of 2026):</p>
 * <ul>
 *     <li><b>OpenAI</b> – GPT-4o / GPT-4o-mini / GPT-4.1 / o4-mini</li>
 *     <li><b>Anthropic</b> – Claude Sonnet 4 / Opus 4 / 3.5 Sonnet</li>
 *     <li><b>Google Gemini</b> – Gemini 2.5 Pro / 2.5 Flash</li>
 *     <li><b>OpenRouter</b> – single key, many models</li>
 *     <li><b>Ollama (local)</b> – run any model locally, no key needed</li>
 * </ul>
 *
 * The API key is stored per provider via {@link Preferences}
 * ({@code userNodeForPackage(MatsimCopilotPanel.class)}).
 *
 * @author ikaddoura / Claude
 */
public class MatsimCopilotPanel extends JPanel {

    private static final long serialVersionUID = 1L;
    private static final Logger log = LogManager.getLogger(MatsimCopilotPanel.class);

    private static final String PREF_PROVIDER = "copilot.provider";
    private static final String PREF_MODEL = "copilot.model.";
    private static final String PREF_KEY = "copilot.apikey.";
    private static final String PREF_SETTINGS_VISIBLE = "copilot.settings.visible";
    private static final String PREF_INCLUDE_LOG = "copilot.include.log";
    private static final String PREF_INCLUDE_CONFIG = "copilot.include.config";
    private static final String PREF_MODE = "copilot.mode";
    private static final String PREF_AUTO_APPROVE = "copilot.agent.autoApprove";
    private static final String PREF_INSTRUCTIONS = "copilot.instructions";

    private static final int MAX_LOG_CHARS = 12_000;
    private static final int MAX_CONFIG_CHARS = 60_000;

    /** Operating mode of the copilot. */
    private enum Mode {
        CHAT("\uD83D\uDCAC Chat"),
        AGENT("\uD83E\uDD16 Agent");
        final String label;
        Mode(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    /** Provider definitions. */
    private enum Provider {
        OPENAI("OpenAI", "https://api.openai.com/v1/chat/completions",
                new String[] { "gpt-4o", "gpt-4o-mini", "gpt-4.1", "gpt-4.1-mini", "o4-mini" }, true),
        ANTHROPIC("Anthropic", "https://api.anthropic.com/v1/messages",
                new String[] { "claude-sonnet-4-20250514", "claude-opus-4-20250514", "claude-3-5-sonnet-latest",
                        "claude-3-5-haiku-latest" },
                true),
        GEMINI("Google Gemini", "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent",
                new String[] { "gemini-2.5-pro", "gemini-2.5-flash", "gemini-2.5-flash-lite",
                        "gemini-2.0-flash", "gemini-2.0-flash-lite" }, true),
        OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1/chat/completions",
                new String[] { "openai/gpt-4o", "anthropic/claude-sonnet-4", "google/gemini-2.5-pro",
                        "moonshotai/kimi-k2.6", "moonshotai/kimi-k2",
                        "google/gemma-4-27b-it", "google/gemma-3-27b-it",
                        "meta-llama/llama-3.3-70b-instruct", "mistralai/mistral-large" },
                true),
        OLLAMA("Ollama (local)", "http://localhost:11434/v1/chat/completions",
                new String[] { "qwen2.5-coder:14b", "qwen2.5-coder:7b", "llama3.2", "llama3.1",
                        "kimi-k2", "gemma3", "gemma2",
                        "mistral", "phi3", "hermes3" }, false),
        JLAMA("Embedded (Java)", "jlama://local",
                JlamaService.DEFAULT_MODELS, false);

        final String label;
        final String defaultEndpoint;
        final String[] models;
        final boolean needsKey;

        Provider(String label, String defaultEndpoint, String[] models, boolean needsKey) {
            this.label = label;
            this.defaultEndpoint = defaultEndpoint;
            this.models = models;
            this.needsKey = needsKey;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final Preferences prefs = Preferences.userNodeForPackage(MatsimCopilotPanel.class);
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();

    private final JComboBox<Provider> providerBox = new JComboBox<>(Provider.values());
    private final JComboBox<Mode> modeBox = new JComboBox<>(Mode.values());
    private final JComboBox<String> modelBox = new JComboBox<>();
    private final JPasswordField apiKeyField = new JPasswordField(28);
    private final JButton saveKeyBtn = new JButton("Save");
    private final JCheckBox includeLogBox = new JCheckBox("Send recent log/error output as context", true);
    private final JCheckBox includeConfigBox = new JCheckBox("Send selected configuration file as context", false);
    private final JCheckBox autoApproveBox = new JCheckBox("Agent: auto-approve all actions (\u26A0 risky)", false);
    private final JTextArea instructionsArea = new JTextArea(8, 60);
    private final JButton settingsToggle = new JButton("\u2699 Settings \u25BC");
    private JPanel settingsPanel;
    private final JTextPane chatPane = new JTextPane();
    private final JTextArea inputArea = new JTextArea(4, 60);
    private final JButton sendBtn = new JButton("Send  (Ctrl+Enter)");
    private final JButton explainErrorBtn = new JButton("Explain last error");
    private final JButton clearBtn = new JButton("New chat");
    private final JButton viewCtxBtn = new JButton("View context");
    private final JButton stopAgentBtn = new JButton("Stop");
    private final JLabel statusLabel = new JLabel(" ");

    private final JTextArea stdOutSource;
    private final JTextArea stdErrSource;
    private Supplier<File> configFileSupplier = () -> null;
    private MatsimAgentTools.RunController runController;

    /** Suppress side effects (saving to prefs) while we programmatically populate the combo boxes. */
    private boolean suppressEvents = false;

    /** Conversation history for chat mode (role, content). */
    private final List<Map.Entry<String, String>> history = new ArrayList<>();
    /** Persistent Gemini conversation for agent mode (raw "contents" entries). */
    private final List<ObjectNode> agentContents = new ArrayList<>();
    /** Persistent OpenAI-style conversation for agent mode (messages[] entries). */
    private final List<ObjectNode> agentMessages = new ArrayList<>();
    /** When non-null, the user has asked to cancel the running agent loop. */
    private volatile boolean agentCancelRequested = false;
    /** The currently running background worker (chat or agent), or {@code null} when idle. */
    private volatile SwingWorker<?, ?> currentWorker = null;

    public MatsimCopilotPanel(JTextArea stdOutSource, JTextArea stdErrSource) {
        this.stdOutSource = stdOutSource;
        this.stdErrSource = stdErrSource;
        buildUi();
        restoreFromPrefs();
        appendSystem(
                "Hi, I am the MATSim Copilot. \uD83E\uDD16\n"
                        + "Open \u2699 Settings to pick a provider and enter your API key (saved locally).\n"
                        + "Use the Mode dropdown to switch between:\n"
                        + "   \uD83D\uDCAC Chat  - I read your log/config and answer questions.\n"
                        + "   \uD83E\uDD16 Agent - I can also edit the config and start/stop MATSim runs\n"
                        + "             (each destructive action asks for your approval).\n"
                        + "             Agent mode supports: Google Gemini, OpenAI, OpenRouter, Ollama (local).\n\n"
                        + "Providers needing no install: \"Embedded (Java)\" runs a small LLM directly in this\n"
                        + "JVM via JLama (downloads ~1 GB model on first use, chat-only).\n\n"
                        + "Tip: in Chat mode, use the button \"Explain last error\" right after a failed run.\n\n"
                        + "\u26A0\uFE0F PLEASE NOTE: This is an experimental feature.\n"
                        + "Watch your token consumption when using paid providers! \u26A0\uFE0F\n");
    }

    /**
     * Provide a supplier returning the currently selected MATSim configuration file
     * (or {@code null} if none). Called by {@link GuiWithConfigEditor}.
     */
    public void setConfigFileSupplier(Supplier<File> supplier) {
        this.configFileSupplier = supplier != null ? supplier : (() -> null);
    }

    /**
     * Provide a {@link MatsimAgentTools.RunController} so the agent can start/stop the
     * running MATSim simulation. May be {@code null} – then the agent will simply
     * report that it cannot run simulations.
     */
    public void setRunController(MatsimAgentTools.RunController controller) {
        this.runController = controller;
    }

    // ------------------------------------------------------------------ UI

    private void buildUi() {
        setLayout(new BorderLayout(6, 6));
        setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        // ----- top: header with collapsible settings -----
        JPanel header = new JPanel(new BorderLayout());

        JPanel topBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        settingsToggle.setFocusable(false);
        settingsToggle.setBorderPainted(false);
        settingsToggle.setContentAreaFilled(false);
        topBar.add(settingsToggle);
        topBar.add(new JLabel("Mode:"));
        topBar.add(modeBox);
        topBar.add(includeLogBox);
        topBar.add(includeConfigBox);
        topBar.add(autoApproveBox);
        // MATSim version label intentionally not shown here: it widened the top bar and
        // prevented the checkboxes/dropdowns from shrinking when the window was resized.
        // The Copilot is still informed of the version via MatsimKnowledgeBase.matsimVersion()
        // when the system prompt is built.
        header.add(topBar, BorderLayout.NORTH);

        settingsPanel = new JPanel(new GridBagLayout());
        settingsPanel.setBorder(BorderFactory.createTitledBorder("AI Provider"));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 4, 2, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        c.gridx = 0; c.gridy = 0; settingsPanel.add(new JLabel("Provider:"), c);
        c.gridx = 1; settingsPanel.add(providerBox, c);
        c.gridx = 2; settingsPanel.add(new JLabel("Model:"), c);
        c.gridx = 3; c.weightx = 1; settingsPanel.add(modelBox, c);
        modelBox.setEditable(true); // allow free typing of model names (esp. for Ollama)
        c.weightx = 0;

        c.gridx = 0; c.gridy = 1; settingsPanel.add(new JLabel("API key:"), c);
        c.gridx = 1; c.gridwidth = 2; settingsPanel.add(apiKeyField, c); c.gridwidth = 1;
        c.gridx = 3; settingsPanel.add(saveKeyBtn, c);
        
        c.gridx = 0; c.gridy = 2; c.gridwidth = 4;
        c.insets = new Insets(8, 4, 2, 4);
        settingsPanel.add(new JLabel("Custom System Instructions (appended to AI context):"), c);

        c.gridy = 3; c.weighty = 1.0; c.fill = GridBagConstraints.BOTH;
        instructionsArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        instructionsArea.setLineWrap(true);
        instructionsArea.setWrapStyleWord(true);
        settingsPanel.add(new JScrollPane(instructionsArea), c);

        header.add(settingsPanel, BorderLayout.CENTER);
        // start collapsed unless user previously had it open
        settingsPanel.setVisible(prefs.getBoolean(PREF_SETTINGS_VISIBLE, false));
        updateSettingsToggleLabel();

        add(header, BorderLayout.NORTH);

        // ----- center: chat + input split -----
        chatPane.setEditable(false);
        chatPane.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        JScrollPane chatScroll = new JScrollPane(chatPane);
        chatScroll.setPreferredSize(new Dimension(700, 280));

        inputArea.setLineWrap(true);
        inputArea.setWrapStyleWord(true);
        JScrollPane inputScroll = new JScrollPane(inputArea);

        JPanel bottom = new JPanel(new BorderLayout(4, 4));
        bottom.add(inputScroll, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 2));
        actions.add(statusLabel);
        actions.add(Box.createHorizontalStrut(20));
        actions.add(clearBtn);
        actions.add(viewCtxBtn);
        actions.add(explainErrorBtn);
        actions.add(stopAgentBtn);
        actions.add(sendBtn);
        bottom.add(actions, BorderLayout.SOUTH);

        stopAgentBtn.setVisible(false);
        stopAgentBtn.setToolTipText("Cancel the current request: stops the agent loop or "
                + "interrupts the chat HTTP/local-model call. Use this if a local Ollama / "
                + "JLama model is making the machine unresponsive.");

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, chatScroll, bottom);
        split.setResizeWeight(0.75);
        split.setBorder(null);
        add(split, BorderLayout.CENTER);

        // ----- behaviour -----
        settingsToggle.addActionListener(e -> {
            boolean newVisible = !settingsPanel.isVisible();
            settingsPanel.setVisible(newVisible);
            prefs.putBoolean(PREF_SETTINGS_VISIBLE, newVisible);
            updateSettingsToggleLabel();
            revalidate();
            repaint();
        });
        providerBox.addActionListener(e -> { if (!suppressEvents) onProviderChanged(); });
        modelBox.addActionListener(e -> {
            if (suppressEvents) return;
            Provider p = (Provider) providerBox.getSelectedItem();
            if (p != null && modelBox.getSelectedItem() != null) {
                prefs.put(PREF_MODEL + p.name(), modelBox.getSelectedItem().toString());
            }
        });
        includeLogBox.addActionListener(e -> prefs.putBoolean(PREF_INCLUDE_LOG, includeLogBox.isSelected()));
        includeConfigBox.addActionListener(e -> prefs.putBoolean(PREF_INCLUDE_CONFIG, includeConfigBox.isSelected()));
        autoApproveBox.addActionListener(e -> prefs.putBoolean(PREF_AUTO_APPROVE, autoApproveBox.isSelected()));
        
        instructionsArea.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { save(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { save(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { save(); }
            private void save() { prefs.put(PREF_INSTRUCTIONS, instructionsArea.getText()); }
        });
        
        modeBox.addActionListener(e -> {
            Mode m = (Mode) modeBox.getSelectedItem();
            if (m == null) return;
            prefs.put(PREF_MODE, m.name());
            updateModeUi();
        });
        saveKeyBtn.addActionListener(e -> saveCurrentKey());
        sendBtn.addActionListener(e -> onSend());
        explainErrorBtn.addActionListener(e -> onExplainError());
        clearBtn.addActionListener(e -> {
            history.clear();
            agentContents.clear();
            agentMessages.clear();
            chatPane.setText("");
            appendSystem("New chat started.\n");
        });
        viewCtxBtn.addActionListener(e -> showContextDialog());
        stopAgentBtn.addActionListener(e -> {
            agentCancelRequested = true;
            statusLabel.setText("Cancelling…");
            SwingWorker<?, ?> w = currentWorker;
            if (w != null) {
                // Interrupts the worker thread → HttpClient.send throws InterruptedException
                // and the JLama streaming sink aborts generation.
                w.cancel(true);
            }
        });

        // Ctrl+Enter to send
        KeyStroke ctrlEnter = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, java.awt.event.InputEvent.CTRL_DOWN_MASK);
        inputArea.getInputMap(JComponent.WHEN_FOCUSED).put(ctrlEnter, "send");
        inputArea.getActionMap().put("send", new AbstractAction() {
            private static final long serialVersionUID = 1L;
            @Override public void actionPerformed(ActionEvent e) { onSend(); }
        });
    }

    private void updateSettingsToggleLabel() {
        settingsToggle.setText(settingsPanel.isVisible() ? "\u2699 Settings \u25B2" : "\u2699 Settings \u25BC");
    }

    private void onProviderChanged() {
        Provider p = (Provider) providerBox.getSelectedItem();
        if (p == null) return;

        // Read the saved model BEFORE touching the combo box - because addItem()
        // on the first element fires a selection event that would otherwise
        // overwrite our preference with the default.
        String savedModel = prefs.get(PREF_MODEL + p.name(), p.models[0]);

        // For Ollama, try to discover the locally-installed models and offer those instead
        // of the hard-coded defaults; fall back silently if the server is not reachable.
        String[] modelList = p.models;
        if (p == Provider.OLLAMA) {
            String[] installed = fetchOllamaModels();
            if (installed != null && installed.length > 0) modelList = installed;
        }

        boolean prev = suppressEvents;
        suppressEvents = true;
        try {
            modelBox.removeAllItems();
            for (String m : modelList) modelBox.addItem(m);
            modelBox.setSelectedItem(savedModel);
            // If the saved model isn't in the (live) list, keep it as a free entry
            // so the user can still pick / type something else later.
            if (modelBox.getSelectedItem() == null && modelList.length > 0) {
                modelBox.setSelectedItem(modelList[0]);
            }
        } finally {
            suppressEvents = prev;
        }

        String savedKey = prefs.get(PREF_KEY + p.name(), "");
        apiKeyField.setText(savedKey);
        apiKeyField.setEnabled(p.needsKey);
        saveKeyBtn.setEnabled(p.needsKey);

        prefs.put(PREF_PROVIDER, p.name());
        // make sure the (possibly already-correct) model value is persisted
        prefs.put(PREF_MODEL + p.name(), savedModel);
        if (p == Provider.OLLAMA) {
            statusLabel.setText(modelList == p.models
                    ? "Ollama not reachable on localhost:11434 - showing default model names."
                    : "Ollama: " + modelList.length + " local model(s) discovered.");
        } else if (p == Provider.JLAMA) {
            if (!JlamaService.isAvailable()) {
                statusLabel.setText("Embedded (Java) provider unavailable: JLama JAR is missing on the classpath.");
            } else {
                statusLabel.setText("Embedded (Java): model is downloaded on first use to "
                        + JlamaService.DEFAULT_MODEL_DIR + " (~0.5-2.5 GB). First answer will be slow.");
            }
        } else {
            statusLabel.setText(p.needsKey && savedKey.isEmpty()
                    ? "No API key stored for " + p.label
                    : " ");
        }
    }

    /**
     * Query Ollama's {@code GET /api/tags} for the list of locally installed model names.
     * Returns {@code null} on any failure (server not running, parse error, etc.).
     */
    private String[] fetchOllamaModels() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:11434/api/tags"))
                    .timeout(Duration.ofSeconds(2))
                    .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) return null;
            // Body looks like {"models":[{"name":"qwen2.5-coder:14b","model":"...", ...}, ...]}
            com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(resp.body());
            com.fasterxml.jackson.databind.JsonNode arr = root.path("models");
            if (!arr.isArray() || arr.isEmpty()) return null;
            List<String> names = new ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode m : arr) {
                String name = m.path("name").asText("");
                if (!name.isEmpty()) names.add(name);
            }
            return names.toArray(new String[0]);
        } catch (Exception ex) {
            return null;
        }
    }

    private void saveCurrentKey() {
        Provider p = (Provider) providerBox.getSelectedItem();
        if (p == null) return;
        String key = new String(apiKeyField.getPassword()).trim();
        prefs.put(PREF_KEY + p.name(), key);
        statusLabel.setText("API key for " + p.label + " saved locally.");
    }

    private void restoreFromPrefs() {
        String savedProv = prefs.get(PREF_PROVIDER, Provider.OPENAI.name());
        try {
            providerBox.setSelectedItem(Provider.valueOf(savedProv));
        } catch (Exception ignore) {
            providerBox.setSelectedItem(Provider.OPENAI);
        }
        onProviderChanged();
        includeLogBox.setSelected(prefs.getBoolean(PREF_INCLUDE_LOG, true));
        includeConfigBox.setSelected(prefs.getBoolean(PREF_INCLUDE_CONFIG, false));
        autoApproveBox.setSelected(prefs.getBoolean(PREF_AUTO_APPROVE, false));
        instructionsArea.setText(prefs.get(PREF_INSTRUCTIONS, MatsimKnowledgeBase.DEFAULT_INSTRUCTIONS));
        
        String savedMode = prefs.get(PREF_MODE, Mode.CHAT.name());
        try {
            modeBox.setSelectedItem(Mode.valueOf(savedMode));
        } catch (Exception ignore) {
            modeBox.setSelectedItem(Mode.CHAT);
        }
        updateModeUi();
    }

    /** Updates labels/visibility/tooltips that depend on the current {@link Mode}. */
    private void updateModeUi() {
        Mode m = (Mode) modeBox.getSelectedItem();
        boolean agent = m == Mode.AGENT;
        autoApproveBox.setVisible(agent);
        // includeLogBox / includeConfigBox are only meaningful in chat mode – the agent
        // pulls log and config itself via tools.
        includeLogBox.setVisible(!agent);
        includeConfigBox.setVisible(!agent);
        sendBtn.setText(agent ? "Run agent  (Ctrl+Enter)" : "Send  (Ctrl+Enter)");
        explainErrorBtn.setVisible(!agent);
        if (agent) {
            inputArea.setToolTipText("Describe a goal for the agent (e.g. 'reduce lastIteration to 5 and run the simulation').");
        } else {
            inputArea.setToolTipText("Ask a question about your MATSim run.");
        }
    }

    // ------------------------------------------------------------------ chat

    private void onSend() {
        String text = inputArea.getText().trim();
        if (text.isEmpty()) return;
        inputArea.setText("");
        Mode m = (Mode) modeBox.getSelectedItem();
        if (m == Mode.AGENT) {
            runAgentTurn(text);
        } else {
            sendUserMessage(text, true);
        }
    }

    private void onExplainError() {
        String err = stdErrSource != null ? stdErrSource.getText() : "";
        String out = stdOutSource != null ? stdOutSource.getText() : "";
        if ((err == null || err.isBlank()) && (out == null || out.isBlank())) {
            JOptionPane.showMessageDialog(this,
                    "There is no log output yet. Run a simulation first.",
                    "Nothing to explain", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String prompt = "Please analyse the latest MATSim run and explain the most likely cause "
                + "of any warnings/errors in plain language. If possible, suggest concrete fixes "
                + "(config parameters, file paths, missing inputs, common pitfalls).";
        sendUserMessage(prompt, true);
    }

    private void sendUserMessage(String userText, boolean attachLog) {
        Provider p = (Provider) providerBox.getSelectedItem();
        String model = (String) modelBox.getSelectedItem();
        if (p == null || model == null) return;
        String apiKey = new String(apiKeyField.getPassword()).trim();
        if (p.needsKey && apiKey.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Please enter and save an API key for " + p.label + " first.",
                    "API key missing", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Build the user message - optionally enriched with log/config context.
        StringBuilder full = new StringBuilder(userText);
        if (attachLog && includeLogBox.isSelected()) {
            String ctx = buildLogContext();
            if (!ctx.isEmpty()) {
                full.append("\n\n---\nRecent MATSim log (truncated):\n```\n")
                    .append(ctx).append("\n```");
            }
        }
        if (attachLog && includeConfigBox.isSelected()) {
            String cfg = buildConfigContext();
            if (!cfg.isEmpty()) {
                full.append("\n\n---\nSelected MATSim configuration file:\n```xml\n")
                    .append(cfg).append("\n```");
            }
        }
        history.add(Map.entry("user", full.toString()));
        appendUser(userText);
        appendAssistantPlaceholder();

        sendBtn.setEnabled(false);
        explainErrorBtn.setEnabled(false);
        stopAgentBtn.setVisible(true);
        stopAgentBtn.setEnabled(true);
        statusLabel.setText("Thinking… (" + chatContextSizeLabel() + ")");
        
        // Grab the dynamic instructions from the UI thread before entering the background thread
        String dynamicSystemPrompt = effectiveSystemPrompt();

        SwingWorker<String, Void> worker = new SwingWorker<String, Void>() {
            @Override protected String doInBackground() throws Exception {
                // Pass it into callProvider
                return callProvider(p, model, apiKey, history, dynamicSystemPrompt); 
            }
            @Override protected void done() {
                String reply;
                try {
                    if (isCancelled()) {
                        reply = "[Cancelled by user]";
                    } else {
                        reply = get();
                    }
                } catch (java.util.concurrent.CancellationException ce) {
                    reply = "[Cancelled by user]";
                } catch (Exception ex) {
                    log.warn("Copilot error", ex);
                    Throwable root = ex.getCause() != null ? ex.getCause() : ex;
                    if (root instanceof InterruptedException) {
                        reply = "[Cancelled by user]";
                    } else {
                        reply = "[Error] " + root.getMessage();
                    }
                }
                history.add(Map.entry("assistant", reply));
                replacePlaceholderWithAssistant(reply);
                statusLabel.setText("Last turn: " + chatContextSizeLabel());
                sendBtn.setEnabled(true);
                explainErrorBtn.setEnabled(true);
                stopAgentBtn.setVisible(false);
                currentWorker = null;
            }
        };
        currentWorker = worker;
        worker.execute();
    }

    private String buildLogContext() {
        String err = stdErrSource != null ? stdErrSource.getText() : "";
        String out = stdOutSource != null ? stdOutSource.getText() : "";
        StringBuilder sb = new StringBuilder();
        if (err != null && !err.isBlank()) {
            sb.append("[stderr]\n").append(smartLogExtract(err, MAX_LOG_CHARS / 2)).append("\n");
        }
        if (out != null && !out.isBlank()) {
            sb.append("[stdout]\n").append(smartLogExtract(out, MAX_LOG_CHARS / 2));
        }
        return sb.toString();
    }

    /** Extracts the FIRST error/exception and the TAIL of the log to capture the root cause without blowing up tokens. */
    private static String smartLogExtract(String s, int maxTailChars) {
        if (s.length() <= maxTailChars) return s;

        // Find the first major exception or error
        int errIdx = s.indexOf("Exception in thread");
        if (errIdx < 0) errIdx = s.indexOf("ERROR");
        if (errIdx < 0) errIdx = s.indexOf("Caused by:");

        String head = "";
        if (errIdx >= 0 && errIdx < s.length() - maxTailChars) {
            // Grab the error and the ~2000 characters immediately following it to catch the message/trace
            int endIdx = Math.min(errIdx + 2000, s.length() - maxTailChars);
            head = "--- FIRST ERROR/EXCEPTION FOUND ---\n" + s.substring(errIdx, endIdx) + "\n\n... (middle skipped) ...\n\n";
        } else {
            head = "... (start skipped) ...\n\n";
        }

        return head + "--- LOG TAIL ---\n" + s.substring(s.length() - maxTailChars);
    }

    /**
     * Read the currently selected config file (provided via {@link #setConfigFileSupplier(Supplier)})
     * and return its contents, truncated to {@link #MAX_CONFIG_CHARS}.
     */
    private String buildConfigContext() {
        File f = configFileSupplier.get();
        if (f == null || !f.isFile()) return "";
        try {
            String content = Files.readString(f.toPath());
            StringBuilder sb = new StringBuilder();
            sb.append("[file: ").append(f.getAbsolutePath()).append("]\n");
            if (content.length() > MAX_CONFIG_CHARS) {
                sb.append(content, 0, MAX_CONFIG_CHARS).append("\n…(truncated)…");
            } else {
                sb.append(content);
            }
            return sb.toString();
        } catch (IOException e) {
            log.warn("Could not read config file for Copilot context: " + f.getAbsolutePath(), e);
            return "[could not read " + f.getAbsolutePath() + ": " + e.getMessage() + "]";
        }
    }

    // ------------------------------------------------------------- chat view

    private void appendUser(String text) {
        appendStyled("You: ", new Color(0x0066CC), true);
        appendStyled(text + "\n\n", null, false);
    }

    private void appendSystem(String text) {
        appendStyled(text + "\n", new Color(0x666666), false);
    }

    private int placeholderStart = -1;
    private int placeholderEnd = -1;

    private void appendAssistantPlaceholder() {
        StyledDocument doc = chatPane.getStyledDocument();
        try {
            placeholderStart = doc.getLength();
            doc.insertString(doc.getLength(), "Copilot: ", boldStyle(new Color(0x008060)));
            int contentStart = doc.getLength();
            doc.insertString(doc.getLength(), "…\n\n", normalStyle());
            placeholderEnd = doc.getLength();
            chatPane.setCaretPosition(doc.getLength());
            // remember where content begins to allow replacement
            placeholderStart = contentStart;
        } catch (Exception ignore) { }
    }

    private void replacePlaceholderWithAssistant(String text) {
        StyledDocument doc = chatPane.getStyledDocument();
        try {
            if (placeholderStart >= 0 && placeholderEnd >= placeholderStart) {
                doc.remove(placeholderStart, placeholderEnd - placeholderStart);
                doc.insertString(placeholderStart, text + "\n\n", normalStyle());
            } else {
                doc.insertString(doc.getLength(), "Copilot: " + text + "\n\n", normalStyle());
            }
            chatPane.setCaretPosition(doc.getLength());
        } catch (Exception ignore) { }
        placeholderStart = placeholderEnd = -1;
    }

    private void appendStyled(String s, Color color, boolean bold) {
        StyledDocument doc = chatPane.getStyledDocument();
        SimpleAttributeSet a = new SimpleAttributeSet();
        if (color != null) StyleConstants.setForeground(a, color);
        StyleConstants.setBold(a, bold);
        try {
            doc.insertString(doc.getLength(), s, a);
            chatPane.setCaretPosition(doc.getLength());
        } catch (Exception ignore) { }
    }

    private AttributeSet normalStyle() { return new SimpleAttributeSet(); }
    private AttributeSet boldStyle(Color c) {
        SimpleAttributeSet a = new SimpleAttributeSet();
        if (c != null) StyleConstants.setForeground(a, c);
        StyleConstants.setBold(a, true);
        return a;
    }

    // -------------------------------------------------------------- agent mode

    /** Runs one turn of the agent in a background thread. Dispatches to Gemini or OpenAI-compatible. */
    private void runAgentTurn(String userText) {
        Provider p = (Provider) providerBox.getSelectedItem();
        String model = (String) modelBox.getSelectedItem();
        if (p == null || model == null) return;
        if (p == Provider.ANTHROPIC) {
            JOptionPane.showMessageDialog(this,
                    "Agent mode for Anthropic is not implemented yet.\n"
                            + "Supported in agent mode: Google Gemini, OpenAI, OpenRouter, Ollama (local).",
                    "Agent mode unavailable", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (p == Provider.JLAMA) {
            JOptionPane.showMessageDialog(this,
                    "Agent mode is not available for the embedded (Java) provider.\n\n"
                            + "The small models that JLama can run locally are not reliable enough\n"
                            + "for tool calling. Use Chat mode for embedded inference, or pick\n"
                            + "Ollama / Gemini / OpenAI for Agent mode.",
                    "Agent mode unavailable", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String apiKey = new String(apiKeyField.getPassword()).trim();
        if (p.needsKey && apiKey.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Please enter and save an API key for " + p.label + " first.",
                    "API key missing", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (configFileSupplier.get() == null) {
            JOptionPane.showMessageDialog(this,
                    "Please load a MATSim configuration file in the GUI first - the agent needs "
                            + "a scenario directory to operate in.",
                    "No config loaded", JOptionPane.WARNING_MESSAGE);
            return;
        }

        appendUser(userText);
        agentCancelRequested = false;

        // Build the actual user message that the model will see. On the FIRST turn of a
        // fresh conversation, pre-attach the recent log + a one-line config summary so the
        // agent doesn't have to spend tokens calling tail_log / read_config just to get
        // started. On subsequent turns we only send the bare user text - any additional
        // info will be pulled via tools.
        boolean firstTurn = agentContents.isEmpty() && agentMessages.isEmpty();
        String agentUserText = userText;
        if (firstTurn) {
            StringBuilder enriched = new StringBuilder(userText);
            File cfg = configFileSupplier.get();
            if (cfg != null) {
                enriched.append("\n\n---\nActive scenario:\n  config file: ")
                        .append(cfg.getAbsolutePath())
                        .append("\n  scenario dir: ").append(cfg.getParentFile().getAbsolutePath());
                
                // --- DELETED BLOCK ---
                // The automatic config injection has been removed to save tokens.
                // The agent will now use the read_config tool on its first turn
                // to get the config, which is more efficient for the overall conversation.
            }
            String logCtx = buildLogContext();
            if (!logCtx.isEmpty()) {
                enriched.append("\n\n---\nMost recent MATSim run log (Smart Extracted). The actual root\n")
                        .append("cause is usually a single sentence near the FIRST 'Exception in thread'\n")
                        .append("or 'ERROR' line - quote it before proposing any fix:\n```\n")
                        .append(logCtx).append("\n```");
            }
            agentUserText = enriched.toString();
        }
        final String agentUserTextFinal = agentUserText;

        sendBtn.setEnabled(false);
        explainErrorBtn.setEnabled(false);
        stopAgentBtn.setVisible(true);
        stopAgentBtn.setEnabled(true);
        statusLabel.setText("Agent: working…");

        MatsimAgentTools tools = new MatsimAgentTools(
                configFileSupplier, stdOutSource, stdErrSource, runController, this::approveOnEdt);

        GeminiAgent.ProgressListener listener = new GeminiAgent.ProgressListener() {
            @Override public void onToolCall(String name, JsonNode args) {
                javax.swing.SwingUtilities.invokeLater(() -> {
                    appendToolCall(name, args);
                    updateAgentStatus();
                });
            }
            @Override public void onToolResult(String name, JsonNode result) {
                javax.swing.SwingUtilities.invokeLater(() -> {
                    appendToolResult(name, result);
                    updateAgentStatus();
                });
            }
            @Override public void onModelText(String text) {
                javax.swing.SwingUtilities.invokeLater(() -> appendAgentThought(text));
            }
        };

        // Build the per-provider agent.
        final Provider providerFinal = p;
        final String modelFinal = model;
        final String apiKeyFinal = apiKey;

        SwingWorker<String, Void> worker = new SwingWorker<String, Void>() {
            @Override protected String doInBackground() throws Exception {
                if (agentCancelRequested) return "[cancelled before start]";
                if (providerFinal == Provider.GEMINI) {
                    GeminiAgent agent = new GeminiAgent(http, providerFinal.defaultEndpoint,
                            modelFinal, apiKeyFinal, tools, effectiveAgentSystemPrompt());
                    return agent.runTurn(agentContents, agentUserTextFinal, listener);
                } else {
                    boolean openrouter = providerFinal == Provider.OPENROUTER;
                    String key = providerFinal == Provider.OLLAMA ? "" : apiKeyFinal;
                    OpenAiToolAgent agent = new OpenAiToolAgent(http, providerFinal.defaultEndpoint,
                            modelFinal, key, openrouter, tools, effectiveAgentSystemPrompt());
                    return agent.runTurn(agentMessages, agentUserTextFinal, listener);
                }
            }
            @Override protected void done() {
                String reply;
                try {
                    if (isCancelled()) {
                        reply = "[Agent cancelled by user]";
                    } else {
                        reply = get();
                    }
                } catch (java.util.concurrent.CancellationException ce) {
                    reply = "[Agent cancelled by user]";
                } catch (Exception ex) {
                    log.warn("Agent error", ex);
                    Throwable root = ex.getCause() != null ? ex.getCause() : ex;
                    if (root instanceof InterruptedException) {
                        reply = "[Agent cancelled by user]";
                    } else {
                        reply = "[Agent error] " + root.getMessage();
                    }
                }
                appendAgentFinal(reply);
                int chars = 0;
                for (ObjectNode n : agentContents) chars += n.toString().length();
                for (ObjectNode n : agentMessages) chars += n.toString().length();
                statusLabel.setText(String.format("Agent finished. Context ~%,d chars / ~%,d tokens",
                        chars, chars / 4));
                sendBtn.setEnabled(true);
                explainErrorBtn.setEnabled(true);
                stopAgentBtn.setVisible(false);
                agentCancelRequested = false;
                currentWorker = null;
            }
        };
        currentWorker = worker;
        worker.execute();
    }

    /** Updates the status label with an estimate of the current context size. */
    private void updateAgentStatus() {
        int chars = 0;
        for (ObjectNode n : agentContents) chars += n.toString().length();
        for (ObjectNode n : agentMessages) chars += n.toString().length();
        statusLabel.setText(String.format("Agent: working… (~%,d ctx chars, ~%,d tokens)",
                chars, chars / 4));
    }

    /** Returns the rough size (chars / tokens) of the current chat-mode history. */
    private String chatContextSizeLabel() {
        int chars = SYSTEM_PROMPT.length();
        for (Map.Entry<String, String> m : history) chars += m.getValue().length();
        return String.format("~%,d ctx chars / ~%,d tokens", chars, chars / 4);
    }

    /**
     * Pops up a dialog showing the EXACT context that would be sent to the model on the
     * next call. Useful for debugging unexpectedly large prompts / token bills.
     */
    private void showContextDialog() {
        Mode m = (Mode) modeBox.getSelectedItem();
        StringBuilder sb = new StringBuilder();
        int chars;
        if (m == Mode.AGENT) {
            String actualAgentSysPrompt = effectiveAgentSystemPrompt(); // <-- CHANGE IS HERE
            sb.append("=== AGENT MODE CONTEXT ===\n\n");
            sb.append("System prompt (").append(actualAgentSysPrompt.length()).append(" chars):\n");
            sb.append(actualAgentSysPrompt).append("\n\n");
            chars = actualAgentSysPrompt.length();
            if (!agentContents.isEmpty()) {
                sb.append("--- Gemini contents[] (").append(agentContents.size()).append(" turns) ---\n");
                for (int i = 0; i < agentContents.size(); i++) {
                    String s = agentContents.get(i).toPrettyString();
                    chars += s.length();
                    sb.append("\n[").append(i).append("]\n").append(s).append('\n');
                }
            }
            if (!agentMessages.isEmpty()) {
                sb.append("--- OpenAI messages[] (").append(agentMessages.size()).append(" turns) ---\n");
                for (int i = 0; i < agentMessages.size(); i++) {
                    String s = agentMessages.get(i).toPrettyString();
                    chars += s.length();
                    sb.append("\n[").append(i).append("]\n").append(s).append('\n');
                }
            }
        } else {
            String actualSysPrompt = effectiveSystemPrompt();
            sb.append("=== CHAT MODE CONTEXT ===\n\n");
            sb.append("System prompt (").append(actualSysPrompt.length()).append(" chars):\n");
            sb.append(actualSysPrompt).append("\n\n");
            chars = actualSysPrompt.length();
            sb.append("--- History (").append(history.size()).append(" messages) ---\n");
            for (int i = 0; i < history.size(); i++) {
                Map.Entry<String, String> e = history.get(i);
                chars += e.getValue().length();
                sb.append("\n[").append(i).append("] ").append(e.getKey())
                  .append(" (").append(e.getValue().length()).append(" chars):\n")
                  .append(e.getValue()).append('\n');
            }
        }
        sb.insert(0, String.format("Total: ~%,d chars  ≈  ~%,d tokens (est. /4)%n%n",
                chars, chars / 4));

        JTextArea ta = new JTextArea(sb.toString(), 30, 100);
        ta.setEditable(false);
        ta.setLineWrap(true);
        ta.setWrapStyleWord(true);
        ta.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        ta.setCaretPosition(0);
        JScrollPane sp = new JScrollPane(ta);
        sp.setPreferredSize(new Dimension(900, 600));
        JOptionPane.showMessageDialog(this, sp,
                "Context that will be sent to the AI", JOptionPane.PLAIN_MESSAGE);
    }

    /**
     * Approval gateway used by {@link MatsimAgentTools}. Always invoked from the worker
     * thread, so we hop to the EDT for the dialog and wait for the result.
     */
    private boolean approveOnEdt(String toolName, String details) {
        if (agentCancelRequested) return false;
        if (autoApproveBox.isSelected()) return true;

        final boolean[] result = new boolean[] { false };
        try {
            Runnable r = () -> {
                int choice = JOptionPane.showOptionDialog(this,
                        "The agent wants to perform a destructive action:\n\n"
                                + toolName + "\n\n" + details
                                + "\n\nAllow this action?",
                        "Agent approval needed",
                        JOptionPane.DEFAULT_OPTION,
                        JOptionPane.QUESTION_MESSAGE,
                        null,
                        new Object[] { "Deny", "Allow" }, "Deny");
                result[0] = (choice == 1);
            };
            if (javax.swing.SwingUtilities.isEventDispatchThread()) r.run();
            else javax.swing.SwingUtilities.invokeAndWait(r);
        } catch (Exception ex) {
            log.warn("Approval dialog failed", ex);
            return false;
        }
        return result[0];
    }

    private void appendToolCall(String name, JsonNode args) {
        appendStyled("\u2192 ", new Color(0x884400), true);
        appendStyled(MatsimAgentTools.describeCall(name, args) + "\n", new Color(0x884400), false);
    }

    private void appendToolResult(String name, JsonNode result) {
        boolean isErr = result != null && result.has("error");
        Color col = isErr ? new Color(0xCC0000) : new Color(0x666666);
        String summary;
        if (isErr) {
            summary = "  \u2715 " + name + ": " + result.path("error").asText();
        } else {
            summary = "  \u2713 " + name + ": " + summariseResult(result);
        }
        appendStyled(summary + "\n", col, false);
    }

    private static String summariseResult(JsonNode result) {
        if (result == null || result.isNull()) return "ok";
        // Pick a few telling fields, otherwise show field-name overview.
        if (result.has("unchanged") && result.get("unchanged").asBoolean()) return "unchanged (cached)";
        if (result.has("exit_code"))   return "exit_code=" + result.get("exit_code").asInt();
        if (result.has("timed_out") && result.get("timed_out").asBoolean()) return "timed out";
        if (result.has("started"))     return "started=" + result.get("started").asBoolean();
        if (result.has("stopped"))     return "stopped=" + result.get("stopped").asBoolean();
        if (result.has("bytes_written")) return result.get("bytes_written").asInt() + " bytes written";
        if (result.has("entries"))     return result.get("entries").size() + " entries";
        if (result.has("size"))        return result.get("size").asInt() + " bytes";
        if (result.has("content")) {
            int len = result.get("content").asText("").length();
            return len + " chars";
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (java.util.Iterator<String> it = result.fieldNames(); it.hasNext(); ) {
            if (!first) sb.append(", ");
            sb.append(it.next());
            first = false;
        }
        return sb.append('}').toString();
    }

    private void appendAgentThought(String text) {
        if (text == null || text.isBlank()) return;
        appendStyled("\u270F ", new Color(0x556677), true);
        appendStyled(text.trim() + "\n", new Color(0x556677), false);
    }

    private void appendAgentFinal(String text) {
        appendStyled("Agent: ", new Color(0x008060), true);
        appendStyled((text == null ? "" : text) + "\n\n", null, false);
    }

    // ------------------------------------------------------- provider calls

    /** Effective chat-mode system prompt, with optional MATSim primer + GUI/version block appended. */
    private String effectiveSystemPrompt() {
        return SYSTEM_PROMPT + MatsimKnowledgeBase.extraSystemPrompt(instructionsArea.getText());
    }

    /** Effective agent-mode system prompt, with optional MATSim primer + GUI/version block appended. */
    private String effectiveAgentSystemPrompt() {
        return AGENT_SYSTEM_PROMPT + MatsimKnowledgeBase.extraSystemPrompt(instructionsArea.getText());
    }

    private static final String SYSTEM_PROMPT =
            "You are MATSim Copilot, a friendly assistant that helps the user run MATSim "
            + "simulations. You explain warnings and errors in plain language and suggest "
            + "concrete fixes (config parameters, file paths, missing inputs, common pitfalls). "
            + "Be concise, use bullet points, and reference the relevant lines from the log "
            + "when helpful. If the user did not provide a log, answer based on general MATSim "
            + "knowledge.";

    private static final String AGENT_SYSTEM_PROMPT =
            "You are MATSim Copilot in AGENT mode. You can call tools to inspect and edit the "
            + "user's MATSim scenario and to start/stop the simulation.\n\n"
            + "TOOL USAGE (CRITICAL):\n"
            + "  - You MUST call tools by providing a valid JSON object in a `functionCall` part.\n"
            + "  - DO NOT wrap the call in `print()` or any other code.\n"
            + "  - DO NOT output markdown code blocks like ```json.\n"
            + "  - Your entire response must be ONLY the valid tool-calling JSON structure expected by the API.\n\n"
            + "The available tools are:\n" // Added this for clarity
            + "  - tail_log, read_config, list_dir, read_file: read-only inspection.\n"
            + "  - write_config: overwrite the active config XML (a .bak is created automatically).\n"
            + "  - start_matsim / wait_for_run / stop_matsim: control the running MATSim process.\n\n"
            + "Workflow guidelines:\n"
            + "  1. Always inspect (read_config, tail_log) before proposing or applying changes.\n"
            + "  2. When write_config is needed, return the FULL new XML content (no diff/patch).\n"
            + "     Make the smallest change required and preserve all other parameters.\n"
            + "  3. After write_config, you may start_matsim and then wait_for_run to verify.\n"
            + "  4. start_matsim, write_config and stop_matsim require user approval - briefly\n"
            + "     justify them in the 'reason' argument.\n"
            + "  5. Never invent file paths; use list_dir / read_file to discover them.\n"
            + "  6. Stop after the user's goal is met and reply with a short summary of what you\n"
            + "     changed and what the result was.\n\n"
            + "ERROR DIAGNOSIS (CRITICAL):\n"
            + "  - EXCEPTIONS BEAT WARNINGS. WARN/INFO lines are noise unless they directly\n"
            + "    explain the failure - never propose changes based on warnings while an\n"
            + "    actual exception is present in the log.\n"
            + "  - When wait_for_run returns a non-zero exit code, ALWAYS scroll back through the\n"
            + "    stderr_tail / stdout_tail and identify the FIRST 'Exception in thread' / 'ERROR' /\n"
            + "    'Caused by' line, plus the topmost human-readable error message. Many MATSim\n"
            + "    errors print a long Guice/injection stack trace AFTER the actual root cause -\n"
            + "    the root cause is usually a single sentence higher up (e.g. 'output directory\n"
            + "    ... already exists and is not empty', 'No scoring parameters for activity X',\n"
            + "    'Could not find file ...', 'OutOfMemoryError'). Quote that exact sentence in\n"
            + "    your reply and base your fix on it - do NOT chase the Guice/injection wrapper\n"
            + "    or invent unrelated config changes.\n"
            + "  - The most common matches and the RIGHT remediation:\n"
            + "      'output directory ... already exists and is not empty'\n"
            + "          → tell the user to click the 'Delete' button next to the output directory\n"
            + "            in the GUI. Or better, in agent mode change the config parameter controller.overwriteFiles to allow the agent to try around.\n"
            + "      'OutOfMemoryError' / 'Java heap space' / 'GC overhead limit exceeded'\n"
            + "          → tell the user to increase the 'Memory' (MB) text field in the GUI.\n"
            + "            Do NOT touch the config.\n"
            + "      'No scoring parameters for activity type X'\n"
            + "          → add an activityParams parameterset for X under scoring (typicalDuration\n"
            + "            is mandatory).\n"
            + "      'No such file' / 'Could not find file' for a network/plans/transit input\n"
            + "          → use list_dir to find the right path; fix inputNetworkFile/inputPlansFile\n"
            + "            in the config (do not invent paths).\n"
            + "  - Touch ONE thing per attempt. Make the minimal change that addresses the quoted\n"
            + "    error sentence and nothing else. Never alter unrelated parameters 'just in\n"
            + "    case'.\n"
            + "  - DIFF BETWEEN ATTEMPTS. After each wait_for_run, compare the new error sentence\n"
            + "    to the previous one:\n"
            + "      * Same error  → your last change did not help; REVERT it (write_config back to\n"
            + "                      the prior content) before trying something else.\n"
            + "      * New error   → previous fix worked; now address the new root cause.\n"
            + "      * No error (exit 0) → you're done. Summarise and stop.\n"
            + "    Always state explicitly in your reasoning whether the error changed.\n"
            + "  - If a tool result contains 'error', read the error message and either fix the\n"
            + "    root cause or stop and ask the user; do not blindly retry.\n\n"
            + "CONFIG SAFETY:\n"
            + "  - Use ONLY parameter names that already exist in the user's config XML or that\n"
            + "    you have seen in the MATSim config DTDs / source. If unsure, do not invent a\n"
            + "    parameter - ask the user or stop. Wrongly named params silently break runs.\n"
            + "  - When write_config is needed, return the FULL new XML content (no diff/patch).\n"
            + "    Make the smallest possible change and preserve all other parameters verbatim.\n"
            + "  - Always tell the user that they can also edit the config manually via the\n"
            + "    'Edit config' button in the GUI - sometimes that's faster and safer than a\n"
            + "    write_config tool call.\n\n"
            + "TOKEN DISCIPLINE (this conversation costs real money):\n"
            + "  - You ALREADY HAVE the config XML in this conversation after the first\n"
            + "    read_config. Do NOT call read_config again unless write_config has been called\n"
            + "    in between, or the user explicitly asks for a fresh read. The tool will return\n"
            + "    a tiny 'unchanged' stub if you do - reuse the earlier content from your\n"
            + "    context instead of asking again.\n"
            + "  - Same for read_file and list_dir on directories you have already explored.\n"
            + "  - Remember the changes YOU made via write_config - the file on disk now matches\n"
            + "    the XML you sent. Don't re-read it just to 'confirm'.\n"
            + "  - Older tool results are automatically summarised on subsequent turns; rely on\n"
            + "    your own short-term memory of the latest config you wrote.\n"
            + "  - Prefer narrow tools and small limits (e.g. tail_log max_lines=40 when you only\n"
            + "    need the last error).";

    private String callProvider(Provider p, String model, String apiKey,
    		List<Map.Entry<String, String>> hist, String sysPrompt) throws IOException, InterruptedException {
    	switch (p) {
    	case OPENAI:     return callOpenAiCompatible(p.defaultEndpoint, model, apiKey, hist, false, sysPrompt);
    	case OPENROUTER: return callOpenAiCompatible(p.defaultEndpoint, model, apiKey, hist, true, sysPrompt);
    	case OLLAMA:     return callOpenAiCompatible(p.defaultEndpoint, model, "", hist, false, sysPrompt);
    	case ANTHROPIC:  return callAnthropic(p.defaultEndpoint, model, apiKey, hist, sysPrompt);
    	case GEMINI:     return callGemini(p.defaultEndpoint, model, apiKey, hist, sysPrompt);
    	case JLAMA:      return callJlama(model, hist, sysPrompt);
    	default:         throw new IOException("Unknown provider: " + p);
    	}
    }

    /**
     * Embedded (pure-Java) chat via JLama. Loads the model on first use into
     * {@link JlamaService#DEFAULT_MODEL_DIR}, downloading it from HuggingFace if
     * necessary. Subsequent calls reuse the in-memory model.
     */
    private String callJlama(String model, List<Map.Entry<String, String>> hist, String sysPrompt)
            throws IOException {
        if (!JlamaService.isAvailable()) {
            throw new IOException("Embedded (Java) provider is not available - the JLama "
                    + "library is not on the classpath. Add com.github.tjake:jlama-core to "
                    + "your dependencies (it is declared optional in the studio's pom.xml).");
        }
        // The history list contains every prior user/assistant turn; the latest user turn
        // is appended last. We split it back into "history without last user turn" + "last user turn".
        List<Map.Entry<String, String>> prior = new ArrayList<>(hist);
        String latestUser;
        if (!prior.isEmpty() && "user".equals(prior.get(prior.size() - 1).getKey())) {
            latestUser = prior.remove(prior.size() - 1).getValue();
        } else {
            latestUser = "";
        }
        return JlamaService.chat(model, sysPrompt, prior, latestUser, 512, 0.3f);
    }

    /** OpenAI-compatible Chat Completions (used for OpenAI, OpenRouter, and Ollama). */
    private String callOpenAiCompatible(String endpoint, String model, String apiKey,
            List<Map.Entry<String, String>> hist, boolean openrouter, String sysPrompt) throws IOException, InterruptedException {
StringBuilder body = new StringBuilder();
body.append("{\"model\":").append(jsonStr(model)).append(",");
body.append("\"messages\":[");
// Use sysPrompt instead of SYSTEM_PROMPT
body.append("{\"role\":\"system\",\"content\":").append(jsonStr(sysPrompt)).append("}");
        for (Map.Entry<String, String> m : hist) {
            body.append(",{\"role\":").append(jsonStr(m.getKey()))
                .append(",\"content\":").append(jsonStr(m.getValue())).append("}");
        }
        body.append("]}");

        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json");
        if (apiKey != null && !apiKey.isBlank()) {
            rb.header("Authorization", "Bearer " + apiKey);
        }
        if (openrouter) {
            rb.header("HTTP-Referer", "https://matsim.org");
            rb.header("X-Title", "MATSim Copilot");
        }
        HttpRequest req = rb.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (java.net.ConnectException ce) {
            throw new IOException("Could not connect to " + endpoint
                    + ".\nFor Ollama: install it from https://ollama.com and start the server "
                    + "(it normally autostarts after install). Then run e.g. `ollama pull "
                    + model + "` once.", ce);
        }
        if (resp.statusCode() / 100 != 2) {
            String respBody = resp.body();
            if (resp.statusCode() == 404 && respBody != null && respBody.contains("not found")
                    && endpoint.contains("11434")) {
                throw new IOException("Ollama: model '" + model + "' is not installed.\n"
                        + "Run this once in a terminal:\n    ollama pull " + model);
            }
            throw new IOException("HTTP " + resp.statusCode() + ": " + respBody);
        }
        String content = JsonMini.findStringValue(resp.body(), "content");
        if (content == null) throw new IOException("No content in response: " + resp.body());
        return content;
    }

    private String callAnthropic(String endpoint, String model, String apiKey,
    		List<Map.Entry<String, String>> hist, String sysPrompt) throws IOException, InterruptedException {
    	StringBuilder body = new StringBuilder();
    	body.append("{\"model\":").append(jsonStr(model)).append(",");
    	body.append("\"max_tokens\":2048,");
    	// Use sysPrompt instead of SYSTEM_PROMPT
    	body.append("\"system\":").append(jsonStr(sysPrompt)).append(",");
    	body.append("\"messages\":[");
        boolean first = true;
        for (Map.Entry<String, String> m : hist) {
            if (!first) body.append(",");
            first = false;
            body.append("{\"role\":").append(jsonStr(m.getKey()))
                .append(",\"content\":").append(jsonStr(m.getValue())).append("}");
        }
        body.append("]}");

        HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + resp.statusCode() + ": " + resp.body());
        }
        // Anthropic: { "content": [ { "type":"text", "text":"..." } ] }
        String text = JsonMini.findStringValue(resp.body(), "text");
        if (text == null) throw new IOException("No text in response: " + resp.body());
        return text;
    }

    private String callGemini(String endpointTemplate, String model, String apiKey,
            List<Map.Entry<String, String>> hist, String sysPrompt) throws IOException, InterruptedException {
    	String endpoint = endpointTemplate.replace("{model}", model) + "?key=" + apiKey;

    	StringBuilder body = new StringBuilder();
    	// Use sysPrompt instead of SYSTEM_PROMPT
    	body.append("{\"systemInstruction\":{\"parts\":[{\"text\":")
    	.append(jsonStr(sysPrompt)).append("}]},");
    	body.append("\"contents\":[");
        boolean first = true;
        for (Map.Entry<String, String> m : hist) {
            if (!first) body.append(",");
            first = false;
            String role = "assistant".equals(m.getKey()) ? "model" : "user";
            body.append("{\"role\":").append(jsonStr(role))
                .append(",\"parts\":[{\"text\":").append(jsonStr(m.getValue())).append("}]}");
        }
        body.append("]}");

        HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IOException(formatGeminiError(model, resp.statusCode(), resp.body()));
        }
        // Gemini: { "candidates":[{"content":{"parts":[{"text":"..."}]}}] }
        String text = JsonMini.findStringValue(resp.body(), "text");
        if (text == null) throw new IOException("No text in response: " + resp.body());
        return text;
    }

    /**
     * Translate a Gemini error response (404 / 429 / 503 / …) to a single, friendly
     * line. Falls back to the raw body if the JSON cannot be parsed.
     */
    static String formatGeminiError(String model, int status, String body) {
        try {
            com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(body);
            com.fasterxml.jackson.databind.JsonNode err = root.path("error");
            String message = err.path("message").asText("");
            String statusName = err.path("status").asText("");
            // Look for a RetryInfo.retryDelay field (e.g. "56s").
            String retry = "";
            for (com.fasterxml.jackson.databind.JsonNode d : err.path("details")) {
                String type = d.path("@type").asText("");
                if (type.contains("RetryInfo")) {
                    retry = d.path("retryDelay").asText("");
                    break;
                }
            }
            switch (status) {
                case 404:
                    return "Gemini: model '" + model + "' is not available (404 NOT_FOUND).\n"
                            + "Likely the model name was renamed or deprecated. Pick a current model from the\n"
                            + "Settings dropdown - good defaults today are 'gemini-2.5-flash' or 'gemini-2.5-pro'.\n"
                            + "(Underlying message: " + shorten(message, 200) + ")";
                case 429:
                    return "Gemini: rate limit / quota exceeded (429 " + statusName + ")."
                            + (retry.isEmpty() ? "" : " Retry in ~" + retry + ".") + "\n"
                            + "On the free tier, gemini-2.5-pro has very low daily limits. Either switch to\n"
                            + "'gemini-2.5-flash' / 'gemini-2.5-flash-lite' (much higher free quota) or enable\n"
                            + "billing for your Google AI Studio project.";
                case 503:
                    return "Gemini: model is overloaded (503 UNAVAILABLE). The Google API itself reports\n"
                            + "high demand - simply retry in a few seconds, or switch to a less-busy model\n"
                            + "(e.g. 'gemini-2.5-flash-lite' or 'gemini-2.0-flash').";
                case 401:
                case 403:
                    return "Gemini: authentication failed (HTTP " + status + "). Re-check your API key in\n"
                            + "Settings (it must come from https://aistudio.google.com/apikey and the\n"
                            + "Generative Language API must be enabled for that project).";
                default:
                    return "Gemini HTTP " + status + " " + statusName
                            + (message.isEmpty() ? "" : ": " + shorten(message, 400));
            }
        } catch (Exception parseEx) {
            return "Gemini HTTP " + status + ": " + shorten(body, 600);
        }
    }

    private static String shorten(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    // (callOllama removed: Ollama is now reached via the OpenAI-compatible
    //  /v1/chat/completions endpoint through callOpenAiCompatible.)

    // ------------------------------------------------------ JSON helpers

    /** JSON string literal, escaping characters as required. */
    private static String jsonStr(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b");  break;
                case '\f': sb.append("\\f");  break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
                    else sb.append(ch);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /**
     * Tiny JSON helper – just enough to extract the first string value of a given key
     * from a (possibly nested) JSON document. Avoids pulling in an extra dependency.
     */
    static final class JsonMini {
        /** Returns the first occurrence of "key": "..." in the document, decoded, or null. */
        static String findStringValue(String json, String key) {
            String needle = "\"" + key + "\"";
            int i = 0;
            while ((i = json.indexOf(needle, i)) >= 0) {
                int j = i + needle.length();
                // skip whitespace
                while (j < json.length() && Character.isWhitespace(json.charAt(j))) j++;
                if (j < json.length() && json.charAt(j) == ':') {
                    j++;
                    while (j < json.length() && Character.isWhitespace(json.charAt(j))) j++;
                    if (j < json.length() && json.charAt(j) == '"') {
                        return readJsonString(json, j);
                    }
                }
                i = j;
            }
            return null;
        }

        /** Read a JSON string starting at index of opening quote; return decoded value. */
        private static String readJsonString(String s, int openQuoteIdx) {
            StringBuilder sb = new StringBuilder();
            int i = openQuoteIdx + 1;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '"') return sb.toString();
                if (c == '\\' && i + 1 < s.length()) {
                    char esc = s.charAt(i + 1);
                    switch (esc) {
                        case '"':  sb.append('"');  i += 2; break;
                        case '\\': sb.append('\\'); i += 2; break;
                        case '/':  sb.append('/');  i += 2; break;
                        case 'b':  sb.append('\b'); i += 2; break;
                        case 'f':  sb.append('\f'); i += 2; break;
                        case 'n':  sb.append('\n'); i += 2; break;
                        case 'r':  sb.append('\r'); i += 2; break;
                        case 't':  sb.append('\t'); i += 2; break;
                        case 'u':
                            if (i + 5 < s.length()) {
                                sb.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16));
                                i += 6;
                            } else { i = s.length(); }
                            break;
                        default:   sb.append(esc); i += 2; break;
                    }
                } else {
                    sb.append(c);
                    i++;
                }
            }
            return sb.toString();
        }
    }

    // make compiler happy about unused import warnings on some JDKs
    @SuppressWarnings("unused")
    private static Component noOp() { return null; }
}
