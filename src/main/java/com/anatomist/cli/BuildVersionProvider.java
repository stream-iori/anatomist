package com.anatomist.cli;

import picocli.CommandLine;

public final class BuildVersionProvider implements CommandLine.IVersionProvider {
    @Override
    public String[] getVersion() {
        return new String[] { BuildVersion.display() };
    }
}
