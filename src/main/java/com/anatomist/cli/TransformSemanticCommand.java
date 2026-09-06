package com.anatomist.cli;

import picocli.CommandLine.Option;

/** Semantic command that consumes a stream rather than only CLI selectors. */
abstract class TransformSemanticCommand extends SemanticCommand {
    @Option(names = "--accept-unframed",
            description = "Accept header-based data without evidence; coverage becomes unknown.")
    boolean acceptUnframed;

    @Override protected final boolean acceptUnframed() { return acceptUnframed; }
}
