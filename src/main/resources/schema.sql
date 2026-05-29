-- anatomist Phase 1 schema. Matches docs/scenario-1-index.md §完整 DDL.
-- documents / semantic_annotations tables belong to Phase 2 and are not created here.

CREATE TABLE nodes (
    id TEXT PRIMARY KEY,
    label TEXT NOT NULL,
    kind TEXT NOT NULL,
    qualified_name TEXT NOT NULL,
    package TEXT,
    source_file TEXT NOT NULL,
    source_location TEXT,
    module TEXT,
    scope TEXT NOT NULL DEFAULT 'MAIN',
    javadoc TEXT,
    metadata TEXT
);

CREATE INDEX idx_nodes_kind ON nodes(kind);
CREATE INDEX idx_nodes_qualified_name ON nodes(qualified_name);
CREATE INDEX idx_nodes_package ON nodes(package);
CREATE INDEX idx_nodes_source_file ON nodes(source_file);
CREATE INDEX idx_nodes_module ON nodes(module);
CREATE INDEX idx_nodes_scope ON nodes(scope);

CREATE TABLE edges (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    source_id TEXT NOT NULL REFERENCES nodes(id) ON DELETE CASCADE,
    target_id TEXT REFERENCES nodes(id) ON DELETE CASCADE,
    external_target_fqn TEXT,
    relation TEXT NOT NULL,
    call_kind TEXT,
    confidence TEXT NOT NULL DEFAULT 'EXTRACTED',
    context TEXT,
    is_external INTEGER NOT NULL DEFAULT 0,
    source_file TEXT,
    source_location TEXT,
    metadata TEXT,
    CHECK (
        (is_external = 0 AND target_id IS NOT NULL AND external_target_fqn IS NULL)
        OR
        (is_external = 1 AND target_id IS NULL AND external_target_fqn IS NOT NULL)
    )
);

CREATE INDEX idx_edges_source_id ON edges(source_id);
CREATE INDEX idx_edges_target_id ON edges(target_id);
CREATE INDEX idx_edges_external_target_fqn ON edges(external_target_fqn);
CREATE INDEX idx_edges_relation ON edges(relation);
CREATE INDEX idx_edges_call_kind ON edges(call_kind);
CREATE INDEX idx_edges_source_relation ON edges(source_id, relation);
CREATE INDEX idx_edges_target_relation ON edges(target_id, relation);
CREATE INDEX idx_edges_relation_external_target ON edges(relation, is_external, target_id);
CREATE INDEX idx_edges_relation_external_fqn ON edges(relation, is_external, external_target_fqn);

CREATE TABLE annotations (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    node_id TEXT NOT NULL REFERENCES nodes(id) ON DELETE CASCADE,
    annotation_fqn TEXT NOT NULL,
    attributes TEXT
);

CREATE INDEX idx_annotations_node_id ON annotations(node_id);
CREATE INDEX idx_annotations_fqn ON annotations(annotation_fqn);

CREATE VIRTUAL TABLE node_names USING fts5(
    qualified_name,
    label,
    javadoc,
    content='nodes',
    content_rowid='rowid'
);

CREATE TRIGGER nodes_ai AFTER INSERT ON nodes BEGIN
    INSERT INTO node_names(rowid, qualified_name, label, javadoc)
    VALUES (new.rowid, new.qualified_name, new.label, new.javadoc);
END;

CREATE TRIGGER nodes_ad AFTER DELETE ON nodes BEGIN
    INSERT INTO node_names(node_names, rowid, qualified_name, label, javadoc)
    VALUES ('delete', old.rowid, old.qualified_name, old.label, old.javadoc);
END;

CREATE TRIGGER nodes_au AFTER UPDATE ON nodes BEGIN
    INSERT INTO node_names(node_names, rowid, qualified_name, label, javadoc)
    VALUES ('delete', old.rowid, old.qualified_name, old.label, old.javadoc);
    INSERT INTO node_names(rowid, qualified_name, label, javadoc)
    VALUES (new.rowid, new.qualified_name, new.label, new.javadoc);
END;
