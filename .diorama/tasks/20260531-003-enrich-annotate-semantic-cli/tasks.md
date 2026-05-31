# Tasks: 20260531-003-enrich-annotate-semantic-cli

**Source**: [design.md](./design.md)

## 技术评估

### 开发入口

- `src/main/java/com/anatomist/cli/AnatomistCli.java` — 注册新子命令
- `src/main/java/com/anatomist/query/QueryService.java` — enrich 查询逻辑
- `src/main/java/com/anatomist/store/SqliteStore.java` — annotate 写入逻辑
- `src/main/resources/schema.sql` — UNIQUE 索引

### 验收入口

- `anatomist enrich --node OrderService` — REQ-001, REQ-003
- `anatomist enrich --package com.example.service` — REQ-002
- `anatomist enrich --node OrderService --format json` — REQ-003
- `anatomist annotate <node-id> --label ... --category ...` — REQ-004, REQ-005
- `anatomist annotate ... --source CONVENTION` (expect exit 1) — BR-005
- `anatomist annotate --from-json batch.json` — REQ-007
- 现有 golden file IT 不变 — AC-009

---

## T1: DTO + Schema + Store upsert [REQ-004, REQ-006, BR-005]

新增 EnrichResult / DocSnippet / SemanticAnnotationRow DTO；schema.sql 加 UNIQUE 索引；SqliteStore 加 upsert 语义。

### T1 Phase 1: Skeleton

新增文件：
- `src/main/java/com/anatomist/query/EnrichResult.java` — 聚合结果 DTO（node, members, annotations, semanticAnnotations, callees, relatedDocs, suggestedQueries）
- `src/main/java/com/anatomist/query/DocSnippet.java` — 文档片段 DTO（title, path, snippet, docType）
- `src/main/java/com/anatomist/query/SemanticAnnotationRow.java` — 语义注解行 DTO（category, businessLabel, businessDescription, domainContext, source, confidence）
- 修改 `src/main/resources/schema.sql` — 追加 `CREATE UNIQUE INDEX idx_semantic_annotations_upsert_key ON semantic_annotations(node_id, category, source)`

**Gate**: `mvn compile -q` — exit 0

### T1 Phase 2: DSL Test

新增测试：
- `src/test/java/com/anatomist/store/SqliteStoreUpsertTest.java` — 验证 upsertSemanticAnnotation 语义（插入 → 查询 → 再插入同 key → 查询确认更新而非重复）

**Gate**: `mvn test-compile -q && mvn test -Dtest=SqliteStoreUpsertTest -q` — ① test-compile exit 0 ② test fails with AssertionError（红灯；upsert 方法尚未实现）

### T1 Phase 3: Implementation

实现内容：
- `SqliteStore.upsertSemanticAnnotation(SemanticAnnotation)` — DELETE WHERE node_id=? AND category=? AND source=?; INSERT
- `SqliteStore.upsertSemanticAnnotations(List<SemanticAnnotation>)` — 批量版本，事务内逐条 upsert

**Gate**: `mvn test -Dtest=SqliteStoreUpsertTest -q` — exit 0, all green

**Status**: [x] done

---

## T2: QueryService enrich 查询 [REQ-001, REQ-002, BR-001, BR-003, BR-007]

在 QueryService 中实现 enrichNode 和 enrichPackage 查询，复用现有 resolveNodeRow / callsFrom / packageDeps 等方法，新增 readSemanticAnnotations 和 searchRelatedDocs。

### T2 Phase 1: Skeleton

新增方法签名（抛 UnsupportedOperationException）：
- `QueryService.enrichNode(String fqnOrShorthand, int depth, boolean withDocs)` → EnrichResult
- `QueryService.enrichPackage(String pkg, boolean withDocs)` → EnrichResult
- `QueryService.readSemanticAnnotations(String nodeId)` → List\<SemanticAnnotationRow\>
- `QueryService.searchRelatedDocs(String label, String qualifiedName)` → List\<DocSnippet\>
- `QueryService.suggestQueries(EnrichResult)` → List\<String\>

**Gate**: `mvn compile -q` — exit 0

### T2 Phase 2: DSL Test

新增测试：
- `src/test/java/com/anatomist/query/EnrichQueryIT.java` — 对 mini-spring-shop fixture 验证 enrichNode 返回非空结果、enrichPackage 返回包内类型、--with-docs 匹配文档片段

**Gate**: `mvn test-compile -q && mvn test -Dtest=EnrichQueryIT -q` — ① test-compile exit 0 ② test fails with AssertionError

### T2 Phase 3: Implementation

实现内容：
- `enrichNode`: resolveNodeRow → context(members+annotations) → callsFrom(callees) → readSemanticAnnotations → searchRelatedDocs → suggestQueries → 组装 EnrichResult
- `enrichPackage`: 查询 package=? 的所有类型节点 → 每个类型读 semanticAnnotations + annotations → 组装包级概要 + packageDeps 过滤 + suggestQueries
- `readSemanticAnnotations`: SELECT from semantic_annotations WHERE node_id=?
- `searchRelatedDocs`: doc_content FTS5 MATCH label/qualifiedName, LIMIT 3, 截取前 200 字符
- `suggestQueries`: 基于 node.kind / annotations / semanticAnnotations 推导静态建议（CLASS → callees-of/callers-of/deps-of; METHOD → callers-of/callees-of; 有 BUSINESS_SERVICE → context of related types 等）

**Gate**: `mvn test -Dtest=EnrichQueryIT -q` — exit 0, all green

**Status**: [x] done

---

## T3: MarkdownFormatter + EnrichCommand CLI [REQ-001, REQ-002, REQ-003, BR-002]

实现 markdown 格式化器和 enrich CLI 命令，注册到 AnatomistCli。

### T3 Phase 1: Skeleton

新增文件：
- `src/main/java/com/anatomist/query/MarkdownFormatter.java` — static format(EnrichResult) → String
- `src/main/java/com/anatomist/cli/EnrichCommand.java` — picocli 命令，--node/--package 互斥组，--format markdown|json，--with-docs，--depth（默认 1），--index

修改文件：
- `src/main/java/com/anatomist/cli/AnatomistCli.java` — subcommands 加入 EnrichCommand.class

**Gate**: `mvn compile -q` — exit 0

### T3 Phase 2: DSL Test

新增测试：
- `src/test/java/com/anatomist/query/MarkdownFormatterTest.java` — 验证 markdown 输出包含关键段落标题、200 行截断逻辑
- `src/test/java/com/anatomist/cli/EnrichCommandIT.java` — CLI 端到端：enrich --node / --package / --format json / --with-docs

**Gate**: `mvn test-compile -q && mvn test -Dtest=MarkdownFormatterTest -q` — ① test-compile exit 0 ② test fails

### T3 Phase 3: Implementation

实现内容：
- `MarkdownFormatter.formatNode(EnrichResult)` — 段落：# label (kind) → 概要表 → Fields → Methods → Semantic Annotations 表 → Call Graph → Related Documentation → Suggested Queries；callees 超 200 行截断
- `MarkdownFormatter.formatPackage(EnrichResult)` — 段落：# package → 类型概要表 → Package Dependencies → Related Documentation → Suggested Queries
- `EnrichCommand.call()` — 解析参数 → QueryService.enrichNode/enrichPackage → formatMarkdown/formatJson → stdout

**Gate**: `mvn test -Dtest=MarkdownFormatterTest,EnrichCommandIT -q` — exit 0, all green

**Status**: [x] done

---

## T4: AnnotateCommand CLI [REQ-004, REQ-005, REQ-006, REQ-007, BR-004, BR-005, BR-006]

实现 annotate CLI 命令，含 source 校验和 --from-json 批量写入。

### T4 Phase 1: Skeleton

新增文件：
- `src/main/java/com/anatomist/cli/AnnotateCommand.java` — picocli 命令，<node-id> 参数，--label, --category 必填，--context, --source(默认 LLM), --confidence(默认 MEDIUM), --from-json, --index

修改文件：
- `src/main/java/com/anatomist/cli/AnatomistCli.java` — subcommands 加入 AnnotateCommand.class

**Gate**: `mvn compile -q` — exit 0

### T4 Phase 2: DSL Test

新增测试：
- `src/test/java/com/anatomist/cli/AnnotateCommandIT.java` — 验证单条写入、upsert、--from-json 批量、source=CONVENTION 拒绝

**Gate**: `mvn test-compile -q && mvn test -Dtest=AnnotateCommandIT -q` — ① test-compile exit 0 ② test fails

### T4 Phase 3: Implementation

实现内容：
- `AnnotateCommand.call()` — 校验 source ∈ {DOC, LLM}（否则 stderr + exit 1）→ 构造 SemanticAnnotation → SqliteStore.upsertSemanticAnnotation → 输出摘要
- `--from-json` 路径：读取 JSON 文件 → Jackson 解析为 List<SemanticAnnotation> → 逐条校验 + upsert → 输出摘要
- node-id 不存在时：stderr 警告 "node not found, annotation created with dangling node_id"（semantic_annotations.node_id 是 FK ON DELETE SET NULL，允许写入不存在的 node_id 因为 FK 在 node 被删除时才 SET NULL）

**Gate**: `mvn test -Dtest=AnnotateCommandIT -q` — exit 0, all green

**Status**: [x] done

---

## T5: anatomist-skill.md 工作流更新 [REQ-008]

在 anatomist-skill.md 增加 "Writing architecture docs from code" 工作流节。

### T5 Phase 1: Skeleton

无代码骨架，直接编辑 anatomist-skill.md。

**Gate**: 无（文档任务）

### T5 Phase 2: DSL Test

无自动化测试（手工审阅）。

**Gate**: 无

### T5 Phase 3: Implementation

在 anatomist-skill.md 末尾追加 "Writing architecture docs from code" 节，包含：
- 工作流模板：enrich → LLM 推理 → annotate → 验证闭环
- 示例：从 OrderService enrich → 识别业务角色 → annotate 写回
- 与现有 callers-of / context / index-docs 的组合用法

**Gate**: 人工审阅 anatomist-skill.md 内容完整性

**Status**: [ ] done

---

## T6: 最终 Gate + 全量回归 [AC-009, AC-010]

确保所有新增 + 现有测试全绿，enrich markdown 行数 ≤ 200。

### T6 Phase 1: Skeleton

无。

### T6 Phase 2: DSL Test

无。

### T6 Phase 3: Implementation

- 执行 `mvn test -q` — 全量回归
- 对 mini-spring-shop fixture 执行 `anatomist enrich --node OrderService` 计行数

**Gate**: `mvn test -q` — exit 0, all green

**Status**: [ ] done
