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
 *      <ul>
 *        <li>Legacy in-project {@code ./.anatomist/index.db} if present
 *            (back-compat with the old write default)</li>
 *        <li>Otherwise {@code $ANATOMIST_HOME/<cwd-basename>/index.db}
 *            (the new write default)</li>
 *      </ul></li>
 *    <li>Otherwise: error suggesting {@code --index} or
 *        {@code anatomist index}</li>
 *  </ol>
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
        Path cwd = Paths.get("").toAbsolutePath();
        Path def = DefaultIndexPath.forQueryRead(cwd);
        if (Files.isRegularFile(def)) return def;
        throw new IllegalArgumentException(
                "no index db found at " + def + " — pass --index <path> "
              + "or run `anatomist index " + cwd + "` first");
    }
}
