package com.anatomist.query;

import com.anatomist.version.SnapshotException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/** Text ranges only: Git owns textual comparison; the index owns declarations. */
final class DiffTextChanges {
    record Lines(int start, int count) {
        boolean contains(int line) { return count > 0 && line >= start && line < (long) start + count; }
    }
    record Hunk(Lines before, Lines after) {}
    private static final Pattern HUNK = Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$");

    static List<Hunk> compare(Path before, Path after) {
        List<String> command = List.of("git", "-c", "core.safecrlf=false", "diff", "--no-index",
                "--no-ext-diff", "--no-textconv", "--no-color", "--text", "--unified=0",
                "--diff-algorithm=myers", "--", before.toString(), after.toString());
        try {
            Process process = new ProcessBuilder(command).start();
            process.getOutputStream().close();
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var stdout = executor.submit(() -> process.getInputStream().readAllBytes());
                var stderr = executor.submit(() -> process.getErrorStream().readAllBytes());
                if (!process.waitFor(60, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new SnapshotException("DIFF_TEXT_TIMEOUT", "Frozen source comparison timed out");
                }
                String output = new String(stdout.get(), StandardCharsets.UTF_8);
                String error = new String(stderr.get(), StandardCharsets.UTF_8);
                if (process.exitValue() > 1)
                    throw new SnapshotException("DIFF_TEXT_FAILED", error.strip());
                List<Hunk> hunks = new ArrayList<>();
                for (String line : output.lines().toList()) {
                    var m = HUNK.matcher(line);
                    if (m.matches()) hunks.add(new Hunk(new Lines(Integer.parseInt(m.group(1)),
                            m.group(2) == null ? 1 : Integer.parseInt(m.group(2))),
                            new Lines(Integer.parseInt(m.group(3)), m.group(4) == null ? 1 : Integer.parseInt(m.group(4)))));
                }
                if (process.exitValue() == 1 && hunks.isEmpty())
                    throw new SnapshotException("DIFF_TEXT_FAILED", "Text difference has no navigable ranges");
                return List.copyOf(hunks);
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new SnapshotException("DIFF_TEXT_INTERRUPTED", "Frozen source comparison interrupted", failure);
        } catch (java.io.IOException | ExecutionException failure) {
            throw new SnapshotException("DIFF_TEXT_FAILED", failure.getMessage(), failure);
        }
    }
}
