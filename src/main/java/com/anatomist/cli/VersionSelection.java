package com.anatomist.cli;

import com.anatomist.application.SnapshotService;
import picocli.CommandLine.Option;
import java.nio.file.Path;

/** Shared selection for read commands; absent options preserve the legacy default. */
final class VersionSelection {
    @Option(names="--ref",description="Read an indexed Git ref or the last captured WORKTREE.") String ref;
    @Option(names="--snapshot",description="Read one immutable snapshot ID.") String snapshot;
    @Option(names="--project",description="Project checkout for version lookup (default current directory).") Path project;
    Path resolve(Path index) {
        if((ref!=null?1:0)+(snapshot!=null?1:0)+(index!=null?1:0)>1)
            throw new IllegalArgumentException("--ref, --snapshot and --index are mutually exclusive");
        Path root=project==null?Path.of("").toAbsolutePath():project;
        if(ref==null && snapshot==null) return IndexPath.resolve(index,root);
        SnapshotService service=new SnapshotService(root);
        return service.database(service.resolve(snapshot==null?ref:"snapshot:"+snapshot).id());
    }
}
