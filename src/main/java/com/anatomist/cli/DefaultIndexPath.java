package com.anatomist.cli;

import com.anatomist.store.FileCacheService;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Centralised storage-path policy. Every command derives its default
 *  index-db location through here so the convention stays in one place.
 *
 *  <h3>Policy</h3>
 *  <ol>
 *    <li>Explicit {@code --output} / {@code --index} wins — handled by
 *        the callers, never reaches this class.</li>
 *    <li>The default is
 *        {@code $ANATOMIST_HOME/indexes/<project-key>/index.db}, where
 *        {@code ANATOMIST_HOME} defaults to {@code ~/.anatomist}.</li>
 *  </ol>
 *
 *  <h3>Why per-project storage under HOME</h3>
 *  Keeping the DB out of the project tree avoids polluting working
 *  copies (git status, CI artifacts, container mounts) and lets a single
 *  developer index dozens of repos without clutter. The project key combines
 *  a readable basename with a hash of the canonical local checkout path, so
 *  same-named repos and worktrees cannot silently share one database. */
public final class DefaultIndexPath {

    /** Index-database filename. */
    static final String INDEX_DB = "index.db";
    /** Per-checkout index directories live below this storage namespace. */
    static final String INDEXES_DIR = "indexes";
    /** Default name of the storage root under the user's HOME. */
    static final String HOME_DIR = ".anatomist";
    /** Environment variable that overrides the storage root entirely. */
    public static final String ENV_HOME = "ANATOMIST_HOME";

    private DefaultIndexPath() {}

    // ── public entry points used by main + tests ──────────────────────────

    /** Resolve where {@code anatomist index} should write when no
     *  {@code --output} was supplied. */
    public static Path forIndexWrite(Path projectRoot) {
        return forIndexWrite(projectRoot, defaultHome());
    }

    /** Resolve where a query subcommand should read when no
     *  {@code --index} was supplied. May point at a non-existent file —
     *  the caller produces the user-facing "not found" message. */
    public static Path forQueryRead(Path projectRoot) {
        return forQueryRead(projectRoot, defaultHome());
    }

    // ── overloads that take an explicit anatomistHome — for tests ────────

    static Path forIndexWrite(Path projectRoot, Path anatomistHome) {
        return homedDb(projectRoot, anatomistHome);
    }

    static Path forQueryRead(Path projectRoot, Path anatomistHome) {
        // Return the canonical $HOME location whether it exists or not —
        // the caller checks isRegularFile and emits a clean error.
        return homedDb(projectRoot, anatomistHome);
    }

    /** Resolve {@code ~/.anatomist} (or {@code $ANATOMIST_HOME} override).
     *  Both arguments may be {@code null} to read from system env. */
    public static Path resolveHome(String envOverride, String userHome) {
        if (envOverride != null && !envOverride.isEmpty()) {
            return Paths.get(envOverride);
        }
        String home = (userHome == null || userHome.isEmpty())
                ? System.getProperty("user.home", ".")
                : userHome;
        return Paths.get(home, HOME_DIR);
    }

    // ── internals ────────────────────────────────────────────────────────

    private static Path defaultHome() {
        return resolveHome(System.getenv(ENV_HOME), System.getProperty("user.home"));
    }

    private static Path homedDb(Path projectRoot, Path anatomistHome) {
        return storageDir(projectRoot, anatomistHome).resolve(INDEX_DB);
    }

    static Path storageDir(Path projectRoot, Path anatomistHome) {
        return anatomistHome.resolve(INDEXES_DIR).resolve(repoKeyOf(projectRoot));
    }

    /** Stable only on the current machine: locator identity never enters a portable baseline. */
    static String repoKeyOf(Path projectRoot) {
        Path canonical = canonicalRoot(projectRoot);
        String digest = FileCacheService.sha256OfString(canonical.toString()).substring(0, 12);
        return sanitizedRepoNameOf(canonical) + "-" + digest;
    }

    /** Derive a stable per-project directory name. Basename of the
     *  normalised project root; for "/" or empty we fall back to
     *  {@code default-project}. */
    static String repoNameOf(Path projectRoot) {
        Path normalized = projectRoot.toAbsolutePath().normalize();
        Path name = normalized.getFileName();
        if (name == null) return "default-project";
        String s = name.toString();
        return s.isEmpty() ? "default-project" : s;
    }

    private static String sanitizedRepoNameOf(Path projectRoot) {
        String raw = repoNameOf(projectRoot);
        StringBuilder sanitized = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char character = raw.charAt(i);
            boolean allowed = character >= 'A' && character <= 'Z'
                    || character >= 'a' && character <= 'z'
                    || character >= '0' && character <= '9'
                    || character == '.' || character == '_' || character == '-';
            sanitized.append(allowed ? character : '_');
        }
        return sanitized.isEmpty() ? "default-project" : sanitized.toString();
    }

    private static Path canonicalRoot(Path projectRoot) {
        try {
            return projectRoot.toRealPath().normalize();
        } catch (IOException ex) {
            return projectRoot.toAbsolutePath().normalize();
        }
    }
}
