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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import javax.swing.JTextArea;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Tool implementations exposed to the agent (currently used by {@link GeminiAgent}).
 *
 * <p>Tools are sandboxed: file paths are resolved relative to the directory of the
 * currently selected MATSim configuration file and may not escape that directory
 * (no parent-of-root traversal).</p>
 *
 * <p>Side-effecting tools ({@code write_config}, {@code start_matsim}, {@code stop_matsim})
 * always pass through the {@link #approver} callback so the user can confirm.</p>
 *
 * @author ikaddoura / Claude
 */
final class MatsimAgentTools {

    private static final Logger log = LogManager.getLogger(MatsimAgentTools.class);

    /** Maximum bytes returned by {@code read_file}. */
    private static final int MAX_READ_BYTES = 200_000;
    /**
     * Hard refusal limit for {@code read_config}. We do NOT silently truncate the config
     * because the agent will then try to "fix" the file by writing back its truncated
     * view, corrupting it. If a config really exceeds this size, fail loudly so the
     * agent can fall back to {@code read_file} on a slice.
     */
    private static final int MAX_CONFIG_CHARS = 500_000;
    /** Maximum number of log lines returned by {@code tail_log}. */
    private static final int DEFAULT_TAIL_LINES = 200;
    private static final int MAX_TAIL_LINES = 2000;
    /** Tail size embedded in {@code wait_for_run} responses. */
    private static final int RUN_RESULT_TAIL_LINES = 400;

    private static final ObjectMapper M = new ObjectMapper();

    interface RunController {
        /** Starts MATSim asynchronously if not already running. Returns {@code true} if a start was triggered. */
        boolean start();
        /** Forcefully stops the running MATSim process. */
        void stop();
        /** Whether a MATSim process is currently running. */
        boolean isRunning();
        /**
         * Blocks (with a timeout) until the current run finishes.
         *
         * @return exit code, or {@link Integer#MIN_VALUE} if the timeout was reached, or
         *         {@code -1} if no run was active.
         */
        int waitForFinish(long timeoutMs) throws InterruptedException;
    }

    /** ApprovalCallback returns true to allow, false to deny. */
    @FunctionalInterface
    interface ApprovalCallback extends BiFunction<String, String, Boolean> { }

    private final Supplier<File> configFileSupplier;
    private final JTextArea stdOut;
    private final JTextArea stdErr;
    private final RunController runController;
    private final ApprovalCallback approver;

    /**
     * Per-file fingerprint of the most recent successful read or write performed
     * during this agent session. Used to short-circuit duplicate reads of unchanged
     * files (the single biggest token-waster in long agent loops).
     */
    private static final class FileFingerprint {
        final long mtime;
        final long size;
        FileFingerprint(long mtime, long size) { this.mtime = mtime; this.size = size; }
        boolean matches(File f) { return f.lastModified() == mtime && f.length() == size; }
    }
    private final Map<String, FileFingerprint> readCache = new ConcurrentHashMap<>();

    MatsimAgentTools(Supplier<File> configFileSupplier, JTextArea stdOut, JTextArea stdErr,
                     RunController runController, ApprovalCallback approver) {
        this.configFileSupplier = configFileSupplier != null ? configFileSupplier : () -> null;
        this.stdOut = stdOut;
        this.stdErr = stdErr;
        this.runController = runController;
        this.approver = approver != null ? approver : (a, b) -> Boolean.TRUE;
    }

    // ------------------------------------------------------------------ schema

    /** All tools, in stable order, as a list of {@code (name, description, parametersSchema)}. */
    static List<ToolSpec> toolSpecs() {
        List<ToolSpec> tools = new ArrayList<>();

        tools.add(new ToolSpec("tail_log",
                "Return the last N lines of the MATSim simulation's stdout and stderr.",
                schema(
                        prop("max_lines", "integer",
                                "Maximum number of lines per stream (default 200, max 2000)"))));

        tools.add(new ToolSpec("read_config",
                "Read the currently selected MATSim configuration XML file and return its full text. "
                        + "If the file is unchanged since you last read or wrote it in this conversation, "
                        + "this tool returns a tiny stub asking you to reuse the earlier content (token saver). "
                        + "Pass force=true only when the user explicitly asks for a fresh re-read.",
                schema(
                        prop("force", "boolean",
                                "Bypass the unchanged-file cache and always return the full content."))));

        tools.add(new ToolSpec("write_config",
                "Overwrite the currently selected MATSim configuration XML file. A timestamped "
                        + ".bak copy is created first. Requires user approval.",
                requiredSchema(new String[] { "content" },
                        prop("content", "string",
                                "The complete new XML content of the config file"),
                        prop("reason", "string",
                                "One-sentence rationale shown to the user in the approval dialog"))));

        tools.add(new ToolSpec("list_dir",
                "List the entries of a directory inside the scenario folder (the directory of the config file).",
                schema(
                        prop("rel_path", "string",
                                "Path relative to the config file's directory. Use '' or '.' for the root."))));

        tools.add(new ToolSpec("read_file",
                "Read a UTF-8 text file inside the scenario folder. Truncated to ~200 KB. "
                        + "Cached: returns an 'unchanged' stub if you have already read this file in "
                        + "this conversation and it has not been modified.",
                requiredSchema(new String[] { "rel_path" },
                        prop("rel_path", "string",
                                "Path relative to the config file's directory"),
                        prop("force", "boolean",
                                "Bypass the unchanged-file cache."))));

        tools.add(new ToolSpec("start_matsim",
                "Start a MATSim simulation in the background using the GUI's current settings. "
                        + "Requires user approval.",
                schema(
                        prop("reason", "string",
                                "One-sentence rationale shown to the user in the approval dialog"))));

        tools.add(new ToolSpec("wait_for_run",
                "Block until the currently running MATSim process finishes (or the timeout expires) "
                        + "and return the exit code plus the tail of stdout/stderr.",
                schema(
                        prop("timeout_sec", "integer",
                                "Maximum seconds to wait. Default 600."))));

        tools.add(new ToolSpec("stop_matsim",
                "Forcefully stop the currently running MATSim process. Requires user approval.",
                schema(
                        prop("reason", "string",
                                "One-sentence rationale shown to the user in the approval dialog"))));

        return tools;
    }

    // ----- Schema helpers (local to keep the spec list above readable) -----

    /** A single property entry for {@link #schema(Property...)}. */
    private record Property(String name, String type, String description) { }

    private static Property prop(String name, String type, String description) {
        return new Property(name, type, description);
    }

    /** Build a JSON-Schema {@code object} with the given properties (none required). */
    private static ObjectNode schema(Property... props) {
        return requiredSchema(new String[0], props);
    }

    /** Build a JSON-Schema {@code object} with the given properties and required-list. */
    private static ObjectNode requiredSchema(String[] required, Property... props) {
        ObjectNode root = M.createObjectNode();
        root.put("type", "object");
        ObjectNode properties = M.createObjectNode();
        for (Property p : props) {
            ObjectNode pn = M.createObjectNode();
            pn.put("type", p.type());
            pn.put("description", p.description());
            properties.set(p.name(), pn);
        }
        root.set("properties", properties);
        ArrayNode req = M.createArrayNode();
        for (String r : required) req.add(r);
        root.set("required", req);
        return root;
    }

    /** Pair of (tool name, JSON description) for a single tool. */
    static final class ToolSpec {
        final String name;
        final String description;
        final ObjectNode parameters;
        ToolSpec(String name, String description, ObjectNode parameters) {
            this.name = name; this.description = description; this.parameters = parameters;
        }
    }

    // ------------------------------------------------------------------ dispatch

    /**
     * Execute a tool and return a JSON object describing the result (or an error).
     * Never throws — errors are captured into the {@code "error"} field of the result.
     */
    ObjectNode execute(String toolName, JsonNode args) {
        if (args == null || args.isNull()) args = M.createObjectNode();
        try {
            switch (toolName) {
                case "tail_log":     return tailLog(args);
                case "read_config":  return readConfig(args);
                case "write_config": return writeConfig(args);
                case "list_dir":     return listDir(args);
                case "read_file":    return readFile(args);
                case "start_matsim": return startMatsim(args);
                case "wait_for_run": return waitForRun(args);
                case "stop_matsim":  return stopMatsim(args);
                default:             return error("Unknown tool: " + toolName);
            }
        } catch (Exception e) {
            log.warn("Agent tool '" + toolName + "' failed", e);
            return error(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** Short, one-line description of a planned tool call, used in the approval dialog and chat view. */
    static String describeCall(String toolName, JsonNode args) {
        if (args == null) args = M.createObjectNode();
        switch (toolName) {
            case "write_config": {
                String content = args.path("content").asText("");
                String reason = args.path("reason").asText("");
                return "write_config (" + content.length() + " chars)"
                        + (reason.isEmpty() ? "" : " — " + reason);
            }
            case "start_matsim": {
                String reason = args.path("reason").asText("");
                return "start_matsim" + (reason.isEmpty() ? "" : " — " + reason);
            }
            case "stop_matsim": {
                String reason = args.path("reason").asText("");
                return "stop_matsim" + (reason.isEmpty() ? "" : " — " + reason);
            }
            case "read_file":   return "read_file(" + args.path("rel_path").asText("") + ")";
            case "list_dir":    return "list_dir(" + args.path("rel_path").asText(".") + ")";
            case "wait_for_run":return "wait_for_run(timeout=" + args.path("timeout_sec").asInt(600) + "s)";
            case "tail_log":    return "tail_log(max_lines=" + args.path("max_lines").asInt(DEFAULT_TAIL_LINES) + ")";
            case "read_config": return "read_config()";
            default:            return toolName + "(" + args.toString() + ")";
        }
    }

    /** Returns true if a tool is "destructive" and must always be approved. */
    static boolean requiresApproval(String toolName) {
        return "write_config".equals(toolName) || "start_matsim".equals(toolName) || "stop_matsim".equals(toolName);
    }

    // ------------------------------------------------------------------ tools

    private ObjectNode tailLog(JsonNode args) {
        int n = clamp(args.path("max_lines").asInt(DEFAULT_TAIL_LINES), 1, MAX_TAIL_LINES);
        ObjectNode r = M.createObjectNode();
        r.put("stdout", smartExtractLines(stdOut == null ? "" : stdOut.getText(), n));
        r.put("stderr", smartExtractLines(stdErr == null ? "" : stdErr.getText(), n));
        r.put("running", runController != null && runController.isRunning());
        return r;
    }

    private ObjectNode readConfig(JsonNode args) throws IOException {
        File f = configFileSupplier.get();
        if (f == null || !f.isFile()) return error("No config file is currently selected in the GUI.");
        boolean force = args != null && args.path("force").asBoolean(false);
        // If the agent already received this file's content earlier and nothing has changed
        // on disk since then, return a tiny stub instead of re-sending ~40 KB of XML.
        FileFingerprint fp = readCache.get(f.getAbsolutePath());
        if (!force && fp != null && fp.matches(f)) {
            ObjectNode r = M.createObjectNode();
            r.put("path", f.getAbsolutePath());
            r.put("size", f.length());
            r.put("unchanged", true);
            r.put("hint", "Config file is unchanged since you last read it (or last wrote it). "
                    + "Use the content from your earlier read_config / write_config in this conversation. "
                    + "If you really need a fresh copy, mention 'force re-read' in your next user message.");
            return r;
        }
        String content = Files.readString(f.toPath());
        if (content.length() > MAX_CONFIG_CHARS) {
            return error("Config file is " + content.length() + " chars - too large to return safely "
                    + "(limit " + MAX_CONFIG_CHARS + "). Use read_file with rel_path='" + f.getName()
                    + "' to fetch slices, then write_config with the full content you constructed.");
        }
        readCache.put(f.getAbsolutePath(), new FileFingerprint(f.lastModified(), f.length()));
        ObjectNode r = M.createObjectNode();
        r.put("path", f.getAbsolutePath());
        r.put("size", content.length());
        r.put("content", content);
        r.put("truncated", false);
        return r;
    }

    private ObjectNode writeConfig(JsonNode args) throws IOException {
        File f = configFileSupplier.get();
        if (f == null) return error("No config file is currently selected in the GUI.");
        String content = args.path("content").asText(null);
        if (content == null || content.isBlank()) return error("Tool argument 'content' is required and must be non-empty.");

        // ---- basic sanity checks: refuse to write obviously broken XML ----
        String trimmed = content.stripLeading();
        if (!trimmed.startsWith("<?xml") && !trimmed.startsWith("<config")) {
            return error("Refusing to write: content does not start with '<?xml' or '<config'. "
                    + "If you only want to change a few values, return the FULL XML, not a fragment.");
        }
        if (!content.contains("</config>")) {
            return error("Refusing to write: content does not contain a closing </config> tag. "
                    + "This usually means the XML you produced is truncated. Re-read the file with "
                    + "read_config and resend the COMPLETE content.");
        }
        // Reject content that looks dramatically smaller than the existing file (likely truncation).
        if (f.isFile()) {
            long existing = f.length();
            if (existing > 5_000 && content.length() < existing / 2) {
                return error("Refusing to write: new content (" + content.length() + " chars) is less "
                        + "than half the size of the existing file (" + existing + " chars). This "
                        + "almost always indicates a truncated payload. Re-read the file in full and "
                        + "resend the COMPLETE content.");
            }
        }

        String summary = "Overwrite " + f.getAbsolutePath() + " (" + content.length() + " chars)";
        String reason = args.path("reason").asText("");
        if (!reason.isEmpty()) summary += "\n\nReason: " + reason;
        if (!Boolean.TRUE.equals(approver.apply("write_config", summary))) {
            return error("User denied write_config.");
        }

        Path backup = null;
        if (f.isFile()) {
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            backup = f.toPath().resolveSibling(f.getName() + "." + stamp + ".bak");
            Files.copy(f.toPath(), backup, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.writeString(f.toPath(), content);
        // Remember the new fingerprint so a subsequent read_config returns the
        // tiny "unchanged" stub instead of re-sending the full XML to the model.
        readCache.put(f.getAbsolutePath(), new FileFingerprint(f.lastModified(), f.length()));

        ObjectNode r = M.createObjectNode();
        r.put("path", f.getAbsolutePath());
        r.put("bytes_written", content.length());
        if (backup != null) r.put("backup", backup.toString());
        return r;
    }

    private ObjectNode listDir(JsonNode args) throws IOException {
        File root = scenarioRoot();
        if (root == null) return error("No config file selected, cannot determine scenario root.");
        String rel = args.path("rel_path").asText(".");
        File dir = resolveSandboxed(root, rel);
        if (!dir.isDirectory()) return error("Not a directory: " + rel);
        File[] entries = dir.listFiles();
        if (entries == null) entries = new File[0];
        Arrays.sort(entries, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        ArrayNode arr = M.createArrayNode();
        for (File e : entries) {
            ObjectNode n = M.createObjectNode();
            n.put("name", e.getName());
            n.put("is_dir", e.isDirectory());
            if (!e.isDirectory()) n.put("size", e.length());
            arr.add(n);
        }
        ObjectNode r = M.createObjectNode();
        r.put("path", dir.getAbsolutePath());
        r.set("entries", arr);
        return r;
    }

    private ObjectNode readFile(JsonNode args) throws IOException {
        File root = scenarioRoot();
        if (root == null) return error("No config file selected, cannot determine scenario root.");
        String rel = args.path("rel_path").asText("");
        if (rel.isEmpty()) return error("Argument 'rel_path' is required.");
        File f = resolveSandboxed(root, rel);
        if (!f.isFile()) return error("Not a regular file: " + rel);
        boolean force = args.path("force").asBoolean(false);
        FileFingerprint fp = readCache.get(f.getAbsolutePath());
        if (!force && fp != null && fp.matches(f)) {
            ObjectNode r = M.createObjectNode();
            r.put("path", f.getAbsolutePath());
            r.put("size", f.length());
            r.put("unchanged", true);
            r.put("hint", "File unchanged since you last read it - reuse the earlier content.");
            return r;
        }
        byte[] bytes = Files.readAllBytes(f.toPath());
        boolean truncated = bytes.length > MAX_READ_BYTES;
        String content = new String(truncated ? Arrays.copyOf(bytes, MAX_READ_BYTES) : bytes);
        if (!truncated) readCache.put(f.getAbsolutePath(), new FileFingerprint(f.lastModified(), f.length()));
        ObjectNode r = M.createObjectNode();
        r.put("path", f.getAbsolutePath());
        r.put("content", content);
        r.put("truncated", truncated);
        r.put("size", bytes.length);
        return r;
    }

    private ObjectNode startMatsim(JsonNode args) {
        if (runController == null) return error("No RunController is wired; cannot start MATSim.");
        if (runController.isRunning()) return error("A MATSim process is already running. Call wait_for_run or stop_matsim first.");
        String reason = args.path("reason").asText("");
        String summary = "Start a MATSim simulation using the current GUI settings."
                + (reason.isEmpty() ? "" : "\n\nReason: " + reason);
        if (!Boolean.TRUE.equals(approver.apply("start_matsim", summary))) {
            return error("User denied start_matsim.");
        }
        boolean started = runController.start();
        ObjectNode r = M.createObjectNode();
        r.put("started", started);
        return r;
    }

    private ObjectNode waitForRun(JsonNode args) throws InterruptedException {
        if (runController == null) return error("No RunController is wired.");
        int timeoutSec = clamp(args.path("timeout_sec").asInt(600), 1, 24 * 60 * 60);
        int exit = runController.waitForFinish(timeoutSec * 1000L);
        ObjectNode r = M.createObjectNode();
        if (exit == Integer.MIN_VALUE) {
            r.put("timed_out", true);
            r.put("hint", "The simulation is still running after " + timeoutSec + "s. Call wait_for_run again or stop_matsim.");
        } else {
            r.put("timed_out", false);
            r.put("exit_code", exit);
        }
        r.put("stdout_tail", smartExtractLines(stdOut == null ? "" : stdOut.getText(), RUN_RESULT_TAIL_LINES));
        r.put("stderr_tail", smartExtractLines(stdErr == null ? "" : stdErr.getText(), RUN_RESULT_TAIL_LINES));
        return r;
    }

    private ObjectNode stopMatsim(JsonNode args) {
        if (runController == null) return error("No RunController is wired.");
        if (!runController.isRunning()) {
            ObjectNode r = M.createObjectNode();
            r.put("stopped", false);
            r.put("hint", "No MATSim process was running.");
            return r;
        }
        String reason = args.path("reason").asText("");
        String summary = "Stop the running MATSim process."
                + (reason.isEmpty() ? "" : "\n\nReason: " + reason);
        if (!Boolean.TRUE.equals(approver.apply("stop_matsim", summary))) {
            return error("User denied stop_matsim.");
        }
        runController.stop();
        ObjectNode r = M.createObjectNode();
        r.put("stopped", true);
        return r;
    }

    // ------------------------------------------------------------------ helpers

    private File scenarioRoot() {
        File f = configFileSupplier.get();
        return f == null ? null : f.getAbsoluteFile().getParentFile();
    }

    private static File resolveSandboxed(File root, String rel) throws IOException {
        Path rootPath = root.toPath().toAbsolutePath().normalize();
        Path candidate = rootPath.resolve(rel == null ? "" : rel).normalize();
        if (!candidate.startsWith(rootPath)) {
            throw new IOException("Path '" + rel + "' escapes the scenario root " + rootPath);
        }
        return candidate.toFile();
    }

    private static String smartExtractLines(String s, int tailLinesCount) {
        if (s == null || s.isEmpty()) return "";
        String[] lines = s.split("\\R", -1);
        if (lines.length <= tailLinesCount) return s;

        // Find the first error line
        int firstErrIdx = -1;
        for (int i = 0; i < lines.length - tailLinesCount; i++) {
            if (lines[i].contains("Exception in thread") || lines[i].startsWith("ERROR") || lines[i].contains("Caused by:")) {
                firstErrIdx = i;
                break;
            }
        }

        StringBuilder sb = new StringBuilder();
        if (firstErrIdx >= 0) {
            sb.append("--- FIRST ERROR/EXCEPTION FOUND ---\n");
            // Append the error and up to 30 lines following it
            int endErrIdx = Math.min(firstErrIdx + 30, lines.length - tailLinesCount);
            for (int i = firstErrIdx; i < endErrIdx; i++) {
                sb.append(lines[i]).append('\n');
            }
            sb.append("\n…(").append(lines.length - tailLinesCount - endErrIdx).append(" middle lines truncated)…\n\n");
        } else {
            sb.append("…(").append(lines.length - tailLinesCount).append(" earlier lines truncated)…\n");
        }

        sb.append("--- LOG TAIL ---\n");
        for (int i = lines.length - tailLinesCount; i < lines.length; i++) {
            sb.append(lines[i]).append('\n');
        }
        return sb.toString();
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private static ObjectNode error(String msg) {
        ObjectNode r = M.createObjectNode();
        r.put("error", msg);
        return r;
    }

    /** Convenience: convert tool args into a {@link Map} (for logging/UI). Currently unused. */
    @SuppressWarnings("unused")
    private static Map<String, Object> toMap(JsonNode n) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (n != null && n.isObject()) {
            n.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().toString()));
        }
        return out;
    }
}
