package com.anatomist.core;

import java.nio.file.Files;
import java.nio.file.Path;

/** Validates an index request and owns failure propagation outside the CLI parser. */
public final class IndexApplicationService {

    @FunctionalInterface
    public interface IndexWork {
        int run(Path projectRoot) throws Exception;
    }

    public IndexOutcome execute(IndexRequest request, IndexWork work) {
        if (request.projectPath() == null || !Files.isDirectory(request.projectPath())) {
            return IndexOutcome.failure(1,
                    "project path does not exist or is not a directory: " + request.projectPath());
        }
        if (request.projectSource() != null && !request.projectSource().isBlank()
                && !request.sourceRootSpecs().isEmpty()) {
            return IndexOutcome.failure(2,
                    "--project-source and --source-root are mutually exclusive");
        }
        try {
            return IndexOutcome.success(work.run(request.projectPath().toRealPath().normalize()));
        } catch (JavaVersionException e) {
            return IndexOutcome.failure(e.exitCode(), e.getMessage());
        } catch (Exception e) {
            return IndexOutcome.failure(e);
        }
    }
}
