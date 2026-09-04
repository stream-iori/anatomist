package com.anatomist.framework;

import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;

import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

/** Adds source-observed evidence to an existing fallback call without inventing graph nodes. */
public interface CallSiteEvidenceProvider extends ExtensionPoint {

    Optional<Evidence> observe(MethodCallExpr call, FallbackCallSite site);

    record FallbackCallSite(
            String ownerFqn,
            ResolvedReferenceTypeDeclaration ownerDeclaration,
            boolean projectInternal,
            String callKind,
            String resolution
    ) {}

    record Evidence(String namespace, Map<String, Object> value, String counter) {
        public Evidence {
            if (namespace == null || namespace.isBlank()) {
                throw new IllegalArgumentException("evidence namespace must not be blank");
            }
            value = value == null ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(value));
        }
    }
}
