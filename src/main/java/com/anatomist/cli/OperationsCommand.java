package com.anatomist.cli;

import com.anatomist.json.Json;
import com.anatomist.query.semantic.SemanticProviders;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "operations", mixinStandardHelpOptions = true,
        description = "Describe semantic operations, constraints, and provider support for Agents.",
        footer = "%nJSON contract: anatomist-operation-catalog/v1%n"
                + "Without --index, availability is unchecked and no database is opened.%n"
                + "This catalog exposes atomic operations; it intentionally contains no recipes.")
public final class OperationsCommand implements Callable<Integer> {
    static final String CONTRACT = "anatomist-operation-catalog/v1";

    @Parameters(index = "0", arity = "0..1", paramLabel = "OPERATION",
            description = "Optional exact operation ID, for example calls.")
    String operation;

    @Option(names = "--language", description = "Filter support for one language ID.")
    String language;

    @Option(names = "--provider", description = "Filter support for one provider ID.")
    String provider;

    @Option(names = "--index",
            description = "Explicit index used only to inspect operation availability.")
    Path index;

    @Option(names = "--format", defaultValue = "json",
            description = "Output: json | table (default json).")
    String format;

    @Override public Integer call() {
        try {
            format = CliValidation.choice("--format", format, "json", "table");
            SemanticOperationRegistry.Entry selected = operation == null ? null
                    : SemanticOperationRegistry.entry(operation);
            if (operation != null && selected == null) {
                throw new IllegalArgumentException("unknown semantic operation: " + operation);
            }
            String selectedLanguage = language == null || language.isBlank()
                    ? "java" : language.trim().toLowerCase(java.util.Locale.ROOT);
            String selectedProvider = provider == null || provider.isBlank()
                    ? SemanticProviders.providerForLanguage(selectedLanguage) : provider.trim();
            if (index == null) {
                return emit(catalog(selected, selectedLanguage, selectedProvider, null, null));
            }
            Path db = index.toAbsolutePath().normalize();
            if (!Files.isRegularFile(db)) {
                Map<String, Object> error = CliError.base("INDEX_MISSING", "index", 3,
                        "index db not found: " + db, "operations");
                error.put("details", Map.of("index_path", db.toString()));
                CliError.emit(error);
                return 3;
            }
            try (SemanticExecutionContext context = SemanticExecutionContext.open(db, null, "ALL")) {
                return emit(catalog(selected, selectedLanguage, selectedProvider, context, db));
            }
        } catch (IllegalArgumentException failure) {
            CliError.emit(CliError.of("operations", failure, 2));
            return 2;
        } catch (RuntimeException failure) {
            int exit = failure instanceof IllegalStateException ? 3 : 1;
            CliError.emit(CliError.of("operations", failure, exit));
            return exit;
        }
    }

    private Map<String, Object> catalog(SemanticOperationRegistry.Entry selected,
                                         String selectedLanguage,
                                         String selectedProvider,
                                         SemanticExecutionContext context, Path db) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("contract", CONTRACT);
        root.put("query_contract", "semantic-stream/v1");
        root.put("version", BuildVersion.display());
        if (db != null) {
            root.put("index_path", db.toString());
            String sourceRoot = projectMeta(context, "source_root");
            root.put("index_identity", com.anatomist.query.semantic.IndexIdentity.map(
                    db, sourceRoot, context.identity()));
        }
        if (language == null || language.isBlank()) {
            root.put("languages", SemanticProviders.installedLanguages());
        } else {
            root.put("languages", List.of(Map.of("id", selectedLanguage,
                    "installed", SemanticProviders.installed(selectedLanguage))));
        }
        root.put("pipeline_limits", Map.of("stages", PipelineCommand.MAX_STAGES,
                "argv_per_stage", PipelineCommand.MAX_ARGS_PER_STAGE,
                "spec_bytes", PipelineCommand.MAX_SPEC_BYTES));
        root.put("global_options", List.of("--index", "--module", "--scope", "--language",
                "--provider", "--format"));

        List<Map<String, Object>> operations = new ArrayList<>();
        for (SemanticOperationRegistry.Entry entry : SemanticOperationRegistry.entriesView()) {
            if (selected != null && !selected.id().equals(entry.id())) continue;
            Boolean available = context == null ? null
                    : context.capabilities().supports(entry.id(), selectedLanguage,
                            selectedProvider);
            operations.add(SemanticOperationRegistry.catalogEntry(
                    entry, selectedLanguage, selectedProvider, available));
        }
        root.put("operations", List.copyOf(operations));
        return root;
    }

    private static String projectMeta(SemanticExecutionContext context, String key) {
        try (java.sql.PreparedStatement statement = context.query().connection().prepareStatement(
                "SELECT value FROM project_meta WHERE key=?")) {
            statement.setString(1, key);
            try (java.sql.ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : "";
            }
        } catch (java.sql.SQLException failure) {
            throw new RuntimeException("failed to read index identity", failure);
        }
    }

    private int emit(Map<String, Object> catalog) {
        if ("json".equals(format)) {
            System.out.println(Json.writePretty(catalog));
            return 0;
        }
        System.out.printf("%-28s %-10s %-14s %-12s%n",
                "OPERATION", "ROLE", "SUPPORT", "AVAILABILITY");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> operations =
                (List<Map<String, Object>>) catalog.get("operations");
        for (Map<String, Object> item : operations) {
            @SuppressWarnings("unchecked")
            Map<String, Object> support = ((List<Map<String, Object>>) item.get("support")).getFirst();
            System.out.printf("%-28s %-10s %-14s %-12s%n", item.get("id"), item.get("role"),
                    support.get("support"), support.get("availability"));
        }
        return 0;
    }
}
