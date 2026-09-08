package com.anatomist.version;

import java.nio.file.Path;

/** Read-side locations; querying does not depend on build orchestration. */
public interface SnapshotAccess {
    GitRepository git();
    Path directory();
    Path snapshotDirectory(String id);
    Path database(String id);
}
