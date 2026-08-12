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
@Command(name = "skill", mixinStandardHelpOptions = true,
        description = "Show Agent decision guidance for one Java-analysis scene.")
public final class SkillCommand implements Callable<Integer> {

    static final List<String> SCENES = List.of(
            "core", "explore", "trace", "branch", "relations", "spring", "flow", "topics");

    @Parameters(index = "0", arity = "0..1", defaultValue = "core",
            description = "Scene: core | explore | trace | branch | relations | spring | flow | topics.")
    String scene;

    @Override
    public Integer call() {
        String selected = scene == null ? "core" : scene.trim().toLowerCase(Locale.ROOT);
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
