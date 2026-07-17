package com.anatomist.core;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.resolution.UnsolvedSymbolException;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Always-on bounded aggregation of failed SymbolSolver operations. */
final class ResolutionTracker {

    private static final int MAX_GROUPS = 50_000;

    private final Path projectRoot;
    private final SourceIdentityResolver identities;
    private final AtomicLong unresolved = new AtomicLong();
    private final Map<Key, Bucket> groups = new LinkedHashMap<>();

    private String sourceFile;
    private String module = ".";
    private String scope = "MAIN";
    private String phase = "UNKNOWN";
    private String packageName = "";
    private final Map<String, String> explicitImports = new LinkedHashMap<>();

    ResolutionTracker(Path projectRoot, java.util.List<Path> sourcePaths) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.identities = new SourceIdentityResolver(projectRoot, sourcePaths);
    }

    void enterFile(CompilationUnit unit) {
        Path file = unit.getStorage().map(storage -> storage.getPath().toAbsolutePath().normalize())
                .orElse(null);
        if (file != null) {
            try {
                sourceFile = projectRoot.relativize(file).toString();
            } catch (IllegalArgumentException e) {
                sourceFile = file.toString();
            }
            SourceIdentity identity = identities.resolve(sourceFile);
            module = identity.module();
            scope = identity.scope().name();
        } else {
            sourceFile = null;
            module = ".";
            scope = "MAIN";
        }
        packageName = unit.getPackageDeclaration()
                .map(declaration -> declaration.getNameAsString()).orElse("");
        explicitImports.clear();
        unit.getImports().stream()
                .filter(declaration -> !declaration.isAsterisk())
                .forEach(declaration -> {
                    String imported = declaration.getNameAsString();
                    int separator = imported.lastIndexOf('.');
                    explicitImports.put(separator < 0 ? imported : imported.substring(separator + 1),
                            imported);
                });
    }

    void enterPhase(String phase) {
        this.phase = phase == null || phase.isBlank() ? "UNKNOWN" : phase;
    }

    void record(Throwable cause) {
        unresolved.incrementAndGet();
        String sample = ExtractionContext.sampleKey(cause);
        String reason = classify(cause, sample);
        Key key = new Key(sourceFile, module, scope, phase, reason);
        Bucket bucket = groups.get(key);
        if (bucket == null) {
            if (groups.size() >= MAX_GROUPS) {
                key = new Key(null, ".", "MAIN", "RESOLUTION", "DIAGNOSTIC_LIMIT_REACHED");
                bucket = groups.computeIfAbsent(key, ignored -> new Bucket());
            } else {
                bucket = new Bucket();
                groups.put(key, bucket);
            }
        }
        bucket.count++;
        if (bucket.sample == null) bucket.sample = sample;
    }

    long unresolvedCount() {
        return unresolved.get();
    }

    ResolutionSummary snapshot(boolean noClasspath) {
        java.util.List<IndexDiagnostic> diagnostics = new java.util.ArrayList<>();
        groups.forEach((key, bucket) -> diagnostics.add(new IndexDiagnostic(
                severity(key.reason(), noClasspath),
                key.reason(),
                key.phase(),
                key.sourceFile(),
                key.module(),
                key.scope(),
                null,
                bucket.count,
                bucket.sample)));
        long failed = unresolved.get();
        return new ResolutionSummary(failed, 0, failed, diagnostics);
    }

    private String classify(Throwable cause, String sample) {
        String message = ((cause == null ? "" : cause.getClass().getSimpleName() + " "
                + cause.getMessage()) + " " + sample).toLowerCase(Locale.ROOT);
        if (cause instanceof UnsupportedOperationException || message.contains("unsupported")) {
            return "UNSUPPORTED_RESOLUTION";
        }
        if (message.contains("ambiguous") || message.contains("multiple applicable")) {
            return "AMBIGUOUS_OVERLOAD";
        }
        if (message.contains("generic") || message.contains("inference")
                || message.contains("type variable")) {
            return "GENERIC_INFERENCE_FAILED";
        }
        String imported = importedType(sample);
        if (isJdkType(sample) || isJdkType(imported)) {
            return "JDK_SYMBOL_MISMATCH";
        }
        String normalizedPhase = phase.toUpperCase(Locale.ROOT);
        if (normalizedPhase.contains("CALL")) return "METHOD_NOT_FOUND";
        if (normalizedPhase.contains("FIELD_ACCESS")) return "FIELD_NOT_FOUND";
        if (cause instanceof UnsolvedSymbolException) {
            if (imported != null && !sameProjectNamespace(imported)) {
                return "THIRDPARTY_SYMBOL_MISSING";
            }
            return isLikelyInternal(sample)
                    ? "INTERNAL_SYMBOL_MISSING"
                    : "THIRDPARTY_SYMBOL_MISSING";
        }
        return "OTHER_INFERENCE";
    }

    private boolean isLikelyInternal(String symbol) {
        if (symbol == null || symbol.isBlank() || symbol.startsWith("[")) return false;
        String imported = importedType(symbol);
        if (imported != null) return sameProjectNamespace(imported);
        if (!packageName.isBlank() && symbol.startsWith(packageName + ".")) return true;
        return symbol.indexOf('.') < 0 && Character.isUpperCase(symbol.charAt(0));
    }

    private String importedType(String symbol) {
        if (symbol == null || symbol.isBlank()) return null;
        String simple = symbol;
        int separator = simple.lastIndexOf('.');
        if (separator >= 0) simple = simple.substring(separator + 1);
        int generic = simple.indexOf('<');
        if (generic >= 0) simple = simple.substring(0, generic);
        return explicitImports.get(simple);
    }

    private boolean sameProjectNamespace(String qualifiedName) {
        if (qualifiedName == null || packageName.isBlank()) return false;
        return namespacePrefix(qualifiedName).equals(namespacePrefix(packageName));
    }

    private static String namespacePrefix(String qualifiedName) {
        String[] parts = qualifiedName.split("\\.");
        int length = Math.min(2, parts.length);
        return String.join(".", java.util.Arrays.copyOf(parts, length));
    }

    private static boolean isJdkType(String qualifiedName) {
        return qualifiedName != null && (qualifiedName.startsWith("java.")
                || qualifiedName.startsWith("javax.") || qualifiedName.startsWith("jdk."));
    }

    private static String severity(String reason, boolean noClasspath) {
        return switch (reason) {
            case "INTERNAL_SYMBOL_MISSING", "JDK_SYMBOL_MISMATCH" -> "warning";
            case "THIRDPARTY_SYMBOL_MISSING" -> noClasspath ? "info" : "warning";
            default -> "info";
        };
    }

    private record Key(String sourceFile, String module, String scope,
                       String phase, String reason) {}

    private static final class Bucket {
        long count;
        String sample;
    }
}
