package com.anatomist.cli;

import com.anatomist.json.Json;
import com.anatomist.test.CliTestSupport;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class AgentHelpTest {
    @Test
    void rootGroupsCoverEveryCommandExactlyOnce() {
        var actual = new CommandLine(new AnatomistCli()).getSubcommands().keySet();
        var grouped = AgentHelp.COMMAND_GROUPS.values().stream().flatMap(List::stream).toList();
        assertEquals(grouped.size(), Set.copyOf(grouped).size());
        assertEquals(actual, Set.copyOf(grouped));
    }

    @Test
    void everyPublicArgumentHasMeaningAndEveryHelpEntryPointAgrees() throws Exception {
        var root = new CommandLine(new AnatomistCli());
        for (var item : root.getSubcommands().entrySet()) {
            if (item.getKey().equals("help")) continue;
            for (var arg : item.getValue().getCommandSpec().args()) {
                if (!arg.hidden()) assertFalse(String.join(" ", arg.description()).isBlank(),
                        item.getKey() + ": " + arg);
            }
            String[] args = {item.getKey(), "--help"};
            var direct = CliTestSupport.capture(() -> AnatomistCli.commandLine(args).execute(args));
            var full = CliTestSupport.capture(() -> new CommandLine(new AnatomistCli()).execute(args));
            var help = CliTestSupport.capture(() -> new CommandLine(new AnatomistCli()).execute("help", item.getKey()));
            assertEquals(0, direct.exitCode(), direct.stderr());
            assertEquals(full.stdout(), direct.stdout(), item.getKey());
            assertEquals(full.stdout(), help.stdout(), item.getKey());
        }
    }

    @Test
    void helpAndCatalogExposeActualRecordContracts() throws Exception {
        for (var entry : SemanticOperationRegistry.entriesView()) {
            var command = entry.factory().get();
            var output = CliTestSupport.capture(() -> new CommandLine(command).execute("--help"));
            String help = output.stdout();
            assertEquals(0, output.exitCode(), output.stderr());
            var catalog = SemanticOperationRegistry.catalogEntry(entry, "java", "java-core", null);
            var input = (Map<?, ?>) catalog.get("input");
            assertEquals(command.acceptedInputRecords(), Set.copyOf((List<?>) input.get("records")));
            assertEquals(command.acceptedInputRecords().stream().sorted().toList(), input.get("records"));
            String accepts = help.lines().filter(line -> line.startsWith("Accepts:")).findFirst().orElseThrow();
            for (String record : command.acceptedInputRecords()) assertTrue(accepts.contains(record), entry.id());
            var variants = (List<?>) ((Map<?, ?>) catalog.get("output")).get("variants");
            for (Object raw : variants) {
                var variant = (Map<?, ?>) raw;
                var recordNames = (List<?>) variant.get("records");
                assertEquals(recordNames.stream().map(Object::toString).sorted().toList(), recordNames);
                var emits = help.lines().filter(line -> line.startsWith("Emits:")).collect(Collectors.joining(" "));
                for (Object record : (List<?>) variant.get("records")) assertTrue(emits.contains(record.toString()), entry.id());
            }
            for (Object raw : (List<?>) catalog.get("arguments")) {
                assertNotNull(((Map<?, ?>) raw).get("description"), entry.id() + ": " + raw);
            }
        }
    }

    @Test
    void executableHelpExamplesParseAndCompose() throws Exception {
        for (var entry : SemanticOperationRegistry.entriesView()) {
            var command = new CommandLine(entry.factory().get());
            String footer = String.join("\n", command.getCommandSpec().usageMessage().footer()).replace("%n", "\n");
            var examples = footer.lines().map(String::trim).filter(line -> line.startsWith("anatomist ")).toList();
            assertEquals(1, examples.size(), entry.id());
            var args = words(examples.getFirst());
            args.removeFirst();
            if (args.getFirst().equals("pipeline")) {
                args.add(1, "--explain");
                String[] argv = args.toArray(String[]::new);
                var plan = CliTestSupport.capture(() -> AnatomistCli.commandLine(argv).execute(argv));
                assertEquals(0, plan.exitCode(), entry.id() + ": " + plan.stderr());
            } else {
                assertEquals(entry.id(), args.removeFirst());
                command.parseArgs(args.toArray(String[]::new));
            }
        }
    }

    @Test
    void skillBashExamplesCompose() throws Exception {
        for (String scene : SkillCommand.SCENES) {
            var guide = CliTestSupport.capture(() -> new CommandLine(new SkillCommand()).execute(scene));
            var blocks = Pattern.compile("(?s)```bash\\n(.*?)```").matcher(guide.stdout());
            while (blocks.find()) {
                for (String line : blocks.group(1).lines().filter(l -> !l.isBlank()).toList()) {
                    var args = words(line);
                    assertEquals("anatomist", args.removeFirst());
                    assertEquals("pipeline", args.getFirst());
                    args.add(1, "--explain");
                    String[] argv = args.toArray(String[]::new);
                    var result = CliTestSupport.capture(() -> AnatomistCli.commandLine(argv).execute(argv));
                    assertEquals(0, result.exitCode(), scene + ": " + result.stderr());
                }
            }
        }
    }

    @Test
    void compositionUsesVariantsAndDeclarationSupport() throws Exception {
        check(0, "resolve", "p.Service", "--then", "describe", "--then", "source");
        check(5, "resolve", "p.Service#run()", "--then", "trace", "--to", "p.Helper#work()", "--then", "source");
        check(5, "search", "Service", "--count", "--then", "resolve");
        check(0, "resolve", "p.Service#run()", "--then", "regions", "--then", "sites-in", "--record", "call_site", "--then", "dispatch");
        check(5, "resolve", "p.Service#run()", "--then", "regions", "--then", "sites-in", "--record", "all", "--then", "dispatch");
    }

    private static void check(int expected, String... stages) throws Exception {
        var args = new ArrayList<>(List.of("pipeline", "--explain", "--"));
        args.addAll(List.of(stages));
        String[] argv = args.toArray(String[]::new);
        var result = CliTestSupport.capture(() -> AnatomistCli.commandLine(argv).execute(argv));
        assertEquals(expected, result.exitCode(), result.stderr());
        if (expected != 0) assertEquals("PIPELINE_INCOMPATIBLE_STAGES", ((Map<?, ?>) Json.parseTree(result.stderr())).get("code"));
    }

    private static ArrayList<String> words(String line) {
        var result = new ArrayList<String>();
        var matcher = Pattern.compile("'([^']*)'|([^\\s]+)").matcher(line);
        while (matcher.find()) result.add(matcher.group(1) == null ? matcher.group(2) : matcher.group(1));
        return result;
    }
}
