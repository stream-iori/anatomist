package com.anatomist.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.PrintStream;

/** Single Jackson instance shared by every query command — snake_case keys,
 *  pretty-printed, stable across CLI / golden-file tests. */
public final class JsonFormatter {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private JsonFormatter() {}

    public static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException("JSON serialization failed", e);
        }
    }

    public static void emit(PrintStream out, QueryEnvelope env) {
        out.println(toJson(env));
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
