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
    begin_line INTEGER,
    begin_column INTEGER,
    end_line INTEGER,
    end_column INTEGER,
    source_ordinal INTEGER,
    module TEXT NOT NULL,
    scope TEXT NOT NULL CHECK (scope IN ('MAIN','TEST','GENERATED')),
    javadoc TEXT,
    metadata TEXT,
    producer_id TEXT NOT NULL DEFAULT 'java-core',
    CHECK (
        (begin_line IS NULL AND begin_column IS NULL AND end_line IS NULL AND end_column IS NULL)
        OR
        (begin_line > 0 AND begin_column > 0 AND end_line > 0 AND end_column > 0
         AND (end_line > begin_line OR (end_line = begin_line AND end_column >= begin_column)))
    )
);

CREATE INDEX idx_nodes_kind ON nodes(kind);
CREATE INDEX idx_nodes_symbol_id ON nodes(symbol_id);
CREATE INDEX idx_nodes_symbol_identity ON nodes(symbol_id,module,scope,kind);
CREATE INDEX idx_nodes_qualified_name ON nodes(qualified_name);
CREATE INDEX idx_nodes_package ON nodes(package);
CREATE INDEX idx_nodes_source_file ON nodes(source_file);
CREATE INDEX idx_nodes_module ON nodes(module);
CREATE INDEX idx_nodes_scope ON nodes(scope);
CREATE INDEX idx_nodes_producer_file ON nodes(producer_id, source_file);

CREATE TABLE declarations (
    symbol_id TEXT NOT NULL,
    qualified_name TEXT NOT NULL,
    label TEXT NOT NULL,
    kind TEXT NOT NULL,
    declaration_kind TEXT NOT NULL CHECK (declaration_kind IN ('type','method','constructor')),
    type_kind TEXT CHECK (type_kind IS NULL OR type_kind IN ('class','interface','enum','record','annotation')),
    visibility TEXT NOT NULL CHECK (visibility IN ('public','protected','private','package')),
    modifiers TEXT NOT NULL,
    declared_modifiers TEXT NOT NULL,
    implicit_modifiers TEXT NOT NULL,
    declaring_type TEXT,
    source_file TEXT NOT NULL,
    source_location TEXT,
    begin_line INTEGER,
    begin_column INTEGER,
    end_line INTEGER,
    end_column INTEGER,
    module TEXT NOT NULL,
    scope TEXT NOT NULL CHECK (scope IN ('MAIN','TEST','GENERATED')),
    nesting_depth INTEGER NOT NULL,
    direct_member INTEGER NOT NULL,
    synthetic INTEGER NOT NULL DEFAULT 0,
    binding_resolved INTEGER NOT NULL DEFAULT 1,
    producer_id TEXT NOT NULL DEFAULT 'java-core',
    CHECK (
        (begin_line IS NULL AND begin_column IS NULL AND end_line IS NULL AND end_column IS NULL)
        OR
        (begin_line > 0 AND begin_column > 0 AND end_line > 0 AND end_column > 0
         AND (end_line > begin_line OR (end_line = begin_line AND end_column >= begin_column)))
    ),
    PRIMARY KEY (symbol_id,module,scope,source_file,producer_id)
);

CREATE INDEX idx_declarations_file ON declarations(source_file,module,scope);
CREATE INDEX idx_declarations_filters ON declarations(declaration_kind,visibility,synthetic);
CREATE INDEX idx_declarations_owner ON declarations(declaring_type);
CREATE INDEX idx_declarations_producer_file ON declarations(producer_id, source_file);

CREATE TABLE edges (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    source_id TEXT NOT NULL REFERENCES nodes(id) ON DELETE CASCADE,
    target_id TEXT REFERENCES nodes(id) ON DELETE CASCADE,
    external_target_fqn TEXT,
    relation TEXT NOT NULL,
    call_kind TEXT,
    confidence TEXT NOT NULL DEFAULT 'EXTRACTED',
    resolution TEXT,
    context TEXT,
    is_external INTEGER NOT NULL DEFAULT 0,
    source_file TEXT,
    source_location TEXT,
    begin_line INTEGER,
    begin_column INTEGER,
    end_line INTEGER,
    end_column INTEGER,
    source_ordinal INTEGER,
    syntax_target TEXT,
    receiver_static_type TEXT,
    metadata TEXT,
    producer_id TEXT NOT NULL DEFAULT 'java-core',
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
CREATE INDEX idx_edges_external_resolution ON edges(is_external, resolution);
CREATE INDEX idx_edges_source_relation_external ON edges(source_id, relation, is_external);
CREATE INDEX idx_edges_producer_file ON edges(producer_id, source_file);

-- Canonical source call sites. Targets are separate because one syntax site may
-- have several static resolution candidates.
CREATE TABLE call_sites (
    site_pk INTEGER PRIMARY KEY,
    stable_hash BLOB NOT NULL UNIQUE CHECK (length(stable_hash) = 32),
    caller_id TEXT NOT NULL REFERENCES nodes(id) ON DELETE CASCADE,
    source_file TEXT NOT NULL,
    begin_line INTEGER NOT NULL,
    begin_column INTEGER NOT NULL,
    end_line INTEGER NOT NULL,
    end_column INTEGER NOT NULL,
    ordinal INTEGER NOT NULL DEFAULT 0,
    syntax_target TEXT,
    receiver_static_type TEXT,
    dispatch_kind TEXT,
    origin TEXT NOT NULL,
    resolution_status TEXT NOT NULL,
    producer_id TEXT NOT NULL
);

CREATE INDEX idx_call_sites_caller_order ON call_sites(
    caller_id,source_file,begin_line,begin_column,ordinal
);
CREATE INDEX idx_call_sites_source ON call_sites(
    source_file,begin_line,begin_column,ordinal
);

CREATE TABLE call_site_targets (
    call_site_pk INTEGER NOT NULL REFERENCES call_sites(site_pk) ON DELETE CASCADE,
    target_id TEXT REFERENCES nodes(id) ON DELETE CASCADE,
    external_target_fqn TEXT,
    resolution_status TEXT NOT NULL,
    confidence TEXT,
    producer_id TEXT NOT NULL,
    CHECK ((target_id IS NOT NULL) <> (external_target_fqn IS NOT NULL))
);

CREATE INDEX idx_call_site_targets_internal ON call_site_targets(target_id);
CREATE INDEX idx_call_site_targets_external ON call_site_targets(external_target_fqn);
CREATE UNIQUE INDEX idx_call_site_targets_identity ON call_site_targets(
    call_site_pk,COALESCE(target_id,''),COALESCE(external_target_fqn,'')
);

CREATE TABLE annotations (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    node_id TEXT NOT NULL REFERENCES nodes(id) ON DELETE CASCADE,
    annotation_fqn TEXT NOT NULL,
    attributes TEXT,
    source_file TEXT,
    producer_id TEXT NOT NULL DEFAULT 'java-core'
);

CREATE INDEX idx_annotations_node_id ON annotations(node_id);
CREATE INDEX idx_annotations_fqn ON annotations(annotation_fqn);
CREATE INDEX idx_annotations_producer_file ON annotations(producer_id, source_file);

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
    source_file TEXT,
    producer_id TEXT NOT NULL DEFAULT 'manual-annotation',
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (source IN ('CONVENTION','JAVADOC','DOC','LLM')),
    CHECK (confidence IN ('HIGH','MEDIUM','LOW'))
);

CREATE INDEX idx_semantic_annotations_node_id ON semantic_annotations(node_id);
CREATE INDEX idx_semantic_annotations_doc_id ON semantic_annotations(doc_id);
CREATE INDEX idx_semantic_annotations_category ON semantic_annotations(category);
CREATE INDEX idx_semantic_annotations_source ON semantic_annotations(source);
CREATE INDEX idx_semantic_annotations_producer_file ON semantic_annotations(producer_id, source_file);
CREATE UNIQUE INDEX idx_semantic_annotations_upsert_key
    ON semantic_annotations(node_id, category, source, producer_id);

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
