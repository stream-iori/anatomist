package com.anatomist.query;

import com.anatomist.json.Json;
import com.anatomist.model.GraphConstants;
import com.anatomist.query.JavaSemanticRows.CallableRelation;
import com.anatomist.query.JavaSemanticRows.DispatchTarget;
import com.anatomist.query.JavaSemanticRows.RuntimeImplementation;
import com.anatomist.query.JavaSemanticRows.TypeRelation;
import com.anatomist.query.semantic.SemanticIdentity;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.anatomist.query.QueryInfra.qmarks;
import static com.anatomist.query.QueryInfra.sqlIn;

/** Java language adapter for type, callable, and dispatch semantics. */
final class JavaSemanticService {
    private static final Set<String> TYPE_RELATIONS = Set.of(
            GraphConstants.Relation.IMPLEMENTS,
            GraphConstants.Relation.INHERITS,
            GraphConstants.Relation.PERMITS);

    private final Connection connection;
    private final NodeResolver resolver;
    private String module;
    private String scope = "MAIN";

    JavaSemanticService(Connection connection, NodeResolver resolver) {
        this.connection = connection;
        this.resolver = resolver;
    }

    void select(String module, String scope) {
        this.module = module == null || module.isBlank() ? null : module;
        this.scope = scope == null || scope.isBlank() ? "MAIN" : scope.toUpperCase();
    }

    List<TypeRelation> typeRelations(String seed, String direction, String semantic,
                                     boolean transitive, int maxDepth, int limit) {
        if (transitive && "any".equals(semantic)) {
            throw new IllegalArgumentException(
                    "--transitive requires one --semantic: subtype-of or conforms-to");
        }
        List<TypeRelation> out = new ArrayList<>();
        Set<String> seenRelations = new LinkedHashSet<>();
        Set<String> visited = new HashSet<>();
        Deque<String> frontier = new ArrayDeque<>();
        frontier.add(seed);
        visited.add(seed);
        int depth = 0;
        while (!frontier.isEmpty() && depth++ < (transitive ? maxDepth : 1)
                && out.size() < limit) {
            List<String> current = new ArrayList<>(frontier);
            frontier.clear();
            for (TypeRelation relation : directTypeRelations(current, direction, semantic)) {
                if (seenRelations.add(relation.id())) out.add(relation);
                if (out.size() >= limit) break;
                String next = "outgoing".equals(direction) ? relation.object() : relation.subject();
                if (!relation.externalObject() && visited.add(next)) frontier.add(next);
            }
            if (!transitive) break;
        }
        return List.copyOf(out);
    }

    List<RuntimeImplementation> runtimeImplementations(String seed, String filter,
                                                       String world, int maxDepth, int limit) {
        List<RuntimeImplementation> out = new ArrayList<>();
        Map<String, List<TypeRelation>> proof = new LinkedHashMap<>();
        proof.put(seed, List.of());
        Deque<String> frontier = new ArrayDeque<>();
        frontier.add(seed);
        Set<String> visited = new HashSet<>();
        visited.add(seed);
        int depth = 0;
        while (!frontier.isEmpty() && depth++ < maxDepth && out.size() < limit) {
            List<String> current = new ArrayList<>(frontier);
            frontier.clear();
            for (String parent : current) {
                for (TypeRelation relation : directTypeRelations(List.of(parent), "incoming", "any")) {
                    String child = relation.subject();
                    if (!visited.add(child)) continue;
                    List<TypeRelation> childProof = new ArrayList<>(proof.getOrDefault(parent, List.of()));
                    childProof.add(relation);
                    proof.put(child, List.copyOf(childProof));
                    frontier.add(child);
                    NodeRow node = resolver.readNodeById(child);
                    if (node == null) continue;
                    Instantiability state = instantiability(node);
                    if (matchesInstantiability(filter, state.state())) {
                        out.add(new RuntimeImplementation(node, state.state(), state.reason(),
                                world, childProof));
                        if (out.size() >= limit) break;
                    }
                }
                if (out.size() >= limit) break;
            }
        }
        return List.copyOf(out);
    }

    List<CallableRelation> callableRelations(String seed, String direction,
                                             boolean transitive, int maxDepth, int limit) {
        List<CallableRelation> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Set<String> visited = new HashSet<>();
        visited.add(seed);
        Deque<String> frontier = new ArrayDeque<>();
        frontier.add(seed);
        int depth = 0;
        while (!frontier.isEmpty() && depth++ < (transitive ? maxDepth : 1)
                && out.size() < limit) {
            List<String> current = new ArrayList<>(frontier);
            frontier.clear();
            for (CallableRelation relation : directCallableRelations(current, direction)) {
                if (seen.add(relation.id())) out.add(relation);
                if (out.size() >= limit) break;
                String next = "outgoing".equals(direction) ? relation.object() : relation.subject();
                if (!relation.externalObject() && visited.add(next)) frontier.add(next);
            }
            if (!transitive) break;
        }
        return List.copyOf(out);
    }

    @SuppressWarnings("unchecked")
    List<DispatchTarget> dispatch(Map<String, Object> callSite, String requestedAlgorithm,
                                  String world, int maxDepth, int limit) {
        String siteId = String.valueOf(callSite.get("id"));
        String caller = String.valueOf(callSite.get("caller"));
        String dispatchKind = String.valueOf(callSite.getOrDefault("dispatch_kind", "unknown"));
        Object targetsValue = callSite.get("resolved_targets");
        if (!(targetsValue instanceof List<?> targets)) return List.of();
        LinkedHashMap<String, DispatchTarget> out = new LinkedHashMap<>();
        for (Object value : targets) {
            if (!(value instanceof Map<?, ?> raw)) continue;
            Map<String, Object> target = (Map<String, Object>) raw;
            String targetId = string(target.get("id"));
            if (targetId == null) continue;
            boolean external = Boolean.TRUE.equals(target.get("external"));
            NodeRow node = external ? null : resolver.readNodeById(targetId);
            String algorithm = chooseAlgorithm(requestedAlgorithm, dispatchKind, node);
            boolean abstractTarget = node != null && modifiers(node).contains("abstract");
            Boolean executable = external ? null : !abstractTarget;
            String targetState = external ? "unknown" : ownerInstantiability(node).state();
            DispatchTarget resolved = dispatchTarget(siteId, caller, targetId,
                    string(target.get("qualified_name")), "resolved",
                    mechanism(dispatchKind, "exact".equals(algorithm)), executable, targetState,
                    algorithm, world, external ? "heuristic" :
                            stringOr(target.get("resolution_status"), "exact"),
                    List.of("java.static_resolution"), List.of());
            out.put(targetId, resolved);

            if (external || "exact".equals(algorithm)) continue;
            Set<String> configuredTypes = configuredTypes(caller, targetId);
            Map<String, List<CallableRelation>> proofs = new LinkedHashMap<>();
            proofs.put(targetId, List.of());
            Deque<String> frontier = new ArrayDeque<>();
            frontier.add(targetId);
            Set<String> visited = new HashSet<>();
            visited.add(targetId);
            int depth = 0;
            while (!frontier.isEmpty() && depth++ < maxDepth && out.size() < limit) {
                List<String> current = new ArrayList<>(frontier);
                frontier.clear();
                for (String parent : current) {
                    for (CallableRelation relation : directCallableRelations(
                            List.of(parent), "incoming")) {
                        String candidateId = relation.subject();
                        if (!visited.add(candidateId)) continue;
                        List<CallableRelation> candidateProof = new ArrayList<>(
                                proofs.getOrDefault(parent, List.of()));
                        candidateProof.add(relation);
                        proofs.put(candidateId, List.copyOf(candidateProof));
                        frontier.add(candidateId);
                        NodeRow candidate = resolver.readNodeById(candidateId);
                        if (candidate == null || modifiers(candidate).contains("abstract")) continue;
                        boolean configured = configuredTypes.contains(ownerType(candidateId));
                        if (!configuredTypes.isEmpty() && !configured) continue;
                        Instantiability owner = ownerInstantiability(candidate);
                        if (!"yes".equals(owner.state())) continue;
                        out.put(candidateId, dispatchTarget(siteId, caller, candidateId,
                                candidate.qualifiedName, "possible",
                                mechanism(dispatchKind, false), true, owner.state(), "CHA", world,
                                configured ? "exact" : "heuristic",
                                configured ? List.of("java.override", "configuration.binding")
                                        : List.of("java.override", "java.runtime_type"),
                                candidateProof));
                        if (out.size() >= limit) break;
                    }
                    if (out.size() >= limit) break;
                }
            }
        }
        return List.copyOf(out.values());
    }

    private Set<String> configuredTypes(String caller, String target) {
        String callerType = ownerType(caller);
        String declaredType = ownerType(target);
        if (callerType == null || declaredType == null) return Set.of();
        String sql = "SELECT DISTINCT w.target_id FROM edges i JOIN edges w "
                + "ON w.source_id=i.source_id AND w.relation='WIRES' AND w.is_external=0 "
                + "WHERE i.relation='INJECTS' AND i.is_external=0 "
                + "AND i.source_id=? AND i.target_id=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, callerType); statement.setString(2, declaredType);
            try (ResultSet rows = statement.executeQuery()) {
                Set<String> out = new LinkedHashSet<>();
                while (rows.next()) out.add(rows.getString(1));
                return Set.copyOf(out);
            }
        } catch (SQLException failure) {
            throw new RuntimeException("failed to query configured dispatch targets", failure);
        }
    }

    private static String ownerType(String callable) {
        if (callable == null) return null;
        int hash = callable.indexOf('#');
        return hash < 1 ? null : callable.substring(0, hash);
    }

    private List<TypeRelation> directTypeRelations(List<String> ids, String direction,
                                                   String semantic) {
        if (ids.isEmpty()) return List.of();
        String column = "outgoing".equals(direction) ? "e.source_id" : "e.target_id";
        String sql = "SELECT e.source_id,e.target_id,e.external_target_fqn,e.relation,"
                + "e.confidence,e.resolution,s.qualified_name,s.kind,t.qualified_name,t.kind "
                + "FROM edges e JOIN nodes s ON s.id=e.source_id "
                + "LEFT JOIN nodes t ON t.id=e.target_id WHERE " + column + " IN ("
                + qmarks(ids.size()) + ") AND e.relation IN (" + sqlIn(TYPE_RELATIONS) + ")"
                + selection("s") + " ORDER BY e.source_id,e.relation,e.target_id,e.external_target_fqn";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, ids);
            try (ResultSet rows = statement.executeQuery()) {
                List<TypeRelation> out = new ArrayList<>();
                while (rows.next()) {
                    String relation = rows.getString(4);
                    String mapped = semanticOf(relation);
                    if (!"any".equals(semantic) && !semantic.equals(mapped)) continue;
                    String subject = rows.getString(1);
                    String object = rows.getString(2);
                    String external = rows.getString(3);
                    String mechanism = mechanismOf(relation, rows.getString(8), rows.getString(10));
                    String id = "typerel:sha256:" + SemanticIdentity.sha256(mapped + "\n"
                            + mechanism + "\n" + subject + "\n" + (object == null ? external : object));
                    out.add(new TypeRelation(id, mapped, mechanism, subject,
                            object == null ? external : object, rows.getString(7),
                            rows.getString(9) == null ? external : rows.getString(9),
                            object == null, true, origin(rows.getString(5)),
                            resolution(rows.getString(5), rows.getString(6)),
                            lower(rows.getString(5))));
                }
                return out;
            }
        } catch (SQLException failure) {
            throw new RuntimeException("failed to query Java type semantics", failure);
        }
    }

    private List<CallableRelation> directCallableRelations(List<String> ids, String direction) {
        if (ids.isEmpty()) return List.of();
        String column = "outgoing".equals(direction) ? "e.source_id" : "e.target_id";
        String sql = "SELECT e.source_id,e.target_id,e.external_target_fqn,e.confidence,e.resolution,"
                + "s.qualified_name,t.qualified_name FROM edges e "
                + "JOIN nodes s ON s.id=e.source_id LEFT JOIN nodes t ON t.id=e.target_id "
                + "WHERE " + column + " IN (" + qmarks(ids.size()) + ") AND e.relation='"
                + GraphConstants.Relation.OVERRIDES + "'" + selection("s")
                + " ORDER BY e.source_id,e.target_id,e.external_target_fqn";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, ids);
            try (ResultSet rows = statement.executeQuery()) {
                List<CallableRelation> out = new ArrayList<>();
                while (rows.next()) {
                    String subject = rows.getString(1);
                    String object = rows.getString(2);
                    String external = rows.getString(3);
                    String targetId = object == null ? external : object;
                    String targetOwnerKind = object == null ? null : ownerKind(object);
                    String semantic = "INTERFACE".equals(targetOwnerKind)
                            ? "IMPLEMENTS_CONTRACT" : "OVERRIDES";
                    String mechanism = "INTERFACE".equals(targetOwnerKind)
                            ? "java.interface_method" : "java.override";
                    String id = "callablerel:sha256:" + SemanticIdentity.sha256(semantic + "\n"
                            + mechanism + "\n" + subject + "\n" + targetId);
                    out.add(new CallableRelation(id, semantic, mechanism, subject, targetId,
                            rows.getString(6), rows.getString(7) == null ? external : rows.getString(7),
                            object == null, origin(rows.getString(4)),
                            resolution(rows.getString(4), rows.getString(5)), lower(rows.getString(4))));
                }
                return out;
            }
        } catch (SQLException failure) {
            throw new RuntimeException("failed to query Java callable semantics", failure);
        }
    }

    private DispatchTarget dispatchTarget(String site, String caller, String target,
                                          String targetName, String candidateKind,
                                          String mechanism, Boolean executable,
                                          String instantiability, String algorithm, String world,
                                          String resolution, List<String> reason,
                                          List<CallableRelation> proof) {
        String id = "dispatch:sha256:" + SemanticIdentity.sha256(site + "\n" + target + "\n"
                + candidateKind + "\n" + algorithm + "\n" + world);
        return new DispatchTarget(id, site, caller, target, targetName, candidateKind,
                mechanism, executable, instantiability, algorithm, world, resolution,
                reason, proof);
    }

    private String chooseAlgorithm(String requested, String dispatchKind, NodeRow target) {
        if (!"auto".equals(requested)) return "cha".equals(requested) ? "CHA" : "exact";
        if (Set.of("static", "super", "constructor").contains(dispatchKind)) return "exact";
        if (target == null) return "CHA";
        Set<String> modifiers = modifiers(target);
        if (modifiers.contains("static") || modifiers.contains("private")
                || modifiers.contains("final") || "CONSTRUCTOR".equals(target.kind)) return "exact";
        Instantiability owner = ownerInstantiability(target);
        if ("java.final_class".equals(owner.reason())) return "exact";
        return "CHA";
    }

    private Instantiability ownerInstantiability(NodeRow callable) {
        if (callable == null || callable.id == null) return new Instantiability("unknown", "java.external");
        int hash = callable.id.indexOf('#');
        if (hash < 0) return new Instantiability("unknown", "java.owner_unknown");
        NodeRow owner = resolver.readNodeById(callable.id.substring(0, hash));
        return owner == null ? new Instantiability("unknown", "java.owner_unknown")
                : instantiability(owner);
    }

    private Instantiability instantiability(NodeRow node) {
        if (node == null) return new Instantiability("unknown", "java.external");
        return switch (node.kind) {
            case "INTERFACE" -> new Instantiability("no", "java.interface");
            case "ANNOTATION" -> new Instantiability("no", "java.annotation");
            case "ENUM" -> new Instantiability("yes", "java.enum");
            case "RECORD" -> new Instantiability("yes", "java.record");
            case "ANONYMOUS_CLASS" -> new Instantiability("yes", "java.anonymous_class");
            case "CLASS" -> modifiers(node).contains("abstract")
                    ? new Instantiability("no", "java.abstract_class")
                    : new Instantiability("yes", modifiers(node).contains("final")
                            ? "java.final_class" : "java.concrete_class");
            default -> new Instantiability("unknown", "java.kind_unknown");
        };
    }

    private Set<String> modifiers(NodeRow node) {
        if (node == null || node.id == null) return Set.of();
        String sql = "SELECT modifiers FROM nodes WHERE id=? AND declaration_kind IS NOT NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, node.id);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return Set.of();
                Object parsed = Json.parseTree(rows.getString(1));
                if (!(parsed instanceof List<?> values)) return Set.of();
                Set<String> out = new HashSet<>();
                for (Object value : values) out.add(String.valueOf(value));
                return Set.copyOf(out);
            }
        } catch (SQLException failure) {
            throw new RuntimeException("failed to query declaration modifiers", failure);
        }
    }

    private String ownerKind(String callable) {
        int hash = callable == null ? -1 : callable.indexOf('#');
        NodeRow owner = hash < 0 ? null : resolver.readNodeById(callable.substring(0, hash));
        return owner == null ? null : owner.kind;
    }

    private String selection(String alias) {
        String prefix = alias == null || alias.isBlank() ? "" : alias + ".";
        StringBuilder sql = new StringBuilder();
        if (!"ALL".equals(scope)) sql.append(" AND ").append(prefix).append("scope='")
                .append(scope.replace("'", "''")).append("'");
        if (module != null) sql.append(" AND ").append(prefix).append("module='")
                .append(module.replace("'", "''")).append("'");
        return sql.toString();
    }

    private static void bind(PreparedStatement statement, List<String> values) throws SQLException {
        for (int i = 0; i < values.size(); i++) statement.setString(i + 1, values.get(i));
    }

    private static boolean matchesInstantiability(String filter, String state) {
        return "all".equals(filter) || filter.equals(state)
                || ("unknown".equals(filter) && "unknown".equals(state));
    }

    private static String semanticOf(String relation) {
        return GraphConstants.Relation.IMPLEMENTS.equals(relation) ? "conforms-to" : "subtype-of";
    }

    private static String mechanismOf(String relation, String subjectKind, String objectKind) {
        if (GraphConstants.Relation.IMPLEMENTS.equals(relation)) return "java.implements";
        if (GraphConstants.Relation.PERMITS.equals(relation)) return "java.permits";
        return "INTERFACE".equals(subjectKind) || "INTERFACE".equals(objectKind)
                ? "java.extends_interface" : "java.extends_class";
    }

    private static String mechanism(String dispatchKind, boolean exact) {
        if (exact) return switch (dispatchKind) {
            case "static" -> "java.static";
            case "super" -> "java.super";
            case "constructor" -> "java.constructor";
            default -> "java.exact_dispatch";
        };
        return "interface".equals(dispatchKind)
                ? "java.interface_dispatch" : "java.virtual_dispatch";
    }

    private static String origin(String confidence) {
        return "EXTRACTED".equals(confidence) ? "extracted" : "derived";
    }

    private static String resolution(String confidence, String resolution) {
        if ("AMBIGUOUS".equals(confidence)) return "ambiguous";
        if ("INFERRED".equals(confidence) || resolution != null) return "heuristic";
        return "exact";
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(java.util.Locale.ROOT);
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String stringOr(Object value, String fallback) {
        String text = string(value);
        return text == null || text.isBlank() ? fallback : text;
    }

    private record Instantiability(String state, String reason) {}
}
