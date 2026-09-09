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

@Command(modelTransformer = AgentHelp.class, name="diff",mixinStandardHelpOptions=true,description="Compare Git versions, navigate changes and inspect possible caller impact.",
        footer={"", "Branch work: diff --base main --target HEAD --merge-base --view calls --impact",
                "Branch tips: diff --base other --target HEAD --view calls --impact",
                "Disk edits:  diff --base HEAD --target WORKTREE --view calls --impact",
                "With tests:  diff --base main --target HEAD --merge-base --include-tests --scope ALL --impact-scope ALL --view calls --impact",
                "Requires Git; does not switch the user's checkout. Moving refs are resolved before building.",
                "Output: anatomist-diff/v2. Select an anchor, then query its --snapshot and entity ID.",
                "Text navigation includes comments and formatting; use Git diff for code changes.",
                "File changes cover captured project inputs; --scope/--module select declarations and relationships.",
                "WORKTREE follows Git ignores plus required analysis inputs; unrelated build outputs are excluded.",
                "Use --timings for costs; snapshots stats and snapshots gc for storage and recovery.",
                "--include-tests prepares TEST coverage; query scopes never expand a capture.",
                "Automatic preparation matches indexing configuration; --no-build reports missing/incompatible captures.",
                "Explicit snapshot IDs stay fixed; conflicting requirements fail. --merge-base cannot replace an explicit base snapshot.",
                "--view changes presentation only; --impact independently enables caller analysis.",
                "Check evidence per capability. Unindexed scopes and truncated impact cannot establish absence.",
                "Read skill versions for capture selection and skill maintenance for index recovery."})
public final class DiffCommand implements Callable<Integer> {
    private boolean builtAny;
    @Option(names="--base",required=true,description="Base branch, HEAD, SHA, WORKTREE or snapshot:<id>.") String base;
    @Option(names="--target",required=true,description="Target selector; WORKTREE captures current disk content unless --no-build.") String target;
    @Option(names="--project",description="Project checkout (default current directory).") Path project=Path.of("").toAbsolutePath();
    @Option(names="--merge-base",description="Replace base with the common ancestor of the endpoint commits.") boolean mergeBase;
    @Option(names="--no-build",description="Only use matching existing snapshots; WORKTREE selects its last capture for the requested configuration. Missing or incompatible captures fail.") boolean noBuild;
    @Option(names="--include-tests",description="Include TEST in automatic snapshot scans, including test-only Maven modules. Independent of query scope; explicit roots must include TEST.") boolean includeTests;
    @Option(names="--timings",description="Report preparation and comparison timings in the header.") boolean timings;
    @Option(names="--view",defaultValue="all",description="all (default) | calls: declaration anchors, CALLS changes and requested impact.") String view;
    @Option(names="--impact",description="Include possible callers on each side, including interface/override dispatch; not runtime execution.") boolean impact;
    @Option(names="--impact-dispatch",description="auto (default): include hierarchy candidates; resolved: recorded calls only. Requires --impact.") String impactDispatch;
    @Option(names="--impact-depth",defaultValue="3",description="Maximum reverse-call depth: 0..100 (default 3).") int depth;
    @Option(names="--impact-scope",description="Returned caller scope (default --scope); requires --impact. Does not restrict traversal.") String impactScope;
    @Option(names="--impact-module",description="Returned caller module (default whole project); requires --impact. Does not restrict traversal.") String impactModule;
    @Option(names="--format",defaultValue="ndjson",description="Output: ndjson (default), json or table.") String format;
    @Option(names="--scope",defaultValue="MAIN",description="Declaration/relation scope: MAIN, TEST, GENERATED or ALL (default MAIN).") String scope;
    @Option(names="--module",description="Filter declarations and relationships by module.") String module;
    @Option(names="--no-classpath",description="Skip classpath detection for automatic builds; external resolution may be incomplete.") boolean noClasspath;
    @Option(names="--java-version",description="Target Java language version for automatic builds.") Integer javaVersion;
    @Override public Integer call() {
        long gitStarted=com.anatomist.version.GitRepository.invocations();
        try {
            format=CliValidation.choice("--format",format,"json","ndjson","table");
            view=CliValidation.choice("--view",view,"all","calls");
            scope=CliValidation.scope(scope,true);
            if(!impact && (impactScope!=null || impactModule!=null || impactDispatch!=null)) throw new IllegalArgumentException("--impact-scope, --impact-module and --impact-dispatch require --impact");
            impactDispatch=CliValidation.choice("--impact-dispatch",impactDispatch==null?"auto":impactDispatch,"auto","resolved");
            impactScope=impactScope==null?scope:CliValidation.scope(impactScope,true);
            if(depth<0 || depth>100) throw new IllegalArgumentException("--impact-depth must be between 0 and 100");
            SnapshotService service=new SnapshotService(project);
            service.beginOperation();
            List<String> options=snapshotOptions();
            String buildRequest=Json.writeCompact(options);
            // Resolve moving Git names and configuration-specific WORKTREE pointers before building.
            String a=freeze(service,base,buildRequest,"base"),b=freeze(service,target,buildRequest,"target");
            String baseCommit=service.commit(a),targetCommit=service.commit(b);
            Map<String,Object> request=Map.of("mode",mergeBase?"merge_base":"endpoints",
                    "base",Map.of("selector",base,"commit",baseCommit),
                    "target",Map.of("selector",target,"commit",targetCommit));
            if(mergeBase) {
                String ancestor=service.git().mergeBase(baseCommit,targetCommit);
                if(base.startsWith("snapshot:") && !ancestor.equals(baseCommit))
                    throw new IllegalArgumentException("--merge-base would replace the explicit base snapshot; select a Git ref instead");
                if(!base.startsWith("snapshot:")) a=ancestor;
                baseCommit=ancestor;
            }
            long prepareStarted=System.nanoTime();
            try(var leftLease=lease(service,a,options,baseCommit,"base",base);
                var rightLease=lease(service,b,options,targetCommit,"target",target)) {
                var left=leftLease.entry(); var right=rightLease.entry();
                long prepareMs=(System.nanoTime()-prepareStarted)/1_000_000;
                long compareStarted=System.nanoTime();
                try(var result=new VersionDiffService().compare(service,left,right,scope,module,impact,depth,impactScope,impactModule,impactDispatch).present(view,request)) {
                if(timings) result.header().put("timings",Map.of("prepare_ms",prepareMs,"compare_ms",(System.nanoTime()-compareStarted)/1_000_000,"result_spilled_bytes",result.spilledBytes(),"preparation",service.operationMetrics(),"git_processes",com.anatomist.version.GitRepository.invocations()-gitStarted));
                if(builtAny) com.anatomist.application.SnapshotMaintenance.autoCollect(service,java.util.Set.copyOf(List.of(left.id(),right.id())));
                if(format.equals("json")) {
                    System.out.print("{\"contract\":\"anatomist-diff/v2\",\"comparison\":"+Json.writeCompact(result.header())+",\"changes\":[");
                    boolean first=true;
                    for(var row:result.changes()) { if(!first) System.out.print(",");System.out.print(Json.writeCompact(row));checkOutput();first=false; }
                    System.out.println("],\"evidence\":"+Json.writeCompact(result.evidence())+"}");
                }
                else if(format.equals("ndjson")) {
                    System.out.println(Json.writeCompact(result.header()));
                    result.changes().forEach(r->{System.out.println(Json.writeCompact(r));checkOutput();});
                    System.out.println(Json.writeCompact(result.evidence()));
                } else {
                    System.out.println("BASE " + left.id() + "  TARGET " + right.id());
                    System.out.println("COMPARISON " + Json.writeCompact(request) + "  OUTPUT " + Json.writeCompact(result.header().get("output")));
                    if(impact) System.out.println("IMPACT " + Json.writeCompact(result.header().get("impact")));
                    if(!left.profile().equals(right.profile())) System.out.println("Analysis environments differ; changes may have environmental causes.");
                    result.changes().forEach(r->System.out.println(r.get("record")+"\t"+r.getOrDefault("change",r.getOrDefault("side",""))+"\t"+r.getOrDefault("entity",r.getOrDefault("path",r.getOrDefault("relationship","")))
                            +(r.containsKey("origin")?"\t"+r.get("origin")+"\tdispatch="+(Boolean.TRUE.equals(r.get("contains_possible_dispatch"))?"possible":"resolved"):"")));
                    System.out.println(Json.writeCompact(result.evidence()));
                }
            }
            checkOutput();return 0;
            }
        } catch(RuntimeException failure) {
            int exit=CliError.exit(failure); CliError.emit(CliError.of("diff",failure,exit)); return exit;
        }
    }
    private static void checkOutput() {
        if(System.out.checkError()) throw new SnapshotException("DIFF_OUTPUT_FAILED","Diff output stream closed or failed");
    }
    private String freeze(SnapshotService service,String selector,String request,String side) {
        try {
            if(noBuild && selector.equals("WORKTREE")) return "snapshot:"+service.resolve(selector,request).id();
            return selector.equals("WORKTREE") || selector.startsWith("snapshot:")?selector:service.commit(selector);
        } catch(SnapshotException failure) { throw diagnostic(failure,side,selector); }
    }
    List<String> snapshotOptions() {
        List<String> options=new ArrayList<>();
        if(includeTests) options.add("--include-tests");
        if(noClasspath) options.add("--no-classpath");
        if(javaVersion!=null) options.addAll(List.of("--java-version",javaVersion.toString()));
        IndexCommand request=new IndexCommand();
        List<String> indexArgs=new ArrayList<>(List.of(project.toString()));indexArgs.addAll(options);
        new picocli.CommandLine(request).parseArgs(indexArgs.toArray(String[]::new));
        return request.snapshotOptions();
    }
    private SnapshotCatalog.Entry endpoint(SnapshotService service,String ref,List<String> options,String commit,String side,String selector) {
        try {
            if(ref.startsWith("snapshot:")) return service.resolve(ref);
            String request=Json.writeCompact(options);
            if(noBuild) return service.resolve(ref,request);
            // build already performs request-aware reuse and validates dependency artifacts.
            var builder=IndexCommand.snapshotBuilder(options,project.toAbsolutePath().normalize());
            var built=service.build(ref,request,false,new SnapshotService.Builder() {
                public java.util.Collection<Path> inputs(Path root) throws Exception { return builder.inputs(root); }
                public java.util.Collection<Path> artifacts(Path root) throws Exception { return builder.artifacts(root); }
                public Map<String,Object> build(Path root,Path db,boolean incremental) throws Exception {
                if(includeTests) {
                    var roots=com.anatomist.config.ConfigLoader.load(root).sourceRootSpecs();
                    var coverage=new IndexCommand();
                    coverage.sourceRootSpecs=roots;
                    if(!roots.isEmpty() && coverage.resolveSourceRoots(root,List.of()).stream()
                            .noneMatch(r->r.scope()==com.anatomist.core.SourceScope.TEST))
                        throw new SnapshotException("SNAPSHOT_COVERAGE_MISMATCH","Explicit source roots must include TEST when --include-tests is requested",
                                Map.of("required_scope","TEST","reason","EXPLICIT_ROOTS_EXCLUDE_TEST"));
                }
                return builder.build(root,db,incremental);
            }},commit);
            builtAny |= !built.reused();
            return built.entry();
        } catch(SnapshotException failure) { throw diagnostic(failure,side,selector); }
    }
    private record Lease(SnapshotCatalog.Entry entry,com.anatomist.store.IndexLock lock) implements AutoCloseable {
        public void close() { lock.close(); }
    }
    private Lease lease(SnapshotService service,String ref,List<String> options,String commit,String side,String selector) {
        for(int attempt=0;attempt<2;attempt++) {
            var entry=endpoint(service,ref,options,commit,side,selector);
            var lock=com.anatomist.store.IndexLock.forRead(service.database(entry.id()));
            try {
                entry=service.resolve("snapshot:"+entry.id());
                service.requireOptions(entry,includeTests,javaVersion,noClasspath);
                return new Lease(entry,lock);
            } catch(RuntimeException failure) {
                lock.close();
                if(attempt==1 || noBuild || ref.startsWith("snapshot:") || !(failure instanceof SnapshotException version)
                        || !java.util.Set.of("SNAPSHOT_MISSING","SNAPSHOT_NOT_READY").contains(version.code())) throw failure;
            }
        }
        throw new IllegalStateException("Unreachable endpoint retry");
    }
    private SnapshotException diagnostic(SnapshotException failure,String side,String selector) {
        Map<String,Object> details=new LinkedHashMap<>(failure.details());
        details.put("side",side); details.put("selector",selector);
        return new SnapshotException(failure.code(),failure.getMessage(),details);
    }
}
