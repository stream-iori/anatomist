package com.anatomist.query;

/** Per-package node tallies for the {@code overview} command. */
public class PackageStat {
    public String name;
    public long types;
    public long methods;

    public PackageStat() {}

    public PackageStat(String name) {
        this.name = name;
    }
}
