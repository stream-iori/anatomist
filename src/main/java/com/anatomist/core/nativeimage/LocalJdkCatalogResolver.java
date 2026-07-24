package com.anatomist.core.nativeimage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Resolves a catalog from an explicitly configured local JDK and caches it
 * below the Anatomist home. It deliberately has no network behavior. */
public final class LocalJdkCatalogResolver {
    public static final String ENV_JDK_HOME = "ANATOMIST_JDK_HOME";

    private LocalJdkCatalogResolver() {}

    public static JdkTypeCatalog resolve(Path jdkHome, int expectedRelease) {
        return resolve(jdkHome, expectedRelease, defaultCatalogDirectory());
    }

    static JdkTypeCatalog resolve(Path jdkHome, int expectedRelease, Path catalogDirectory) {
        Path home = jdkHome.toAbsolutePath().normalize();
        int actualRelease = JdkTypeCatalogBuilder.releaseOf(home);
        if (actualRelease != expectedRelease) {
            throw new IllegalArgumentException("JDK_HOME_RELEASE_MISMATCH: expected Java "
                    + expectedRelease + " but " + home + " is Java " + actualRelease);
        }
        Path cache = catalogDirectory.resolve(cacheName(home, actualRelease));
        JdkTypeCatalog cached = readIfValid(cache, actualRelease);
        if (cached != null) return cached;

        JdkTypeCatalog built = new JdkTypeCatalogBuilder().buildFromJdkHome(home);
        if (built.jdkRelease() != actualRelease) {
            throw new IllegalStateException("built catalog release does not match JDK home: " + home);
        }
        writeAtomically(cache, built);
        return built;
    }

    static Path defaultCatalogDirectory() {
        String configured = System.getenv("ANATOMIST_HOME");
        Path home = configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.home", "."), ".anatomist")
                : Path.of(configured);
        return home.resolve("catalogs");
    }

    static JdkTypeCatalog readIfValid(Path file, int expectedRelease) {
        if (!Files.isRegularFile(file)) return null;
        try (InputStream in = Files.newInputStream(file)) {
            JdkTypeCatalog catalog = JdkTypeCatalog.readFrom(in);
            return catalog.jdkRelease() == expectedRelease ? catalog : null;
        } catch (RuntimeException | IOException ignored) {
            return null;
        }
    }

    private static void writeAtomically(Path target, JdkTypeCatalog catalog) {
        try {
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".part");
            try {
                try (OutputStream out = Files.newOutputStream(temporary)) {
                    catalog.writeTo(out);
                }
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to cache JDK catalog at " + target, e);
        }
    }

    private static String cacheName(Path home, int release) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(home.toRealPath().toString().getBytes(StandardCharsets.UTF_8));
            digest.update(Files.readAllBytes(home.resolve("release")));
            Path archiveRoot = release == 8 && Files.isDirectory(home.resolve("jre/lib"))
                    ? home.resolve("jre/lib")
                    : release == 8 ? home.resolve("lib") : home.resolve("jmods");
            try (var files = Files.walk(archiveRoot, 1)) {
                files.filter(Files::isRegularFile).sorted().forEach(path -> {
                    try {
                        digest.update(path.getFileName().toString().getBytes(StandardCharsets.UTF_8));
                        digest.update(Long.toString(Files.size(path)).getBytes(StandardCharsets.UTF_8));
                        digest.update(Long.toString(Files.getLastModifiedTime(path).toMillis()).getBytes(StandardCharsets.UTF_8));
                    } catch (IOException e) {
                        throw new CatalogFingerprintException(e);
                    }
                });
            }
            return "jdk" + release + "-" + HexFormat.of().formatHex(digest.digest()).substring(0, 16) + ".bin";
        } catch (CatalogFingerprintException | IOException e) {
            throw new IllegalArgumentException("failed to fingerprint JDK home " + home, e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static final class CatalogFingerprintException extends RuntimeException {
        CatalogFingerprintException(IOException cause) { super(cause); }
    }
}
