package com.anatomist.cli;

import com.anatomist.application.SnapshotService;
import com.anatomist.json.Json;
import com.anatomist.query.VersionDiffService;
import com.anatomist.version.*;
import com.anatomist.store.SnapshotCatalog;
import picocli.CommandLine.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;

@Command(modelTransformer = AgentHelp.class, name="diff",mixinStandardHelpOptions=true,description="Locate changed source and navigate declarations in two frozen versions.",
        footer={"", "Examples: diff --base HEAD --target WORKTREE",
                "          diff --base main --target feature --merge-base",
                "          diff --base HEAD --target WORKTREE --module api --impact --impact-scope TEST",
                "Requires Git; does not switch the user's checkout. Moving refs are resolved before building.",
                "Output: anatomist-diff/v2. Select an anchor, then query its --snapshot and entity ID.",
                "Text navigation includes comments and formatting; use Git diff for code changes.",
                "File changes cover the project; --scope/--module select declarations and relationships.",
                "Check evidence per capability. Unindexed scopes and truncated impact cannot establish absence.",
                "Read skill versions for capture selection and skill maintenance for index recovery."})
public final class DiffCommand implements Callable<Integer> {
    @Option(names="--base",required=true,description="Base branch, HEAD, SHA, WORKTREE or snapshot:<id>.") String base;
    @Option(names="--target",required=true,description="Target selector; WORKTREE captures current disk content unless --no-build.") String target;
    @Option(names="--project",description="Project checkout (default current directory).") Path project=Path.of("").toAbsolutePath();
    @Option(names="--merge-base",description="Replace base with the common ancestor of the endpoint commits.") boolean mergeBase;
    @Option(names="--no-build",description="Only use existing snapshots; WORKTREE selects its last capture. Missing versions fail.") boolean noBuild;
    @Option(names="--impact",description="Include reverse static-call impact on each side; not proof of runtime execution.") boolean impact;
    @Option(names="--impact-depth",defaultValue="3",description="Maximum reverse-call depth: 0..100 (default 3).") int depth;
    @Option(names="--impact-scope",description="Returned caller scope (default --scope); requires --impact. Does not restrict traversal.") String impactScope;
    @Option(names="--impact-module",description="Returned caller module (default whole project); requires --impact. Does not restrict traversal.") String impactModule;
    @Option(names="--format",defaultValue="ndjson",description="Output: ndjson (default), json or table.") String format;
    @Option(names="--scope",defaultValue="MAIN",description="Declaration/relation scope: MAIN, TEST, GENERATED or ALL (default MAIN).") String scope;
    @Option(names="--module",description="Filter declarations and relationships by module.") String module;
    @Option(names="--no-classpath",description="Skip classpath detection for automatic builds; external resolution may be incomplete.") boolean noClasspath;
    @Option(names="--java-version",description="Target Java language version for automatic builds.") Integer javaVersion;
    @Override public Integer call() {
        try {
            format=CliValidation.choice("--format",format,"json","ndjson","table");
            scope=CliValidation.scope(scope,true);
            if(!impact && (impactScope!=null || impactModule!=null)) throw new IllegalArgumentException("--impact-scope and --impact-module require --impact");
            impactScope=impactScope==null?scope:CliValidation.scope(impactScope,true);
            if(depth<0 || depth>100) throw new IllegalArgumentException("--impact-depth must be between 0 and 100");
            SnapshotService service=new SnapshotService(project);
            // Resolve moving Git names before either endpoint starts building.
            String a=freeze(service,base),b=freeze(service,target);
            if(mergeBase) a=service.git().mergeBase(service.commit(a),service.commit(b));
            try(var guard=com.anatomist.store.IndexOperationLock.forWrite(service.directory().resolve("catalog.db"))) {
                var left=endpoint(service,a); var right=endpoint(service,b);
                var result=new VersionDiffService().compare(service,left,right,scope,module,impact,depth,impactScope,impactModule);
                if(format.equals("json")) System.out.println(Json.writePretty(result.json()));
                else if(format.equals("ndjson")) {
                    System.out.println(Json.writeCompact(result.header()));
                    result.changes().forEach(r->System.out.println(Json.writeCompact(r)));
                    System.out.println(Json.writeCompact(result.evidence()));
                } else {
                    System.out.println("BASE " + left.id() + "  TARGET " + right.id());
                    if(!left.profile().equals(right.profile())) System.out.println("Analysis environments differ; changes may have environmental causes.");
                    result.changes().forEach(r->System.out.println(r.get("record")+"\t"+r.getOrDefault("change",r.getOrDefault("side",""))+"\t"+r.getOrDefault("entity",r.getOrDefault("path",r.getOrDefault("relationship","")))
                            +(r.containsKey("origin")?"\t"+r.get("origin"):"")));
                    System.out.println(Json.writeCompact(result.evidence()));
                }
            }
            return 0;
        } catch(RuntimeException failure) {
            int exit=CliError.exit(failure); CliError.emit(CliError.of("diff",failure,exit)); return exit;
        }
    }
    private static String freeze(SnapshotService service,String selector) {
        return selector.equals("WORKTREE") || selector.startsWith("snapshot:")?selector:service.commit(selector);
    }
    private SnapshotCatalog.Entry endpoint(SnapshotService service,String ref) {
        if(noBuild || ref.startsWith("snapshot:")) return service.resolve(ref);
        if(!ref.equals("WORKTREE")) {
            try { return service.resolve(ref); }
            catch(SnapshotException failure) { if(!failure.code().equals("SNAPSHOT_MISSING")) throw failure; }
        }
        List<String> options=new ArrayList<>();
        if(noClasspath) options.add("--no-classpath");
        if(javaVersion!=null) options.addAll(List.of("--java-version",javaVersion.toString()));
        IndexCommand request=new IndexCommand();
        List<String> indexArgs=new ArrayList<>(List.of(project.toString()));indexArgs.addAll(options);
        new picocli.CommandLine(request).parseArgs(indexArgs.toArray(String[]::new));
        options=request.snapshotOptions();
        System.err.println("Building snapshot for " + ref);
        return service.build(ref,Json.writeCompact(options),false,
                IndexCommand.snapshotBuilder(options,project.toAbsolutePath().normalize())).entry();
    }
}
