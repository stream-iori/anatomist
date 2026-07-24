package com.anatomist.core.nativeimage;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * CLI entry point that generates a {@code jdkN-types.bin} catalog from an
 * installed JDK home. Intended to be run once per target JDK version; the output
 * is committed under {@code src/main/resources/META-INF/anatomist/}.
 *
 * <p>Usage: {@code java -cp ... JdkTypeCatalogGenerator <jdk-home> <output-path>}
 */
public final class JdkTypeCatalogGenerator {

    private JdkTypeCatalogGenerator() {}

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("Usage: JdkTypeCatalogGenerator <jdk-home> <output-path>");
            System.err.println("Example: JdkTypeCatalogGenerator /opt/jdk-8 src/main/resources/META-INF/anatomist/jdk8-types.bin");
            System.exit(1);
        }
        Path jdkHome = Path.of(args[0]);
        Path output = Path.of(args[1]);

        System.out.println("Building JDK type catalog from " + jdkHome.toAbsolutePath() + "...");
        JdkTypeCatalog catalog = new JdkTypeCatalogBuilder().buildFromJdkHome(jdkHome);
        System.out.println("  JDK release: " + catalog.jdkRelease());
        System.out.println("  Types collected: " + catalog.size());

        Files.createDirectories(output.getParent());
        try (OutputStream out = Files.newOutputStream(output)) {
            catalog.writeTo(out);
        }
        System.out.println("  Written to: " + output.toAbsolutePath());
        System.out.println("Done.");
    }
}
