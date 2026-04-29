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

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Multi-step agent loop for any OpenAI-compatible Chat Completions endpoint.
 *
 * <p>Confirmed working with:</p>
 * <ul>
 *     <li>OpenAI – {@code https://api.openai.com/v1/chat/completions}</li>
 *     <li>OpenRouter – {@code https://openrouter.ai/api/v1/chat/completions}</li>
 *     <li>Ollama (≥ 0.4) – {@code http://localhost:11434/v1/chat/completions}<br>
 *         (Note: the dedicated {@code /api/chat} endpoint also supports tools but with
 *         a slightly different shape; we use the OpenAI-compatible {@code /v1/} endpoint.)
 *     </li>
 *     <li>llama-server / LM Studio / vLLM / any other OpenAI-compatible server.</li>
 * </ul>
 *
 * <p>The same {@link MatsimAgentTools.ToolSpec} JSON-Schema is reused; only the
 * request/response envelopes are different from {@link GeminiAgent}.</p>
 *
 * @author ikaddoura / Claude
 */
final class OpenAiToolAgent {

    private static final Logger log = LogManager.getLogger(OpenAiToolAgent.class);
    private static final ObjectMapper M = new ObjectMapper();

    private static final int MAX_ITERATIONS = 20;
    /** Hard cap on how many older tool messages we keep verbatim; the rest are compacted. */
    private static final int OLD_TOOL_RESULT_CHARS = 200;

    private final HttpClient http;
    private final String endpoint;
    private final String model;
    private final String apiKey;       // may be null/empty for local servers
    private final boolean openRouter;  // adds the recommended OpenRouter headers
    private final MatsimAgentTools tools;
    private final String systemPrompt;

    OpenAiToolAgent(HttpClient http, String endpoint, String model, String apiKey,
                    boolean openRouter, MatsimAgentTools tools, String systemPrompt) {
        this.http = http;
        this.endpoint = endpoint;
        this.model = model;
        this.apiKey = apiKey;
        this.openRouter = openRouter;
        this.tools = tools;
        this.systemPrompt = systemPrompt;
    }

    /**
     * Run one user turn. The {@code messages} list is the persistent OpenAI-style
     * conversation; on return it has been extended with the new exchange so subsequent
     * turns keep context.
     *
     * @return the final assistant text.
     */
    String runTurn(List<ObjectNode> messages, String userText, GeminiAgent.ProgressListener listener)
            throws IOException, InterruptedException {

        // Ensure system message is at index 0.
        if (messages.isEmpty() || !"system".equals(messages.get(0).path("role").asText())) {
            messages.add(0, msg("system", systemPrompt));
        }
        messages.add(msg("user", userText));

        for (int iter = 0; iter < MAX_ITERATIONS; iter++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Agent cancelled by user.");
            }
            compactOldToolResults(messages);
            ObjectNode response = call(messages);

            JsonNode choices = response.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new IOException("No choices in response: " + response);
            }
            JsonNode message = choices.get(0).path("message");
            if (message.isMissingNode()) {
                throw new IOException("No message in first choice: " + response);
            }

            // Persist the assistant turn verbatim (keeps tool_call ids consistent).
            messages.add(((ObjectNode) message).deepCopy());

            JsonNode toolCalls = message.path("tool_calls");
            String text = message.path("content").asText("");

            if (!toolCalls.isArray() || toolCalls.isEmpty()) {
                // Final answer.
                String finalText = text == null ? "" : text.trim();
                if (finalText.isEmpty()) finalText = "(no answer)";
                return finalText;
            }

            if (!text.isBlank() && listener != null) listener.onModelText(text);

            // Execute every tool call and append a "tool" role message per call.
            for (JsonNode call : toolCalls) {
                String id = call.path("id").asText("");
                JsonNode fn = call.path("function");
                String name = fn.path("name").asText("");
                String argsJson = fn.path("arguments").asText("{}");
                JsonNode args;
                try {
                    args = M.readTree(argsJson);
                } catch (Exception ex) {
                    args = M.createObjectNode().put("_raw", argsJson);
                }
                if (listener != null) listener.onToolCall(name, args);
                ObjectNode result = tools.execute(name, args);
                if (listener != null) listener.onToolResult(name, result);

                ObjectNode toolMsg = M.createObjectNode();
                toolMsg.put("role", "tool");
                if (!id.isEmpty()) toolMsg.put("tool_call_id", id);
                toolMsg.put("name", name);
                // OpenAI/Ollama want a string here; encode the JSON result as a string.
                toolMsg.put("content", result.toString());
                messages.add(toolMsg);
            }
        }

        return "[Agent stopped: reached max " + MAX_ITERATIONS + " iterations without a final answer.]";
    }

    // -------------------------------------------------------------------- HTTP

    private ObjectNode call(List<ObjectNode> messages) throws IOException, InterruptedException {
        ObjectNode body = M.createObjectNode();
        body.put("model", model);

        ArrayNode msgs = M.createArrayNode();
        for (ObjectNode m : messages) msgs.add(m);
        body.set("messages", msgs);

        // tools[]
        ArrayNode toolsArr = M.createArrayNode();
        for (MatsimAgentTools.ToolSpec t : MatsimAgentTools.toolSpecs()) {
            ObjectNode wrapper = M.createObjectNode();
            wrapper.put("type", "function");
            ObjectNode fn = M.createObjectNode();
            fn.put("name", t.name);
            fn.put("description", t.description);
            fn.set("parameters", t.parameters);
            wrapper.set("function", fn);
            toolsArr.add(wrapper);
        }
        body.set("tools", toolsArr);
        body.put("tool_choice", "auto");
        // Streaming off (we want the whole response).
        body.put("stream", false);

        String bodyStr = M.writeValueAsString(body);
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(180))
                .header("Content-Type", "application/json");
        if (apiKey != null && !apiKey.isBlank()) {
            rb.header("Authorization", "Bearer " + apiKey);
        }
        if (openRouter) {
            rb.header("HTTP-Referer", "https://matsim.org");
            rb.header("X-Title", "MATSim Copilot");
        }
        HttpRequest req = rb.POST(HttpRequest.BodyPublishers.ofString(bodyStr)).build();
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (ConnectException ce) {
            throw new IOException("Could not connect to " + endpoint
                    + " - is the local model server running? "
                    + "(For Ollama: install from https://ollama.com and run `ollama serve`.)", ce);
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
        JsonNode parsed = M.readTree(resp.body());
        if (!(parsed instanceof ObjectNode)) {
            throw new IOException("Unexpected response (not an object): " + resp.body());
        }
        return (ObjectNode) parsed;
    }

    // -------------------------------------------------------------------- helpers

    static ObjectNode msg(String role, String content) {
        ObjectNode m = M.createObjectNode();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    /**
     * Replace the {@code content} field of every {@code role:"tool"} message that is
     * older than the most recent batch with a tiny summary, to save tokens on
     * subsequent iterations.
     */
    static void compactOldToolResults(List<ObjectNode> messages) {
        // Find the index range of the most recent contiguous block of "tool" messages.
        int lastToolEnd = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("tool".equals(messages.get(i).path("role").asText())) { lastToolEnd = i; break; }
        }
        int lastToolStart = lastToolEnd;
        while (lastToolStart > 0 && "tool".equals(messages.get(lastToolStart - 1).path("role").asText())) {
            lastToolStart--;
        }
        for (int i = 0; i < messages.size(); i++) {
            if (i >= lastToolStart && i <= lastToolEnd) continue;
            ObjectNode m = messages.get(i);
            if (!"tool".equals(m.path("role").asText())) continue;
            String content = m.path("content").asText("");
            if (content.length() <= OLD_TOOL_RESULT_CHARS) continue;
            // Cheap summary: try to parse as JSON and pick a known field; else truncate.
            String summary;
            try {
                JsonNode n = M.readTree(content);
                summary = summariseForHistory(n);
            } catch (Exception ex) {
                summary = truncate(content, OLD_TOOL_RESULT_CHARS);
            }
            m.put("content", "{\"compacted\":true,\"summary\":\"" + summary.replace("\"", "\\\"") + "\"}");
        }
    }

    private static String summariseForHistory(JsonNode content) {
        if (content == null || content.isNull()) return "ok";
        if (content.has("error")) return "error: " + truncate(content.path("error").asText(), 120);
        if (content.has("exit_code")) return "exit_code=" + content.get("exit_code").asInt();
        if (content.has("bytes_written"))
            return content.get("bytes_written").asInt() + " bytes written";
        if (content.has("entries"))
            return content.get("entries").size() + " entries";
        if (content.has("size") && content.has("path"))
            return "file " + truncate(content.path("path").asText(""), 80)
                    + " (" + content.get("size").asInt() + " bytes)";
        return truncate(content.toString(), OLD_TOOL_RESULT_CHARS);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** Suppress unused warnings on log import on some JDKs. */
    @SuppressWarnings("unused")
    private static Logger noOp() { return log; }

    /** Allow callers to share a typed message list. */
    static List<ObjectNode> newMessageList() { return new ArrayList<>(); }
}
