package com.anatomist.query;

import com.anatomist.json.DtoCodecs;
import com.anatomist.json.Json;

import java.io.PrintStream;

/** Pretty-printer entry point for query subcommands. Delegates to the
 *  hand-written {@link Json} layer so anatomist remains reflection-free. */
public final class JsonFormatter {

    static {
        DtoCodecs.ensureRegistered();
    }

    private JsonFormatter() {}

    public static String toJson(Object value) {
        return Json.writePretty(value);
    }

    public static void emit(PrintStream out, QueryEnvelope env) {
        out.println(toJson(env));
    }
}
