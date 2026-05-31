# Tasks: Phase 2 语义层 + 文档索引层

**Source**: [design.md](./design.md)
**Anchors**: 见 task.json `anchors`

## 技术评估

### 开发入口
- `src/main/java/com/anatomist/cli/IndexCommand.java` — 在 `pruneDanglingInternalEdges()` 之后、`store.write()` 之前接入 `SemanticPostProcessor.process(result)`；统计输出新增 `Semantic annotations:` 行
- `src/main/resources/schema.sql` — 追加 `documents` / `doc_content` / `semantic_annotations` DDL + 触发器 + 索引
- `src/main/java/com/anatomist/store/SqliteStore.java` — 新增 `insertSemanticAnnotations` / `insertDocuments`，并在 `write()` 中调用前者
- `src/main/java/com/anatomist/cli/AnatomistCli.java` — `subcommands` 数组新增 `IndexDocsCommand.class`

### 验收入口
- `src/test/java/com/anatomist/cli/IndexCommandIT.java` — 新增 `semantic_annotations` 行数断言 + stdout 统计行断言 + Phase 1 baseline 零回归
- `src/test/java/com/anatomist/store/SqliteStoreWriteTest.java` — 新增 semantic_annotations / documents 写入场景
- 新增 `src/test/java/com/anatomist/semantic/SemanticPostProcessorTest.java` — 11 条 CONVENTION 规则 + JAVADOC 提炼参数化
- 新增 `src/test/java/com/anatomist/cli/IndexDocsCommandIT.java` — README / DOC / ADR 三种场景 + FTS5 MATCH 查询

## 任务清单

### T1: Schema 扩展 + Model 类 + SqliteStore 写入 [REQ-001, REQ-002, REQ-003, BR-001, BR-002, BR-006, AC-001, AC-002, AC-003]

**Status**: [x] done

新增 `SemanticAnnotation` / `Document` model 类；`ExtractionResult` 新增 `List<SemanticAnnotation> semanticAnnotations` 字段；`schema.sql` 追加 `documents` 表 + `doc_content` FTS5 虚拟表(external content)+ ai/ad/au 三触发器 + `semantic_annotations` 表(含 FK ON DELETE SET NULL)+ 必要索引；`SqliteStore` 新增 `insertSemanticAnnotations(...)` 和 `insertDocuments(...)`，并在 `write()` 末尾调用 `insertSemanticAnnotations`。

- **Phase 1 (Skeleton)**: 新建 `SemanticAnnotation.java` / `Document.java` 字段壳；`ExtractionResult` 加字段 + getter；`SqliteStore` 加方法签名(空实现)；`schema.sql` 追加表/触发器/索引 DDL
  - **Gate**: `mvn compile -q` — exit 0
- **Phase 2 (DSL Test)**: `SqliteStoreWriteTest` 新增 `writeSemanticAnnotations_persistsRows` / `insertDocuments_persistsRowsAndSyncsFts` 两个红灯测试，断言 `semantic_annotations` 行数与 `doc_content` FTS5 MATCH 命中
  - **Gate**: `mvn test-compile -q && mvn test -Dtest=SqliteStoreWriteTest -q` — ① test-compile exit 0 ② test fails with AssertionError(红灯；编译失败不算红灯)
- **Phase 3 (Implementation)**: 实现 `SqliteStore.insertSemanticAnnotations` / `insertDocuments`(批量预编译 + 同事务)；`SqliteStore.SCHEMA_SPLITTER` 已 `BEGIN..END`-aware，无需改动
  - **Gate**: `mvn test -Dtest=SqliteStoreWriteTest,SqliteStoreInitSchemaTest -q` — exit 0, all tests green

### T2: SemanticPostProcessor — CONVENTION + JAVADOC 推导 [REQ-004, REQ-005, REQ-006, BR-003, BR-004, BR-005, BR-008, AC-004, AC-005, AC-006]

**Status**: [x] done

新建 `com.anatomist.semantic.SemanticPostProcessor`(纯 Java，输入 `ExtractionResult`，填充 `semanticAnnotations`)；内部含 `ConventionRule` 定义(record/enum) + 11 条规则常量(6 注解 + 5 命名)；`applyConventionRules` 遍历 `annotations` 表/`nodes.label` 命中即写入(source=CONVENTION, confidence=MEDIUM)；`applyJavadocRules` 遍历 `nodes.javadoc` 非空者，截取第一段(`firstBlankLineOrTag` 启发式)写入 `business_description`(source=JAVADOC, confidence=HIGH)。注解类规则只对持有该注解的 Node 生效；命名类规则仅对 `kind ∈ {CLASS, INTERFACE, ENUM, RECORD}` 生效。

- **Phase 1 (Skeleton)**: 新建 `SemanticPostProcessor.java` + `ConventionRule.java`(规则常量表 + 空 `process(ExtractionResult)` 方法)
  - **Gate**: `mvn compile -q` — exit 0
- **Phase 2 (DSL Test)**: 新建 `src/test/java/com/anatomist/semantic/SemanticPostProcessorTest.java`：① `@ParameterizedTest` 覆盖 11 条规则(`@Service`/`@Repository`/`@RestController`/`@Controller`/`@Entity`/`@Transactional`/`@Component` + `*Service`/`*DTO|*Request|*Response`/`*Repository|*Dao`/`*Controller`/`*Config|*Configuration`)；② `multiRulesHit_writesAllRecords`(S3)；③ `javadoc_extractsFirstParagraph`(S5) / `javadocNull_skipped`(S6)；④ `namingRule_skipsMethodAndField`(BR-004)；全部红灯
  - **Gate**: `mvn test-compile -q && mvn test -Dtest=SemanticPostProcessorTest -q` — ① test-compile exit 0 ② tests fail with AssertionError
- **Phase 3 (Implementation)**: 实现 `applyConventionRules` + `applyJavadocRules`；`firstBlankLineOrTag` 取首个空行或首个 `@param`/`@return`/`@throws` 等 javadoc tag 之前的内容并 `trim()`
  - **Gate**: `mvn test -Dtest=SemanticPostProcessorTest -q` — exit 0, all tests green

### T3: IndexCommand 接入 + 统计输出 [REQ-007, AC-007, AC-009, AC-010]

**Status**: [x] done

在 `IndexCommand.call()` 中 `pruneDanglingInternalEdges(result)` 之后、`store.write(result)` 之前调用 `new SemanticPostProcessor().process(result)`；统计行追加 `Semantic annotations: <n>`；`IndexCommandIT` 新增三项断言：① stdout 包含 `Semantic annotations:` ② `semantic_annotations` 行数 ≥ AC-010 基线 ③ Phase 1 baseline(16 types / 47 methods / 75 CONTAINS / LAMBDA≥1 / METHOD_REF≥1) 全部保留。

- **Phase 1 (Skeleton)**: `IndexCommand` 插入 `SemanticPostProcessor` 调用点 + 计数变量 + stdout 行；`IndexCommandIT` 加新断言方法签名占位(空 body 或 `fail()`)
  - **Gate**: `mvn compile -q` — exit 0
- **Phase 2 (DSL Test)**: `IndexCommandIT` 在既有 `index_writesAllExpectedRows`(或同名 IT)中追加上述三项断言，先红灯
  - **Gate**: `mvn test-compile -q && mvn test -Dtest=IndexCommandIT -q` — ① test-compile exit 0 ② test fails with AssertionError
- **Phase 3 (Implementation)**: 调通 `SemanticPostProcessor` 注入；统计行打印；fixture 上 `semantic_annotations` ≥ AC-010 基线
  - **Gate**: `mvn test -Dtest=IndexCommandIT -q` — exit 0, all tests green

### T4: DocScanner + IndexDocsCommand 子命令 [REQ-008, BR-007, BR-009, AC-008]

**Status**: [x] done

新建 `com.anatomist.doc.DocScanner`：`scan(Path projectRoot) → List<Document>`，匹配 `README.md` / `docs/**/*.md` / `**/ADR-*.md`，排除 `CHANGELOG.md` / `swagger*.json` / `openapi*.json`；title 取首个 `^# ` 行(正则手写，BR-009 不引依赖)，否则文件名 stem；doc_type 由路径模式判定(README/DOC/ADR)；module 取项目根之下首个目录段(`<module>/...`)，单模块为 null。新建 `com.anatomist.cli.IndexDocsCommand`(picocli `Callable<Integer>`，参数 `<path>` + `--output`)；`AnatomistCli.subcommands` 注册之。

- **Phase 1 (Skeleton)**: 新建 `DocScanner.java` / `IndexDocsCommand.java` 壳；`AnatomistCli` 注册子命令
  - **Gate**: `mvn compile -q` — exit 0
- **Phase 2 (DSL Test)**: 新建 `src/test/java/com/anatomist/cli/IndexDocsCommandIT.java`：① S7 README + title 解析 ② S8 ADR + 无 `#` 取 stem ③ S9 多模块 module 推断 ④ `doc_content` FTS5 MATCH 命中 ⑤ BR-007 排除 CHANGELOG/swagger；用 `@TempDir` 构造样例目录；全部红灯
  - **Gate**: `mvn test-compile -q && mvn test -Dtest=IndexDocsCommandIT -q` — ① test-compile exit 0 ② tests fail with AssertionError
- **Phase 3 (Implementation)**: 实现扫描 + title 正则 + doc_type/module 解析；命令调 `SqliteStore.initSchema()` + `insertDocuments(...)`
  - **Gate**: `mvn test -Dtest=IndexDocsCommandIT -q` — exit 0, all tests green

## 任务顺序与依赖

```text
T1 (Schema + Models + Store)
  → T2 (SemanticPostProcessor)        — 依赖 T1 的 SemanticAnnotation 模型
  → T3 (IndexCommand 接入)             — 依赖 T1 + T2
T1 → T4 (DocScanner + IndexDocsCommand) — 依赖 T1 的 documents 写入路径(可并行 T2/T3)
```

推荐顺序: T1 → T2 → T3 → T4(可与 T2/T3 并行，但建议串行以减少认知负担)。

## Coverage-Matrix Advisory

Plan phase-exit 前建议执行 `/diorama coverage-matrix`；遗漏维度若集中在依赖失败/可观测性，可在 T3/T4 Phase 3 顺手补一条 stdout 兜底分支。
