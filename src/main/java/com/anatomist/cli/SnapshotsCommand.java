package com.anatomist.cli;

import com.anatomist.application.SnapshotService;
import com.anatomist.json.Json;
import com.anatomist.store.SnapshotCatalog;
import com.anatomist.store.IndexOperationLock;
import picocli.CommandLine.*;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(modelTransformer = AgentHelp.class, name="snapshots",mixinStandardHelpOptions=true,description="List, inspect, pin and garbage-collect immutable snapshots.",
        footer={"", "Examples: snapshots list --format table",
                "          snapshots pin <id>", "          snapshots gc --keep 20",
                "          snapshots gc --keep 20 --execute",
                "GC previews recovery and removal; --execute performs them. Pins, valid entrypoints and readers are protected.",
                "Use stats for storage counts; --max-bytes may reduce --keep. budget_met=false explains protected excess.",
                "Automatic GC is opt-in through [versions.gc] auto. Lock-file tombstones remain intentionally.",
                "Deleted WORKTREE content cannot be recovered from Git. Unpinning does not itself delete data."})
public final class SnapshotsCommand implements Callable<Integer> {
    @Spec picocli.CommandLine.Model.CommandSpec spec;
    @Parameters(index="0",defaultValue="list",description="Action: list (default), show, pin, unpin, stats or gc.") String action;
    @Parameters(index="1",arity="0..1",description="Snapshot ID; required for show, pin and unpin.") String id;
    @Option(names="--project",description="Project checkout (default current directory).") Path project=Path.of("").toAbsolutePath();
    @Option(names="--format",defaultValue="json",description="List output: json (default) or table. Other actions emit JSON.") String format;
    @Option(names="--keep",defaultValue="20",description="GC retains this many newest successful snapshots, plus protected versions (default 20).") int keep;
    @Option(names="--max-bytes",defaultValue="0",description="GC byte budget; 0 disables. May reduce --keep, never pins or active snapshots.") long maxBytes;
    @Option(names="--max-age-days",defaultValue="30",description="Expire unused entrypoints and optional classpath cache after this age.") int maxAgeDays;
    @Option(names="--include-caches",negatable=true,description="GC also collects expired Anatomist classpath lists and temporary files.") boolean includeCaches;
    @Option(names="--execute",description="Execute GC; without this flag GC is a preview.") boolean execute;
    @Override public Integer call() {
        try {
            CliValidation.choice("--format",format,"json","table");
            CliValidation.choice("action",action,"list","show","pin","unpin","stats","gc");
            SnapshotService service=new SnapshotService(project);
            if(action.equals("stats")) {
                System.out.println(Json.writePretty(com.anatomist.application.SnapshotMaintenance.stats(service)));return 0;
            }
            if(action.equals("gc")) {
                var config=com.anatomist.config.ConfigLoader.load(service.git().project());
                var parsed=spec.commandLine().getParseResult();
                if(!parsed.hasMatchedOption("--keep")) keep=config.versionsKeep();
                if(!parsed.hasMatchedOption("--max-bytes")) maxBytes=config.versionsMaxBytes();
                if(!parsed.hasMatchedOption("--max-age-days")) maxAgeDays=config.versionsMaxAgeDays();
                if(!parsed.hasMatchedOption("--include-caches")) includeCaches=config.versionsIncludeCaches();
                System.out.println(Json.writePretty(com.anatomist.application.SnapshotMaintenance.collect(service,keep,maxBytes,maxAgeDays,includeCaches,execute,java.util.Set.of())));
                return 0;
            }
            try(var lock=IndexOperationLock.forWrite(service.directory().resolve("catalog.db"));
                var catalog=new SnapshotCatalog(service.directory())) {
                if(action.equals("list")) {
                    var entries=catalog.list();
                    if(format.equals("table")) {
                        System.out.println("ID\tSTATUS\tPINNED\tCOMMIT\tCREATED");
                        entries.forEach(e->System.out.println(e.id()+"\t"+e.status()+"\t"+e.pinned()+"\t"+e.commit()+"\t"+e.created()));
                    } else System.out.println(Json.writePretty(entries.stream().map(SnapshotCatalog.Entry::json).toList()));
                }
                else {
                    if(id==null) throw new IllegalArgumentException("Snapshot ID is required for " + action);
                    if(action.equals("pin") || action.equals("unpin")) catalog.pin(id,action.equals("pin"));
                    System.out.println(Json.writePretty(catalog.get(id).json()));
                }
            }
            return 0;
        } catch(Exception cause) {
            RuntimeException failure=cause instanceof RuntimeException r?r:new com.anatomist.version.SnapshotException("SNAPSHOT_STATS_FAILED",cause.getMessage(),cause);
            int exit=CliError.exit(failure); CliError.emit(CliError.of("snapshots",failure,exit)); return exit;
        }
    }
}
