package com.anatomist.core;

/** Builds and parses the storage identity: module::scope::symbol_id. */
public final class NodeKeyFactory {

    private static final String SEP = "::";

    private NodeKeyFactory() {}

    public static String key(SourceIdentity identity, String symbolId) {
        if (symbolId == null) return null;
        return escape(identity.module()) + SEP + identity.scope().name() + SEP + symbolId;
    }

    public static boolean isKey(String value) {
        if (value == null) return false;
        int first = value.indexOf(SEP);
        int second = first < 0 ? -1 : value.indexOf(SEP, first + SEP.length());
        if (first < 0 || second < 0) return false;
        try {
            SourceScope.valueOf(value.substring(first + SEP.length(), second));
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    public static String symbolId(String key) {
        int first = key.indexOf(SEP);
        int second = first < 0 ? -1 : key.indexOf(SEP, first + SEP.length());
        return second < 0 ? key : key.substring(second + SEP.length());
    }

    public static SourceIdentity identity(String key) {
        int first = key.indexOf(SEP);
        int second = first < 0 ? -1 : key.indexOf(SEP, first + SEP.length());
        if (first < 0 || second < 0) return new SourceIdentity(".", SourceScope.MAIN);
        return new SourceIdentity(unescape(key.substring(0, first)),
                SourceScope.valueOf(key.substring(first + SEP.length(), second)));
    }

    private static String escape(String module) {
        return module.replace("%", "%25").replace(":", "%3A");
    }

    private static String unescape(String module) {
        return module.replace("%3A", ":").replace("%25", "%");
    }
}
