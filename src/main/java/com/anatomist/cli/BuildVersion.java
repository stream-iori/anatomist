package com.anatomist.cli;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

final class BuildVersion {
    private static final String FALLBACK_VERSION = "unknown";
    private static final String RESOURCE = "/anatomist-version.properties";

    private BuildVersion() {
    }

    static String version() {
        Properties props = new Properties();
        try (InputStream in = BuildVersion.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return FALLBACK_VERSION;
            }
            props.load(in);
            return props.getProperty("version", FALLBACK_VERSION).trim();
        } catch (IOException e) {
            return FALLBACK_VERSION;
        }
    }

    static String display() {
        return "anatomist " + version();
    }
}
