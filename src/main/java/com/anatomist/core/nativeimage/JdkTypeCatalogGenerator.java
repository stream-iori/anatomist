package com.anatomist.core.nativeimage;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * CLI entry point that generates a {@code jdkN-types.bin} catalog from the
 * running JDK. Intended to be run once per target JDK version; the output
 * is committed under {@code src/main/resources/META-INF/anatomist/}.
 *
 * <p>Usage: {@code java -cp ... JdkTypeCatalogGenerator <version> <output-path>}
 */
public final class JdkTypeCatalogGenerator {

    private JdkTypeCatalogGenerator() {}

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("Usage: JdkTypeCatalogGenerator <jdk-version-label> <output-path>");
            System.err.println("Example: JdkTypeCatalogGenerator 8 src/main/resources/META-INF/anatomist/jdk8-types.bin");
            System.exit(1);
        }
        String versionLabel = args[0];
        Path output = Path.of(args[1]);

        System.out.println("Building JDK type catalog (label=" + versionLabel + ") from current JDK...");
        JdkTypeCatalog catalog = new JdkTypeCatalogBuilder().buildFromCurrentJdk();
        System.out.println("  Types collected: " + catalog.size());

        Files.createDirectories(output.getParent());
        try (OutputStream out = Files.newOutputStream(output)) {
            catalog.writeTo(out);
        }
        System.out.println("  Written to: " + output.toAbsolutePath());
        System.out.println("Done.");
    }
}
