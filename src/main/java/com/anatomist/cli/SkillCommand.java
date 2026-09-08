package com.anatomist.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

/** Emits small, task-oriented decision guides for Agent callers. */
@Command(modelTransformer = AgentHelp.class, name = "skill", mixinStandardHelpOptions = true,
        description = "Choose queries and interpret evidence for one task.")
public final class SkillCommand implements Callable<Integer> {

    static final List<String> SCENES = List.of(
            "core", "topics", "explore", "source", "trace", "relations", "spring", "versions", "maintenance");

    @Parameters(index = "0", arity = "0..1", defaultValue = "core",
            description = "Guide: core (default) | topics | explore | source | trace | relations | spring | versions | maintenance.")
    String scene;

    @Override
    public Integer call() {
        String selected = scene == null ? "core" : scene.trim().toLowerCase(Locale.ROOT);
        if ("branch".equals(selected) || "flow".equals(selected)) {
            System.err.println("ERROR: skill " + selected + " was removed; use anatomist skill source");
            return 2;
        }
        if (!SCENES.contains(selected)) {
            System.err.println("ERROR: unknown skill scene '" + scene + "'; use one of: "
                    + String.join(", ", SCENES));
            return 2;
        }
        String resource = "/META-INF/anatomist/skills/" + selected + ".md";
        try (InputStream input = SkillCommand.class.getResourceAsStream(resource)) {
            if (input == null) {
                System.err.println("ERROR: embedded skill scene is missing: " + selected);
                return 1;
            }
            String content = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            System.out.print(content);
            if (!content.endsWith("\n")) System.out.println();
            return 0;
        } catch (IOException failure) {
            System.err.println("ERROR: cannot read embedded skill scene '" + selected
                    + "': " + failure.getMessage());
            return 1;
        }
    }
}
