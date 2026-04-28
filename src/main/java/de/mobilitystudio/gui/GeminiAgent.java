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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Multi-step "agent" loop on top of Google's Gemini function-calling API.
 *
 * <p>The agent owns a conversation that is (a) the user's goal and (b) a sequence
 * of tool calls / tool results. It iterates until either the model returns a final
 * text answer, the maximum number of iterations is reached, or it is cancelled.</p>
 *
 * @author ikaddoura / Claude
 */
final class GeminiAgent {

    private static final Logger log = LogManager.getLogger(GeminiAgent.class);
    private static final ObjectMapper M = new ObjectMapper();

    private static final int MAX_ITERATIONS = 20;

    /** Callback notified about progress events; runs on the worker thread. */
    interface ProgressListener {
        /** Called when the model emits a tool call (after parsing, before execution). */
        void onToolCall(String name, JsonNode args);
        /** Called with the JSON result returned to the model. */
        void onToolResult(String name, JsonNode result);
        /** Called for free-text "thoughts" the model emits between tool calls. */
        default void onModelText(String text) { }
    }

    private final HttpClient http;
    private final String endpointTemplate;
    private final String model;
    private final String apiKey;
    private final MatsimAgentTools tools;
    private final String systemPrompt;

    GeminiAgent(HttpClient http, String endpointTemplate, String model, String apiKey,
                MatsimAgentTools tools, String systemPrompt) {
        this.http = http;
        this.endpointTemplate = endpointTemplate;
        this.model = model;
        this.apiKey = apiKey;
        this.tools = tools;
        this.systemPrompt = systemPrompt;
    }

    /**
     * Run one user turn through the agent loop. The {@code contents} list is the persistent
     * Gemini conversation (user/model parts); on return it has been extended with the new
     * exchange so subsequent turns keep context.
     *
     * @return the final assistant text (already appended to {@code contents} as a model turn).
     */
    String runTurn(List<ObjectNode> contents, String userText, ProgressListener listener)
            throws IOException, InterruptedException {

        // Append the new user turn.
        contents.add(userPart(userText));

        for (int iter = 0; iter < MAX_ITERATIONS; iter++) {
            // Compact older tool-results before each call: only the most recent results
            // are needed verbatim, the rest can be reduced to a tiny summary. This is
            // by far the biggest token-saver in the agent loop.
            compactOldToolResults(contents);
            ObjectNode response = callGemini(contents);

            // Pull the first candidate's content (which is itself a "content" part with a "parts" array).
            JsonNode candidates = response.path("candidates");
            if (!candidates.isArray() || candidates.isEmpty()) {
                throw new IOException("Gemini returned no candidates: " + response);
            }
            JsonNode candidateContent = candidates.get(0).path("content");
            if (candidateContent.isMissingNode()) {
                throw new IOException("Gemini candidate has no content: " + response);
            }
            // Persist the model turn into the conversation as-is.
            contents.add(((ObjectNode) candidateContent).deepCopy());

            // Inspect the parts: collect any function calls and free text.
            List<JsonNode> functionCalls = new ArrayList<>();
            StringBuilder textBuf = new StringBuilder();
            for (JsonNode part : candidateContent.path("parts")) {
                if (part.has("functionCall")) {
                    functionCalls.add(part.get("functionCall"));
                } else if (part.has("text")) {
                    textBuf.append(part.get("text").asText());
                }
            }

            if (functionCalls.isEmpty()) {
                // Final answer.
                String finalText = textBuf.toString().trim();
                if (finalText.isEmpty()) finalText = "(no answer)";
                return finalText;
            }

            // Otherwise: emit any pre-call thoughts, execute every tool call, attach the
            // results as a single user turn with functionResponse parts, then loop.
            if (textBuf.length() > 0 && listener != null) listener.onModelText(textBuf.toString());

            ObjectNode toolResultTurn = M.createObjectNode();
            toolResultTurn.put("role", "user");
            ArrayNode resultParts = M.createArrayNode();
            for (JsonNode call : functionCalls) {
                String name = call.path("name").asText();
                JsonNode args = call.path("args");
                if (listener != null) listener.onToolCall(name, args);
                ObjectNode result = tools.execute(name, args);
                if (listener != null) listener.onToolResult(name, result);

                ObjectNode part = M.createObjectNode();
                ObjectNode fr = M.createObjectNode();
                fr.put("name", name);
                ObjectNode response2 = M.createObjectNode();
                // Gemini wants response.content (or any object); we pass the whole result object.
                response2.set("content", result);
                fr.set("response", response2);
                part.set("functionResponse", fr);
                resultParts.add(part);
            }
            toolResultTurn.set("parts", resultParts);
            contents.add(toolResultTurn);
        }

        return "[Agent stopped: reached max " + MAX_ITERATIONS + " iterations without a final answer.]";
    }

    // -------------------------------------------------------------------- HTTP

    private ObjectNode callGemini(List<ObjectNode> contents) throws IOException, InterruptedException {
        String endpoint = endpointTemplate.replace("{model}", model) + "?key=" + apiKey;

        ObjectNode body = M.createObjectNode();

        // System instruction
        ObjectNode sys = M.createObjectNode();
        ArrayNode sysParts = M.createArrayNode();
        sysParts.add(M.createObjectNode().put("text", systemPrompt));
        sys.set("parts", sysParts);
        body.set("systemInstruction", sys);

        // Conversation
        ArrayNode contentsArr = M.createArrayNode();
        for (ObjectNode c : contents) contentsArr.add(c);
        body.set("contents", contentsArr);

        // Tools
        ArrayNode toolsArr = M.createArrayNode();
        ObjectNode toolWrapper = M.createObjectNode();
        ArrayNode declarations = M.createArrayNode();
        for (MatsimAgentTools.ToolSpec t : MatsimAgentTools.toolSpecs()) {
            ObjectNode decl = M.createObjectNode();
            decl.put("name", t.name);
            decl.put("description", t.description);
            decl.set("parameters", t.parameters);
            declarations.add(decl);
        }
        toolWrapper.set("functionDeclarations", declarations);
        toolsArr.add(toolWrapper);
        body.set("tools", toolsArr);

        ObjectNode toolConfig = M.createObjectNode();
        ObjectNode fcc = M.createObjectNode();
        fcc.put("mode", "AUTO");
        toolConfig.set("functionCallingConfig", fcc);
        body.set("toolConfig", toolConfig);

        String bodyStr = M.writeValueAsString(body);
        HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(180))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(bodyStr)).build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("Gemini HTTP " + resp.statusCode() + ": " + resp.body());
        }
        JsonNode parsed = M.readTree(resp.body());
        if (!(parsed instanceof ObjectNode)) {
            throw new IOException("Unexpected Gemini response (not an object): " + resp.body());
        }
        return (ObjectNode) parsed;
    }

    // -------------------------------------------------------------------- helpers

    static ObjectNode userPart(String text) {
        ObjectNode c = M.createObjectNode();
        c.put("role", "user");
        ArrayNode parts = M.createArrayNode();
        parts.add(M.createObjectNode().put("text", text));
        c.set("parts", parts);
        return c;
    }

    /** Helper: a "model" content node carrying just text – useful to seed the conversation. */
    static ObjectNode modelTextPart(String text) {
        ObjectNode c = M.createObjectNode();
        c.put("role", "model");
        ArrayNode parts = M.createArrayNode();
        parts.add(M.createObjectNode().put("text", text));
        c.set("parts", parts);
        return c;
    }

    /** Suppress unused-import warning when a future provider is added. */
    @SuppressWarnings("unused")
    private static Consumer<String> noOp() { return s -> { }; }

    // ---------------------------------------------------------- history compaction

    /** Maximum chars to keep per tool-response part once it has been "consumed". */
    private static final int OLD_TOOL_RESULT_CHARS = 200;

    /**
     * Walk the conversation and replace every {@code functionResponse.content} that is
     * older than the most recent tool-result turn with a tiny summary.
     */
    static void compactOldToolResults(List<ObjectNode> contents) {
        int lastToolResultsIdx = -1;
        for (int i = contents.size() - 1; i >= 0; i--) {
            if (isToolResultsTurn(contents.get(i))) { lastToolResultsIdx = i; break; }
        }
        for (int i = 0; i < contents.size(); i++) {
            if (i == lastToolResultsIdx) continue;
            ObjectNode turn = contents.get(i);
            if (!isToolResultsTurn(turn)) continue;
            for (JsonNode part : turn.path("parts")) {
                if (!part.has("functionResponse")) continue;
                JsonNode fr = part.get("functionResponse");
                if (!(fr instanceof ObjectNode)) continue;
                JsonNode resp = fr.path("response");
                if (!(resp instanceof ObjectNode respObj)) continue;
                JsonNode content = respObj.path("content");
                if (content.has("compacted")) continue; // already compacted
                ObjectNode compact = M.createObjectNode();
                compact.put("compacted", true);
                compact.put("summary", summariseForHistory(content));
                respObj.set("content", compact);
            }
        }
    }

    private static boolean isToolResultsTurn(ObjectNode turn) {
        if (!"user".equals(turn.path("role").asText())) return false;
        for (JsonNode part : turn.path("parts")) {
            if (part.has("functionResponse")) return true;
        }
        return false;
    }

    private static String summariseForHistory(JsonNode content) {
        if (content == null || content.isNull()) return "ok";
        if (content.has("error")) return "error: " + truncate(content.path("error").asText(), 120);
        if (content.has("exit_code")) return "exit_code=" + content.get("exit_code").asInt();
        if (content.has("bytes_written"))
            return content.get("bytes_written").asInt() + " bytes written to "
                    + truncate(content.path("path").asText(""), 80);
        if (content.has("entries"))
            return content.get("entries").size() + " entries in "
                    + truncate(content.path("path").asText(""), 80);
        if (content.has("size") && content.has("path"))
            return "file " + truncate(content.path("path").asText(""), 80)
                    + " (" + content.get("size").asInt() + " bytes)";
        return truncate(content.toString(), OLD_TOOL_RESULT_CHARS);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
