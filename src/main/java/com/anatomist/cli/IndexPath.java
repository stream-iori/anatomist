package com.anatomist.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Discover the SQLite index for a query command.
 *
 *  <p>Resolution order:</p>
 *  <ol>
 *    <li>Explicit {@code --index} flag (the caller's path)</li>
 *    <li>Per {@link DefaultIndexPath#forQueryRead}, derived from the
 *        current working directory:
 *      {@code $ANATOMIST_HOME/indexes/<project-key>/index.db}.</li>
 *    <li>Otherwise: error suggesting {@code --index} or
 *        {@code anatomist index}</li>
 *  </ol>
 */
final class IndexPath {

    private IndexPath() {}

    static Path resolve(Path explicit) {
        return resolve(explicit, Paths.get("").toAbsolutePath());
    }

    static Path resolve(Path explicit, Path projectRoot) {
        if (explicit != null) {
            if (!Files.isRegularFile(explicit)) {
                throw new IllegalArgumentException("index db not found: " + explicit);
            }
            return explicit;
        }
        Path root = projectRoot.toAbsolutePath().normalize();
        Path def = DefaultIndexPath.forQueryRead(root);
        if (Files.isRegularFile(def)) return def;
        throw new IllegalArgumentException(
                "no index db found at " + def + " — pass --index <path> "
              + "or run `anatomist index " + root + "` first");
    }
}
