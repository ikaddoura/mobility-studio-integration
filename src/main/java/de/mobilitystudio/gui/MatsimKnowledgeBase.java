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

/**
 * Static MATSim knowledge and dynamic environment info injected into the Copilot.
 *
 * @author ikaddoura / Claude
 */
final class MatsimKnowledgeBase {

    private MatsimKnowledgeBase() { }

    private static volatile String CACHED_VERSION;

    static String matsimVersion() {
        String v = CACHED_VERSION;
        if (v != null) return v;
        try {
            Class<?> gbl = Class.forName("org.matsim.core.gbl.Gbl");
            Object o = gbl.getMethod("getBuildInfoString").invoke(null);
            v = o == null ? "unknown" : o.toString().trim();
        } catch (Throwable t) {
            v = "unknown";
        }
        if (v.isEmpty()) v = "unknown";
        CACHED_VERSION = v;
        return v;
    }

    /**
     * Default markdown instructions exposed to the user in the Copilot Settings tab.
     * The user can edit or delete this to save tokens or update it for newer MATSim versions.
     */
    static final String DEFAULT_INSTRUCTIONS = 
            "### GUI Capabilities\n"
            + "Refer the user to these instead of doing equivalent work through tools:\n"
            + "- **'Edit config' button**: opens the currently selected config XML in the in-app editor.\n"
            + "- **'Create config' button**: starts a fresh config from a template.\n"
            + "- **'Delete' button**: removes the previous run's output folder. Use this when MATSim aborts with 'output directory ... already exists'. But in agent mode rather suggest to change the config parameter to deleteDirectoryIfExists. "
            + "Otherwise the agent can't do it's job. \n"
            + "- **'Memory' text field**: controls JVM -Xmx. Recommend increasing this for OutOfMemoryError.\n"
            + "\n"
            + "### MATSim Config Primer\n"
            + "Canonical module/parameter names (do not invent variants):\n"
            + "- **Top-level modules**: `controller` (preferred over controler), `network`, `plans`, `facilities`, `transit`, `vehicles`, `qsim`, `scoring`, `routing`, `replanning`.\n"
            + "- **controller**: `outputDirectory`, `firstIteration`, `lastIteration`, `overwriteFiles` (values: failIfDirectoryExists, deleteDirectoryIfExists, overwriteExistingFiles).\n"
            + "- **scoring**: `scoringParameters` -> `activityParams` (for each activity like home/work/leisure, needs typicalDuration) -> `modeParams`.\n"
            + "- **replanning**: `strategysettings` with strategyName and weight.\n"
            + "- **Documentation**: https://www.matsim.org/javadoc/, https://github.com/matsim-org/matsim-libs\n";

    /** Assembled extra system-prompt block containing dynamic JVM info and the user's custom instructions. */
    static String extraSystemPrompt(String customInstructions) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n--- Studio context ---\n");
        sb.append("MATSim build linked into this studio: ").append(matsimVersion()).append('\n');
        sb.append("Java: ").append(System.getProperty("java.version", "unknown"))
          .append(" / OS: ").append(System.getProperty("os.name", "?"))
          .append(' ').append(System.getProperty("os.arch", "?")).append('\n');
        
        if (customInstructions != null && !customInstructions.isBlank()) {
            sb.append("\n").append(customInstructions);
        }
        return sb.toString();
    }
}