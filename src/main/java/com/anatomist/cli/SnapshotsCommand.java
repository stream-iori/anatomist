package com.anatomist.cli;

import com.anatomist.application.SnapshotService;
import com.anatomist.json.Json;
import com.anatomist.version.SnapshotCatalog;
import com.anatomist.store.IndexOperationLock;
import picocli.CommandLine.*;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name="snapshots",mixinStandardHelpOptions=true,description="List, inspect and pin immutable snapshots.")
public final class SnapshotsCommand implements Callable<Integer> {
    @Parameters(index="0",defaultValue="list") String action;
    @Parameters(index="1",arity="0..1") String id;
    @Option(names="--project") Path project=Path.of("").toAbsolutePath();
    @Option(names="--format",defaultValue="json") String format;
    @Override public Integer call() {
        try {
            CliValidation.choice("--format",format,"json","table");
            CliValidation.choice("action",action,"list","show","pin","unpin");
            SnapshotService service=new SnapshotService(project);
            try(var lock=IndexOperationLock.forWrite(service.directory().resolve("catalog.db"));
                var catalog=new SnapshotCatalog(service.directory())) {
                if(action.equals("list")) System.out.println(Json.writePretty(catalog.list().stream().map(SnapshotCatalog.Entry::json).toList()));
                else {
                    if(id==null) throw new IllegalArgumentException("Snapshot ID is required for " + action);
                    if(action.equals("pin") || action.equals("unpin")) catalog.pin(id,action.equals("pin"));
                    System.out.println(Json.writePretty(catalog.get(id).json()));
                }
            }
            return 0;
        } catch(RuntimeException failure) {
            int exit=CliError.exit(failure); CliError.emit(CliError.of("snapshots",failure,exit)); return exit;
        }
    }
}
