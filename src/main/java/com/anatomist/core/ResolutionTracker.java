package com.anatomist.core;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.resolution.UnsolvedSymbolException;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Always-on bounded aggregation of failed SymbolSolver operations. */
final class ResolutionTracker {

    private static final int MAX_GROUPS = 50_000;

    private final Path projectRoot;
    private final SourceIdentityResolver identities;
    private final List<Path> sourcePaths;
    private final Map<String, Boolean> sourceTypes = new LinkedHashMap<>();
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
        this.sourcePaths = sourcePaths == null ? List.of() : sourcePaths.stream()
                .map(path -> path.toAbsolutePath().normalize()).toList();
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
        record(cause, null, null);
    }

    void record(Throwable cause, Node site, String attemptedSymbol) {
        unresolved.incrementAndGet();
        String causeSymbol = ExtractionContext.sampleKey(cause);
        String symbol = normalizeSymbol(attemptedSymbol, causeSymbol);
        int line = site == null ? 0 : site.getBegin().map(position -> position.line).orElse(0);
        String expression = site == null ? null : normalizeText(site.toString(), 220);
        String sample = sample(cause, line, expression);
        String reason = classify(cause, symbol, expression, causeSymbol);
        Key key = new Key(sourceFile, module, scope, phase, reason, symbol, line);
        Bucket bucket = groups.get(key);
        if (bucket == null) {
            if (groups.size() >= MAX_GROUPS) {
                key = new Key(null, ".", "MAIN", "RESOLUTION",
                        "DIAGNOSTIC_LIMIT_REACHED", null, 0);
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
                key.symbol(),
                bucket.count,
                bucket.sample)));
        long failed = unresolved.get();
        return new ResolutionSummary(failed, 0, failed, diagnostics);
    }

    private String classify(Throwable cause, String symbol, String expression, String causeSymbol) {
        String context = String.join(" ", value(symbol), value(expression), value(causeSymbol));
        String message = ((cause == null ? "" : cause.getClass().getSimpleName() + " "
                + cause.getMessage()) + " " + context).toLowerCase(Locale.ROOT);
        if (cause instanceof UnsupportedOperationException || message.contains("unsupported")) {
            return "UNSUPPORTED_RESOLUTION";
        }
        if (message.contains("ambiguous") || message.contains("multiple applicable")) {
            return "AMBIGUOUS_OVERLOAD";
        }
        if (message.contains("generic") || message.contains("inference")
                || message.contains("type variable") || message.contains("typeparametersmap")
                || message.contains("type parameter")
                || message.contains("asmtypesolver")
                || message.contains("solving ")
                || expression != null && (expression.contains("->") || expression.contains("::"))
                || expression != null && (expression.contains("<>") || expression.contains(".<"))
                || expression != null && message.contains("constructor declaration corresponding")
                        && expression.contains(".of(")
                || causeSymbol != null && (causeSymbol.contains("->") || causeSymbol.contains("::"))
                || causeSymbol != null && causeSymbol.length() == 1
                        && Character.isLowerCase(causeSymbol.charAt(0))) {
            return "GENERIC_INFERENCE_FAILED";
        }
        String normalizedPhase = phase.toUpperCase(Locale.ROOT);
        // FieldAccessExtractor deliberately probes every NameExpr/FieldAccessExpr.
        // Type and package qualifiers fail value resolution by design, so this
        // phase must not promote those probes into internal/JDK coverage warnings.
        if (normalizedPhase.contains("FIELD_ACCESS")) return "FIELD_NOT_FOUND";
        if (normalizedPhase.contains("CALL") && isUnqualifiedCall(expression)) {
            return "METHOD_NOT_FOUND";
        }
        // A solved receiver whose member lookup fails is a member-resolution
        // limitation, not proof that the receiver's internal/JDK type is absent.
        if (message.contains("cannot be resolved in context")
                || message.contains("unable to find the method declaration corresponding")) {
            return "METHOD_NOT_FOUND";
        }
        String imported = importedType(causeSymbol);
        if (imported == null) imported = importedType(symbol);
        if (imported == null) imported = importedType(expression);
        if (isJdkType(symbol) || isJdkType(expression) || isJdkType(causeSymbol)
                || isJdkType(imported)) {
            return "JDK_SYMBOL_MISMATCH";
        }
        if (cause instanceof UnsolvedSymbolException && isLikelyInternal(symbol)) {
            return "INTERNAL_SYMBOL_MISSING";
        }
        if (cause instanceof UnsolvedSymbolException && looksLikeTypeSymbol(symbol)) {
            return "THIRDPARTY_SYMBOL_MISSING";
        }
        if (normalizedPhase.contains("CALL")) return "METHOD_NOT_FOUND";
        if (cause instanceof UnsolvedSymbolException) {
            return isLikelyInternal(symbol)
                    ? "INTERNAL_SYMBOL_MISSING"
                    : "THIRDPARTY_SYMBOL_MISSING";
        }
        return "OTHER_INFERENCE";
    }

    private boolean isLikelyInternal(String symbol) {
        if (symbol == null || symbol.isBlank() || symbol.startsWith("[")) return false;
        String imported = importedType(symbol);
        if (imported != null) return isSourceType(imported);
        if (!packageName.isBlank() && symbol.indexOf('.') < 0
                && Character.isUpperCase(symbol.charAt(0))) {
            return isSourceType(packageName + "." + symbol);
        }
        return isSourceType(symbol);
    }

    /** A shared package prefix is not evidence that a type belongs to this checkout. */
    private boolean isSourceType(String fqn) {
        if (fqn == null || fqn.isBlank()) return false;
        return sourceTypes.computeIfAbsent(fqn, candidate -> {
            String relative = candidate.replace('.', '/') + ".java";
            return sourcePaths.stream().anyMatch(root -> Files.isRegularFile(root.resolve(relative)));
        });
    }

    private String importedType(String symbol) {
        if (symbol == null || symbol.isBlank()) return null;
        String simple = symbol.strip();
        int call = simple.indexOf('(');
        if (call >= 0) simple = simple.substring(0, call);
        int space = simple.indexOf(' ');
        if (space >= 0) simple = simple.substring(0, space);
        int leadingSeparator = simple.indexOf('.');
        if (leadingSeparator > 0) {
            String leading = simple.substring(0, leadingSeparator);
            String imported = explicitImports.get(leading);
            if (imported != null) return imported;
        }
        int separator = simple.lastIndexOf('.');
        if (separator >= 0) simple = simple.substring(separator + 1);
        int generic = simple.indexOf('<');
        if (generic >= 0) simple = simple.substring(0, generic);
        return explicitImports.get(simple);
    }

    private static boolean isJdkType(String qualifiedName) {
        return qualifiedName != null && (qualifiedName.startsWith("java.")
                || qualifiedName.startsWith("javax.") || qualifiedName.startsWith("jdk."));
    }

    private static String severity(String reason, boolean noClasspath) {
        return switch (reason) {
            case "INTERNAL_SYMBOL_MISSING", "JDK_SYMBOL_MISMATCH" -> "warning";
            default -> "info";
        };
    }

    private record Key(String sourceFile, String module, String scope,
                       String phase, String reason, String symbol, int line) {}

    private static final class Bucket {
        long count;
        String sample;
    }

    private static String normalizeSymbol(String attempted, String fallback) {
        String value = attempted == null || attempted.isBlank() ? fallback : attempted;
        if (value == null || value.isBlank() || value.startsWith("[")) return null;
        return normalizeText(value, 200);
    }

    private static String sample(Throwable cause, int line, String expression) {
        StringBuilder out = new StringBuilder();
        if (line > 0) out.append('L').append(line).append(": ");
        if (expression != null && !expression.isBlank()) out.append(expression).append(" — ");
        if (cause == null) {
            out.append("resolution returned no binding");
        } else {
            out.append(cause.getClass().getSimpleName());
            if (cause.getMessage() != null && !cause.getMessage().isBlank()) {
                out.append(": ").append(cause.getMessage().strip());
            }
        }
        return normalizeText(out.toString(), 500);
    }

    private static String normalizeText(String value, int limit) {
        if (value == null) return null;
        String normalized = value.replaceAll("\\s+", " ").strip();
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit);
    }

    private static boolean looksLikeTypeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) return false;
        String simple = symbol;
        int separator = Math.max(simple.lastIndexOf('.'), simple.lastIndexOf('$'));
        if (separator >= 0 && separator + 1 < simple.length()) simple = simple.substring(separator + 1);
        int generic = simple.indexOf('<');
        if (generic >= 0) simple = simple.substring(0, generic);
        return !simple.isBlank() && Character.isUpperCase(simple.charAt(0))
                && simple.indexOf('(') < 0;
    }

    private static boolean isUnqualifiedCall(String expression) {
        if (expression == null || expression.isBlank() || expression.startsWith("new ")) return false;
        int call = expression.indexOf('(');
        if (call <= 0) return false;
        return expression.substring(0, call).indexOf('.') < 0;
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
