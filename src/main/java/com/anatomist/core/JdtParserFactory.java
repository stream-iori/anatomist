package com.anatomist.core;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.FileASTRequestor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        this.classpathEntries = classpathEntries == null ? List.of() : List.copyOf(classpathEntries);
        this.sourcePaths = sourcePaths == null ? List.of() : List.copyOf(sourcePaths);
        this.includeRunningVmClasspath = includeRunningVmClasspath;
    }

    public ASTParser newParser() {
        ASTParser parser = ASTParser.newParser(resolveJlsLevel(javaVersion));
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        parser.setStatementsRecovery(true);
        parser.setEnvironment(
                classpathEntries.toArray(new String[0]),
                sourcePaths.toArray(new String[0]),
                null,
                includeRunningVmClasspath
        );
        Map<String, String> options = JavaCore.getOptions();
        String compliance = resolveCompliance(javaVersion);
        JavaCore.setComplianceOptions(compliance, options);
        parser.setCompilerOptions(options);
        return parser;
    }

    /**
     * Batch parse every source file via {@code ASTParser.createASTs(...)} so JDT
     * can share its Binding context across files.
     */
    public void parseAll(List<Path> sourceFiles, FileASTRequestor requestor) {
        if (sourceFiles == null || sourceFiles.isEmpty()) return;
        ASTParser parser = newParser();
        String[] paths = new String[sourceFiles.size()];
        String[] encodings = new String[sourceFiles.size()];
        for (int i = 0; i < sourceFiles.size(); i++) {
            paths[i] = sourceFiles.get(i).toAbsolutePath().toString();
            encodings[i] = StandardCharsets.UTF_8.name();
        }
        parser.createASTs(paths, encodings, new String[0], requestor, null);
    }

    static int resolveJlsLevel(int javaVersion) {
        return switch (javaVersion) {
            case 11 -> AST.JLS11;
            case 17 -> AST.JLS17;
            case 21 -> AST.JLS21;
            default -> AST.JLS8;
        };
    }

    static String resolveCompliance(int javaVersion) {
        return switch (javaVersion) {
            case 11 -> JavaCore.VERSION_11;
            case 17 -> JavaCore.VERSION_17;
            case 21 -> JavaCore.VERSION_21;
            default -> JavaCore.VERSION_1_8;
        };
    }
}
