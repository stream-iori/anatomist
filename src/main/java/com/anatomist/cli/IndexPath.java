package com.anatomist.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Discover the SQLite index for a query command.
 *
 *  <p>Resolution order: explicit {@code --index} flag → current dir's
 *  {@code .anatomist/index.db}.</p>
 */
final class IndexPath {
    private IndexPath() {}

    static Path resolve(Path explicit) {
        if (explicit != null) {
            if (!Files.isRegularFile(explicit)) {
                throw new IllegalArgumentException("index db not found: " + explicit);
            }
            return explicit;
        }
        Path def = Paths.get(".anatomist", "index.db");
        if (Files.isRegularFile(def)) return def;
        throw new IllegalArgumentException(
                "no index db found at " + def + " — pass --index <path> or run "
              + "`anatomist index` first");
    }
}
