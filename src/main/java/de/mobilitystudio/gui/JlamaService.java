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
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Bridge to the optional <a href="https://github.com/tjake/Jlama">JLama</a> dependency:
 * a pure-Java LLM inference engine. Lets the MATSim Copilot run a small model fully
 * offline, with no need to install Ollama, Python, or anything else.
 *
 * <p>Models are downloaded lazily on first use to {@code ~/.matsim/copilot-models/}
 * and cached there. The download is performed by JLama itself
 * ({@link com.github.tjake.jlama.safetensors.SafeTensorSupport#maybeDownloadModel}).</p>
 *
 * <p>All JLama types are referenced via reflection so this class still compiles when
 * the optional {@code jlama-core} dependency is absent and produces a friendly error
 * at runtime if the user picks the JLama provider without the JAR on the classpath.</p>
 *
 * <p>For SIMD-accelerated inference, launch the JVM with
 * {@code --add-modules jdk.incubator.vector}. Without it JLama still works but uses
 * scalar arithmetic and is noticeably slower.</p>
 *
 * @author ikaddoura / Claude
 */
final class JlamaService {

    private static final Logger log = LogManager.getLogger(JlamaService.class);

    /** Default cache directory for downloaded models. */
    static final Path DEFAULT_MODEL_DIR = Path.of(System.getProperty("user.home"),
            ".matsim", "copilot-models");

    /**
     * Recommended small models that work well with JLama and fit on a typical laptop.
     * Sizes are after Q4 quantisation. The first one is the safest default.
     */
    static final String[] DEFAULT_MODELS = new String[] {
            "tjake/Llama-3.2-1B-Instruct-JQ4",          // ~1.0 GB, instruction-tuned, decent quality
            "tjake/Qwen2.5-0.5B-Instruct-JQ4",          // ~0.5 GB, very fast, weaker quality
            "tjake/Llama-3.2-3B-Instruct-JQ4",          // ~2.5 GB, much better at reasoning
            "tjake/Qwen2.5-Coder-1.5B-Instruct-JQ4",    // ~1.2 GB, code-focussed
            "tjake/Phi-3-mini-4k-instruct-JQ4",         // ~2.5 GB, MS Phi-3, broad knowledge
    };

    /** Cached loaded models keyed by full model name. */
    private static final Map<String, Object> MODEL_CACHE = new ConcurrentHashMap<>();
    /** Cached "promptSupport" lookup result per model (Optional<PromptSupport>). */
    private static final Map<String, Object> PROMPT_SUPPORT_CACHE = new ConcurrentHashMap<>();

    private JlamaService() {}

    /** True if {@code com.github.tjake.jlama.model.AbstractModel} is on the classpath. */
    static boolean isAvailable() {
        try {
            Class.forName("com.github.tjake.jlama.model.AbstractModel");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Run a single chat-style turn against a JLama model.
     *
     * @param fullModelName   HuggingFace-style {@code owner/name} (e.g. {@code tjake/Llama-3.2-1B-Instruct-JQ4})
     * @param systemPrompt    system instruction (may be empty/null)
     * @param history         alternating user/assistant messages so far (excluding this turn);
     *                        each entry's key is the role ({@code "user"} / {@code "assistant"}),
     *                        value is the content.
     * @param userText        the user's new message
     * @param maxTokens       max tokens to generate
     * @param temperature     sampling temperature (e.g. 0.3f for focused answers)
     * @return the assistant's reply text
     */
    static String chat(String fullModelName, String systemPrompt,
                       List<Map.Entry<String, String>> history, String userText,
                       int maxTokens, float temperature) throws IOException {

        if (!isAvailable()) {
            throw new IOException("The 'Embedded (Java)' provider needs the JLama library on the "
                    + "classpath, but com.github.tjake.jlama.model.AbstractModel was not found.");
        }
        try {
            Object model = MODEL_CACHE.computeIfAbsent(fullModelName, name -> {
                try {
                    log.info("Loading JLama model '" + name + "' (this may take a while on first run)…");
                    return loadModel(name);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            Object promptCtx = buildPrompt(fullModelName, model, systemPrompt, history, userText);
            return generate(model, promptCtx, temperature, maxTokens);

        } catch (RuntimeException re) {
            // Unwrap from computeIfAbsent etc.
            if (re.getCause() instanceof IOException ioe) throw ioe;
            throw new IOException(re.getMessage() == null ? re.toString() : re.getMessage(), re);
        } catch (Exception ex) {
            throw new IOException(ex.getMessage() == null ? ex.toString() : ex.getMessage(), ex);
        }
    }

    // ------------------------------------------------------------------ reflection plumbing

    /** Download (if needed) and load the given model into memory. */
    private static Object loadModel(String fullModelName) throws Exception {
        File dir = DEFAULT_MODEL_DIR.toFile();
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Could not create model cache directory " + dir);
        }
        Class<?> sts = Class.forName("com.github.tjake.jlama.safetensors.SafeTensorSupport");
        // public static File maybeDownloadModel(String modelDir, String fullModelName) throws IOException
        File modelPath = (File) sts.getMethod("maybeDownloadModel", String.class, String.class)
                .invoke(null, dir.getAbsolutePath(), fullModelName);

        Class<?> ms = Class.forName("com.github.tjake.jlama.model.ModelSupport");
        Class<?> dtype = Class.forName("com.github.tjake.jlama.safetensors.DType");
        Object f32 = dtype.getField("F32").get(null);
        Object i8  = dtype.getField("I8").get(null);
        // public static AbstractModel loadModel(File model, DType workingMemoryType, DType workingQuantizationType)
        return ms.getMethod("loadModel", File.class, dtype, dtype).invoke(null, modelPath, f32, i8);
    }

    /** Build a {@code PromptContext} via the model's {@code PromptSupport.Builder}, or a plain prompt fallback. */
    private static Object buildPrompt(String fullModelName, Object model, String systemPrompt,
                                       List<Map.Entry<String, String>> history, String userText) throws Exception {
        Object promptSupportOpt = PROMPT_SUPPORT_CACHE.computeIfAbsent(fullModelName, k -> {
            try { return model.getClass().getMethod("promptSupport").invoke(model); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        // promptSupportOpt is an Optional<PromptSupport>
        boolean present = (Boolean) promptSupportOpt.getClass().getMethod("isPresent").invoke(promptSupportOpt);
        if (!present) {
            // Tokenizer doesn't expose chat templates - fall back to a flat prompt.
            StringBuilder sb = new StringBuilder();
            if (systemPrompt != null && !systemPrompt.isBlank()) sb.append(systemPrompt).append("\n\n");
            for (Map.Entry<String, String> e : history) sb.append(e.getKey()).append(": ").append(e.getValue()).append("\n");
            sb.append("user: ").append(userText).append("\nassistant: ");
            Class<?> pc = Class.forName("com.github.tjake.jlama.safetensors.prompt.PromptContext");
            return pc.getMethod("of", String.class).invoke(null, sb.toString());
        }
        Object ps = promptSupportOpt.getClass().getMethod("get").invoke(promptSupportOpt);
        Object builder = ps.getClass().getMethod("builder").invoke(ps);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            builder = builder.getClass().getMethod("addSystemMessage", String.class).invoke(builder, systemPrompt);
        }
        for (Map.Entry<String, String> e : history) {
            String role = e.getKey();
            if ("assistant".equals(role)) {
                builder = builder.getClass().getMethod("addAssistantMessage", String.class).invoke(builder, e.getValue());
            } else {
                builder = builder.getClass().getMethod("addUserMessage", String.class).invoke(builder, e.getValue());
            }
        }
        builder = builder.getClass().getMethod("addUserMessage", String.class).invoke(builder, userText);
        // Make sure the model is asked to reply.
        try {
            builder = builder.getClass().getMethod("addGenerationPrompt", boolean.class).invoke(builder, true);
        } catch (NoSuchMethodException ignore) { /* older versions */ }
        return builder.getClass().getMethod("build").invoke(builder);
    }

    /** Call {@code AbstractModel.generate(...)} and return the response text. */
    private static String generate(Object model, Object promptCtx, float temperature, int maxTokens)
            throws Exception {
        Class<?> abstractModel = Class.forName("com.github.tjake.jlama.model.AbstractModel");
        Class<?> promptContext = Class.forName("com.github.tjake.jlama.safetensors.prompt.PromptContext");
        // public Response generate(UUID, PromptContext, float, int, BiConsumer<String,Float>)
        java.util.function.BiConsumer<String, Float> sink = (s, f) -> { /* discard streamed tokens */ };
        Object response = abstractModel
                .getMethod("generate", UUID.class, promptContext, float.class, int.class,
                        java.util.function.BiConsumer.class)
                .invoke(model, UUID.randomUUID(), promptCtx, temperature, maxTokens, sink);
        Object text = response.getClass().getField("responseText").get(response);
        return text == null ? "" : text.toString().trim();
    }
}
