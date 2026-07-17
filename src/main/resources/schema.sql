-- anatomist schema. Phase 1 covers nodes/edges/annotations + node_names FTS5.
-- Phase 2 adds documents / doc_content FTS5 / semantic_annotations.

CREATE TABLE nodes (
    id TEXT PRIMARY KEY,
    symbol_id TEXT NOT NULL,
    label TEXT NOT NULL,
    kind TEXT NOT NULL,
    qualified_name TEXT NOT NULL,
    package TEXT,
    source_file TEXT NOT NULL,
    source_location TEXT,
    module TEXT NOT NULL,
    scope TEXT NOT NULL CHECK (scope IN ('MAIN','TEST','GENERATED')),
    javadoc TEXT,
    metadata TEXT
);

CREATE INDEX idx_nodes_kind ON nodes(kind);
CREATE INDEX idx_nodes_symbol_id ON nodes(symbol_id);
CREATE INDEX idx_nodes_symbol_identity ON nodes(symbol_id,module,scope,kind);
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
CREATE INDEX idx_edges_source_file ON edges(source_file);
CREATE INDEX idx_edges_target_id ON edges(target_id);
CREATE INDEX idx_edges_external_target_fqn ON edges(external_target_fqn);
CREATE INDEX idx_edges_relation ON edges(relation);
CREATE INDEX idx_edges_call_kind ON edges(call_kind);
CREATE INDEX idx_edges_source_relation ON edges(source_id, relation);
CREATE INDEX idx_edges_target_relation ON edges(target_id, relation);
CREATE INDEX idx_edges_relation_external_target ON edges(relation, is_external, target_id);
CREATE INDEX idx_edges_relation_external_fqn ON edges(relation, is_external, external_target_fqn);
CREATE INDEX idx_edges_source_relation_external ON edges(source_id, relation, is_external);

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

CREATE TABLE documents (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    path TEXT NOT NULL,
    title TEXT,
    content TEXT,
    doc_type TEXT NOT NULL,
    module TEXT,
    indexed_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_documents_path ON documents(path);
CREATE INDEX idx_documents_doc_type ON documents(doc_type);
CREATE INDEX idx_documents_module ON documents(module);

CREATE VIRTUAL TABLE doc_content USING fts5(
    title,
    content,
    doc_type,
    content='documents',
    content_rowid='id'
);

CREATE TRIGGER documents_ai AFTER INSERT ON documents BEGIN
    INSERT INTO doc_content(rowid, title, content, doc_type)
    VALUES (new.id, new.title, new.content, new.doc_type);
END;

CREATE TRIGGER documents_ad AFTER DELETE ON documents BEGIN
    INSERT INTO doc_content(doc_content, rowid, title, content, doc_type)
    VALUES ('delete', old.id, old.title, old.content, old.doc_type);
END;

CREATE TRIGGER documents_au AFTER UPDATE ON documents BEGIN
    INSERT INTO doc_content(doc_content, rowid, title, content, doc_type)
    VALUES ('delete', old.id, old.title, old.content, old.doc_type);
    INSERT INTO doc_content(rowid, title, content, doc_type)
    VALUES (new.id, new.title, new.content, new.doc_type);
END;

CREATE TABLE semantic_annotations (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    node_id TEXT REFERENCES nodes(id) ON DELETE SET NULL,
    doc_id INTEGER REFERENCES documents(id) ON DELETE SET NULL,
    category TEXT,
    business_label TEXT,
    business_description TEXT,
    domain_context TEXT,
    source TEXT NOT NULL,
    confidence TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (source IN ('CONVENTION','JAVADOC','DOC','LLM')),
    CHECK (confidence IN ('HIGH','MEDIUM','LOW'))
);

CREATE INDEX idx_semantic_annotations_node_id ON semantic_annotations(node_id);
CREATE INDEX idx_semantic_annotations_doc_id ON semantic_annotations(doc_id);
CREATE INDEX idx_semantic_annotations_category ON semantic_annotations(category);
CREATE INDEX idx_semantic_annotations_source ON semantic_annotations(source);
CREATE UNIQUE INDEX idx_semantic_annotations_upsert_key ON semantic_annotations(node_id, category, source);

CREATE TABLE file_cache (
    source_file TEXT PRIMARY KEY,
    hash TEXT NOT NULL,
    schema_version INTEGER NOT NULL,
    last_indexed TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    node_count INTEGER NOT NULL DEFAULT 0,
    edge_count INTEGER NOT NULL DEFAULT 0,
    file_size INTEGER NOT NULL DEFAULT -1,
    file_mtime_ns INTEGER NOT NULL DEFAULT -1,
    contract_hash TEXT NOT NULL DEFAULT ''
);

CREATE INDEX idx_file_cache_schema_version ON file_cache(schema_version);

CREATE TABLE project_meta (
    key TEXT PRIMARY KEY,
    value TEXT
);

CREATE TABLE index_diagnostics (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    severity TEXT NOT NULL CHECK (severity IN ('info','warning','error')),
    code TEXT NOT NULL,
    phase TEXT NOT NULL,
    source_file TEXT,
    module TEXT,
    scope TEXT,
    symbol TEXT,
    occurrence_count INTEGER NOT NULL,
    sample TEXT
);

CREATE INDEX idx_diagnostics_severity ON index_diagnostics(severity);
CREATE INDEX idx_diagnostics_code ON index_diagnostics(code);
CREATE INDEX idx_diagnostics_source_file ON index_diagnostics(source_file);

CREATE TABLE analysis_coverage (
    source_file TEXT NOT NULL,
    module TEXT NOT NULL,
    scope TEXT NOT NULL,
    capability TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('complete','partial','failed')),
    occurrences INTEGER NOT NULL DEFAULT 0,
    groups_count INTEGER NOT NULL DEFAULT 0,
    codes TEXT NOT NULL,
    code_counts TEXT NOT NULL,
    details_truncated INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (source_file, module, scope, capability)
);

CREATE INDEX idx_analysis_coverage_capability
    ON analysis_coverage(capability, module, scope);
CREATE INDEX idx_analysis_coverage_source_file
    ON analysis_coverage(source_file);

CREATE TABLE file_dependencies (
    source_file TEXT NOT NULL,
    depends_on_file TEXT NOT NULL,
    PRIMARY KEY (source_file, depends_on_file)
);

CREATE INDEX idx_file_deps_target ON file_dependencies(depends_on_file);

CREATE TABLE flow_nodes (
    id TEXT PRIMARY KEY,
    method_id TEXT NOT NULL,
    kind TEXT NOT NULL,
    label TEXT,
    source_file TEXT NOT NULL,
    module TEXT NOT NULL,
    scope TEXT NOT NULL CHECK (scope IN ('MAIN','TEST','GENERATED')),
    line INTEGER NOT NULL DEFAULT 0,
    column_no INTEGER NOT NULL DEFAULT 0,
    callee_method TEXT,
    slot TEXT,
    metadata TEXT
);

CREATE INDEX idx_flow_nodes_method ON flow_nodes(method_id);
CREATE INDEX idx_flow_nodes_kind ON flow_nodes(kind);
CREATE INDEX idx_flow_nodes_source_file ON flow_nodes(source_file);
CREATE INDEX idx_flow_nodes_callee_kind ON flow_nodes(callee_method, kind);
CREATE INDEX idx_flow_nodes_method_kind_slot ON flow_nodes(method_id, kind, slot);

CREATE TABLE flow_edges (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    source_node TEXT NOT NULL REFERENCES flow_nodes(id) ON DELETE CASCADE,
    target_node TEXT NOT NULL REFERENCES flow_nodes(id) ON DELETE CASCADE,
    relation TEXT NOT NULL,
    method_id TEXT NOT NULL,
    source_file TEXT NOT NULL,
    confidence TEXT NOT NULL DEFAULT 'EXTRACTED',
    context TEXT,
    metadata TEXT
);

CREATE INDEX idx_flow_edges_source ON flow_edges(source_node);
CREATE INDEX idx_flow_edges_target ON flow_edges(target_node);
CREATE INDEX idx_flow_edges_relation ON flow_edges(relation);
CREATE INDEX idx_flow_edges_method ON flow_edges(method_id);
CREATE INDEX idx_flow_edges_source_file ON flow_edges(source_file);

CREATE TABLE method_flow_summaries (
    method_id TEXT NOT NULL,
    input_slot TEXT NOT NULL,
    output_slot TEXT NOT NULL,
    relation TEXT NOT NULL,
    source_file TEXT NOT NULL,
    confidence TEXT NOT NULL DEFAULT 'INFERRED',
    metadata TEXT,
    PRIMARY KEY (method_id, input_slot, output_slot, relation)
);

CREATE INDEX idx_flow_summaries_source_file ON method_flow_summaries(source_file);
CREATE INDEX idx_flow_summaries_method ON method_flow_summaries(method_id);

CREATE TABLE method_flow_coverage (
    method_id TEXT PRIMARY KEY,
    source_file TEXT NOT NULL,
    detail_level TEXT NOT NULL CHECK (detail_level IN ('SUMMARY','DETAIL'))
);

CREATE INDEX idx_flow_coverage_source_file ON method_flow_coverage(source_file);
CREATE INDEX idx_flow_coverage_detail ON method_flow_coverage(detail_level);
