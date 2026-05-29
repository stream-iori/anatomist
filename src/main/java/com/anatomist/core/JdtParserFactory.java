package com.anatomist.core;

import org.eclipse.jdt.core.dom.ASTParser;

import java.util.List;

public class JdtParserFactory {

    private final int javaVersion;
    private final List<String> classpathEntries;
    private final List<String> sourcePaths;
    private final boolean includeRunningVmClasspath;

    public JdtParserFactory(int javaVersion,
                            List<String> classpathEntries,
                            List<String> sourcePaths,
                            boolean includeRunningVmClasspath) {
        this.javaVersion = javaVersion;
        this.classpathEntries = classpathEntries;
        this.sourcePaths = sourcePaths;
        this.includeRunningVmClasspath = includeRunningVmClasspath;
    }

    public ASTParser newParser() {
        throw new UnsupportedOperationException("not implemented");
    }
}
