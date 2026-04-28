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
 * @author ikaddoura / GitHub Copilot
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

    private static final int MAX_LOG_CHARS = 12_000;
    private static final int MAX_CONFIG_CHARS = 60_000;

    /** Provider definitions. */
    private enum Provider {
        OPENAI("OpenAI", "https://api.openai.com/v1/chat/completions",
                new String[] { "gpt-4o", "gpt-4o-mini", "gpt-4.1", "gpt-4.1-mini", "o4-mini" }, true),
        ANTHROPIC("Anthropic", "https://api.anthropic.com/v1/messages",
                new String[] { "claude-sonnet-4-20250514", "claude-opus-4-20250514", "claude-3-5-sonnet-latest",
                        "claude-3-5-haiku-latest" },
                true),
        GEMINI("Google Gemini", "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent",
                new String[] { "gemini-2.5-pro", "gemini-2.5-flash", "gemini-1.5-pro", "gemini-1.5-flash" }, true),
        OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1/chat/completions",
                new String[] { "openai/gpt-4o", "anthropic/claude-sonnet-4", "google/gemini-2.5-pro",
                        "meta-llama/llama-3.3-70b-instruct", "mistralai/mistral-large" },
                true),
        OLLAMA("Ollama (local)", "http://localhost:11434/api/chat",
                new String[] { "llama3.2", "llama3.1", "qwen2.5-coder", "mistral", "phi3" }, false);

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
    private final JComboBox<String> modelBox = new JComboBox<>();
    private final JPasswordField apiKeyField = new JPasswordField(28);
    private final JButton saveKeyBtn = new JButton("Save");
    private final JCheckBox includeLogBox = new JCheckBox("Send recent log/error output as context", true);
    private final JCheckBox includeConfigBox = new JCheckBox("Send selected configuration file as context", false);
    private final JButton settingsToggle = new JButton("\u2699 Settings \u25BC");
    private JPanel settingsPanel;
    private final JTextPane chatPane = new JTextPane();
    private final JTextArea inputArea = new JTextArea(4, 60);
    private final JButton sendBtn = new JButton("Send  (Ctrl+Enter)");
    private final JButton explainErrorBtn = new JButton("Explain last error");
    private final JButton clearBtn = new JButton("New chat");
    private final JLabel statusLabel = new JLabel(" ");

    private final JTextArea stdOutSource;
    private final JTextArea stdErrSource;
    private Supplier<File> configFileSupplier = () -> null;

    /** Suppress side effects (saving to prefs) while we programmatically populate the combo boxes. */
    private boolean suppressEvents = false;

    /** Conversation history (role, content). */
    private final List<Map.Entry<String, String>> history = new ArrayList<>();

    public MatsimCopilotPanel(JTextArea stdOutSource, JTextArea stdErrSource) {
        this.stdOutSource = stdOutSource;
        this.stdErrSource = stdErrSource;
        buildUi();
        restoreFromPrefs();
        appendSystem(
                "Hi, I am the MATSim Copilot. \uD83E\uDD16\n"
                        + "Open \u2699 Settings to pick a provider and enter your API key (saved locally).\n"
                        + "By default I receive the latest log lines so I can help interpret warnings\n"
                        + "and errors. You can also send the selected config file as additional context.\n\n"
                        + "Tip: use the button \"Explain last error\" right after a failed run.\n");
    }

    /**
     * Provide a supplier returning the currently selected MATSim configuration file
     * (or {@code null} if none). Called by {@link GuiWithConfigEditor}.
     */
    public void setConfigFileSupplier(Supplier<File> supplier) {
        this.configFileSupplier = supplier != null ? supplier : (() -> null);
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
        topBar.add(includeLogBox);
        topBar.add(includeConfigBox);
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
        c.weightx = 0;

        c.gridx = 0; c.gridy = 1; settingsPanel.add(new JLabel("API key:"), c);
        c.gridx = 1; c.gridwidth = 2; settingsPanel.add(apiKeyField, c); c.gridwidth = 1;
        c.gridx = 3; settingsPanel.add(saveKeyBtn, c);

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
        actions.add(explainErrorBtn);
        actions.add(sendBtn);
        bottom.add(actions, BorderLayout.SOUTH);

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
        saveKeyBtn.addActionListener(e -> saveCurrentKey());
        sendBtn.addActionListener(e -> onSend());
        explainErrorBtn.addActionListener(e -> onExplainError());
        clearBtn.addActionListener(e -> {
            history.clear();
            chatPane.setText("");
            appendSystem("New chat started.\n");
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

        boolean prev = suppressEvents;
        suppressEvents = true;
        try {
            modelBox.removeAllItems();
            for (String m : p.models) modelBox.addItem(m);
            modelBox.setSelectedItem(savedModel);
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
        statusLabel.setText(p.needsKey && savedKey.isEmpty()
                ? "No API key stored for " + p.label
                : " ");
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
    }

    // ------------------------------------------------------------------ chat

    private void onSend() {
        String text = inputArea.getText().trim();
        if (text.isEmpty()) return;
        inputArea.setText("");
        sendUserMessage(text, true);
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
        statusLabel.setText("Thinking…");

        new SwingWorker<String, Void>() {
            @Override protected String doInBackground() throws Exception {
                return callProvider(p, model, apiKey, history);
            }
            @Override protected void done() {
                String reply;
                try {
                    reply = get();
                } catch (Exception ex) {
                    log.warn("Copilot error", ex);
                    reply = "[Error] " + ex.getMessage();
                }
                history.add(Map.entry("assistant", reply));
                replacePlaceholderWithAssistant(reply);
                statusLabel.setText(" ");
                sendBtn.setEnabled(true);
                explainErrorBtn.setEnabled(true);
            }
        }.execute();
    }

    private String buildLogContext() {
        String err = stdErrSource != null ? stdErrSource.getText() : "";
        String out = stdOutSource != null ? stdOutSource.getText() : "";
        StringBuilder sb = new StringBuilder();
        if (err != null && !err.isBlank()) {
            sb.append("[stderr]\n").append(tail(err, MAX_LOG_CHARS / 2)).append("\n");
        }
        if (out != null && !out.isBlank()) {
            sb.append("[stdout]\n").append(tail(out, MAX_LOG_CHARS / 2));
        }
        return sb.toString();
    }

    private static String tail(String s, int maxChars) {
        if (s.length() <= maxChars) return s;
        return "…(truncated)…\n" + s.substring(s.length() - maxChars);
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

    // ------------------------------------------------------- provider calls

    private static final String SYSTEM_PROMPT =
            "You are MATSim Copilot, a friendly assistant that helps the user run MATSim "
            + "simulations. You explain warnings and errors in plain language and suggest "
            + "concrete fixes (config parameters, file paths, missing inputs, common pitfalls). "
            + "Be concise, use bullet points, and reference the relevant lines from the log "
            + "when helpful. If the user did not provide a log, answer based on general MATSim "
            + "knowledge.";

    private String callProvider(Provider p, String model, String apiKey,
                                List<Map.Entry<String, String>> hist) throws IOException, InterruptedException {
        switch (p) {
            case OPENAI:     return callOpenAiCompatible(p.defaultEndpoint, model, apiKey, hist, false);
            case OPENROUTER: return callOpenAiCompatible(p.defaultEndpoint, model, apiKey, hist, true);
            case OLLAMA:     return callOllama(p.defaultEndpoint, model, hist);
            case ANTHROPIC:  return callAnthropic(p.defaultEndpoint, model, apiKey, hist);
            case GEMINI:     return callGemini(p.defaultEndpoint, model, apiKey, hist);
            default:         throw new IOException("Unknown provider: " + p);
        }
    }

    /** OpenAI-compatible Chat Completions (used for OpenAI + OpenRouter). */
    private String callOpenAiCompatible(String endpoint, String model, String apiKey,
                                        List<Map.Entry<String, String>> hist, boolean openrouter) throws IOException, InterruptedException {
        StringBuilder body = new StringBuilder();
        body.append("{\"model\":").append(jsonStr(model)).append(",");
        body.append("\"messages\":[");
        body.append("{\"role\":\"system\",\"content\":").append(jsonStr(SYSTEM_PROMPT)).append("}");
        for (Map.Entry<String, String> m : hist) {
            body.append(",{\"role\":").append(jsonStr(m.getKey()))
                .append(",\"content\":").append(jsonStr(m.getValue())).append("}");
        }
        body.append("]}");

        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey);
        if (openrouter) {
            rb.header("HTTP-Referer", "https://matsim.org");
            rb.header("X-Title", "MATSim Copilot");
        }
        HttpRequest req = rb.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + resp.statusCode() + ": " + resp.body());
        }
        String content = JsonMini.findStringValue(resp.body(), "content");
        if (content == null) throw new IOException("No content in response: " + resp.body());
        return content;
    }

    private String callAnthropic(String endpoint, String model, String apiKey,
                                 List<Map.Entry<String, String>> hist) throws IOException, InterruptedException {
        StringBuilder body = new StringBuilder();
        body.append("{\"model\":").append(jsonStr(model)).append(",");
        body.append("\"max_tokens\":2048,");
        body.append("\"system\":").append(jsonStr(SYSTEM_PROMPT)).append(",");
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
                              List<Map.Entry<String, String>> hist) throws IOException, InterruptedException {
        String endpoint = endpointTemplate.replace("{model}", model) + "?key=" + apiKey;

        StringBuilder body = new StringBuilder();
        body.append("{\"systemInstruction\":{\"parts\":[{\"text\":")
            .append(jsonStr(SYSTEM_PROMPT)).append("}]},");
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
            throw new IOException("HTTP " + resp.statusCode() + ": " + resp.body());
        }
        // Gemini: { "candidates":[{"content":{"parts":[{"text":"..."}]}}] }
        String text = JsonMini.findStringValue(resp.body(), "text");
        if (text == null) throw new IOException("No text in response: " + resp.body());
        return text;
    }

    private String callOllama(String endpoint, String model,
                              List<Map.Entry<String, String>> hist) throws IOException, InterruptedException {
        StringBuilder body = new StringBuilder();
        body.append("{\"model\":").append(jsonStr(model)).append(",\"stream\":false,");
        body.append("\"messages\":[");
        body.append("{\"role\":\"system\",\"content\":").append(jsonStr(SYSTEM_PROMPT)).append("}");
        for (Map.Entry<String, String> m : hist) {
            body.append(",{\"role\":").append(jsonStr(m.getKey()))
                .append(",\"content\":").append(jsonStr(m.getValue())).append("}");
        }
        body.append("]}");
        HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(180))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + resp.statusCode() + ": " + resp.body()
                    + " (is Ollama running on " + endpoint + " ?)");
        }
        // Ollama: { "message": { "role":"assistant", "content":"..." }, ... }
        String content = JsonMini.findStringValue(resp.body(), "content");
        if (content == null) throw new IOException("No content in response: " + resp.body());
        return content;
    }

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
