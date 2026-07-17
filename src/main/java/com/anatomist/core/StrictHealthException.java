package com.anatomist.core;

/** Stops a full index before graph promotion when strict health is violated. */
public final class StrictHealthException extends Exception {
    private final ParseInventory parseInventory;

    public StrictHealthException(ParseInventory parseInventory) {
        super("strict health rejected an incomplete parse: "
                + parseInventory.failedFiles() + " file(s) failed");
        this.parseInventory = parseInventory;
    }

    public ParseInventory parseInventory() {
        return parseInventory;
    }
}
