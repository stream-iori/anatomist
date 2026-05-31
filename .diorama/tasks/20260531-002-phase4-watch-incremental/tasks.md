# Tasks: Phase 4 Watch + 增量更新

**PRD**: [design.md](./design.md)
**Branch**: task/20260531-002-phase4-watch-incremental

> generate 阶段中断恢复时，CodingAgent 应从第一个未完成的 `Status` 或未勾选项继续。

## 技术评估

- 开发入口: src/main/java/com/anatomist/cli/IndexCommand.java, src/main/java/com/anatomist/store/SqliteStore.java, src/main/resources/schema.sql
- 验收入口: src/test/java/com/anatomist/incremental/IncrementalIndexerIT.java, src/test/java/com/anatomist/cli/WatchCommandIT.java
- SUT 边界: FileCacheService(纯文件系统+hash) + IncrementalIndexer(协调解析器+DB) + WatchCommand(WatchService+debounce) + SqliteStore(新CRUD方法)
- 锚点说明: IndexCommand 是增量索引的入口改造点，SqliteStore 是数据层扩展点，IncrementalIndexerIT 和 WatchCommandIT 分别覆盖增量逻辑和监控逻辑的端到端验收

## 任务清单

### T1: Schema + Model + SqliteStore 扩展 [REQ-001, REQ-002, REQ-003]

**Status**: [x] done

#### Phase 1: Skeleton

- [x] FileCacheEntry — record — file_cache 行模型(sourceFile, hash, schemaVersion, lastIndexed, nodeCount, edgeCount, stale, staleReason)
- [x] schema.sql 追加: file_cache 表 + project_meta 表 + file_dependencies 表 + 索引

**Gate**: `mvn compile -q` — exit 0

#### Phase 2: DSL Test

- [x] SqliteStoreWriteTest#testFileCacheCrud — 场景 S11 — 核心断言: 写入 file_cache 后读取与写入一致
- [x] SqliteStoreWriteTest#testProjectMetaCrud — 核心断言: 写入 project_meta 后读取与写入一致
- [x] SqliteStoreWriteTest#testFileDependenciesDerivation — 场景 S6 — 核心断言: 从 edges 推导跨文件依赖
- [x] SqliteStoreWriteTest#testDeleteBySourceFiles — 核心断言: 按文件删除 nodes 时 CASCADE 清理 edges/annotations，显式清理 semantic_annotations
- [x] SqliteStoreWriteTest#testMarkStaleDependents — 场景 S7 — 核心断言: 依赖方 stale=1 + stale_reason 非空

**Gate**: `mvn test-compile -q && mvn test -Dtest="SqliteStoreWriteTest#testFileCacheCrud+testProjectMetaCrud+testFileDependenciesDerivation+testDeleteBySourceFiles+testMarkStaleDependents" -q` — ① test-compile exit 0 ② test fails with AssertionError (红灯)

#### Phase 3: Implementation

- [x] SqliteStore 新增 deleteBySourceFiles(List<String>): 先删 semantic_annotations (WHERE node_id IN SELECT)，再删 nodes (CASCADE 清理 edges/annotations)
- [x] SqliteStore 新增 updateFileCache(List<FileCacheEntry>): INSERT OR REPLACE
- [x] SqliteStore 新增 readFileCache(): 返回 Map<String, FileCacheEntry>
- [x] SqliteStore 新增 readProjectMeta(String key): 返回 Optional<String>
- [x] SqliteStore 新增 upsertProjectMeta(String key, String value)
- [x] SqliteStore 新增 deriveFileDependencies(): 从 edges 表 INSERT INTO file_dependencies SELECT DISTINCT...
- [x] SqliteStore 新增 markStaleDependents(List<String>): 查 file_dependencies 反向标记 stale
- [x] SqliteStore 新增 clearFileDependencies(): DELETE FROM file_dependencies (重索引前清空)

**Gate**: `mvn test -Dtest=SqliteStoreWriteTest -q` — exit 0, all tests green

---

### T2: FileCacheService + 增量索引 + IndexCommand 改造 [REQ-004, REQ-005, REQ-006, REQ-007]

**Status**: [x] done

#### Phase 1: Skeleton

- [x] FileCacheService — class — SHA-256 hash 计算 + file_cache 对比 + 变更检测(changed/new/deleted)
- [x] IncrementalIndexer — class — 增量重解析协调(复用 JavaParserFactory + Extractors + SemanticPostProcessor)
- [x] IndexCommand 新增 --incremental / --full 选项 (picocli @Option)

**Gate**: `mvn compile -q` — exit 0

#### Phase 2: DSL Test

- [x] FileCacheServiceTest#testDetectChangedFiles — 场景 S1 — 核心断言: hash 不同的文件出现在 changed 列表
- [x] FileCacheServiceTest#testDetectNewFiles — 场景 S2 — 核心断言: 新文件出现在 new 列表
- [x] FileCacheServiceTest#testDetectDeletedFiles — 场景 S3 — 核心断言: 文件不存在时出现在 deleted 列表
- [x] IncrementalIndexerIT#testIncrementalModifyFile — 场景 S1 — 核心断言: 修改单个文件后增量索引，该文件 nodes 更新，其他文件不变
- [x] IncrementalIndexerIT#testIncrementalAddFile — 场景 S2 — 核心断言: 新增文件后增量索引，新文件 nodes 出现
- [x] IncrementalIndexerIT#testIncrementalDeleteFile — 场景 S3 — 核心断言: 删除文件后增量索引，该文件 nodes 消失
- [x] IncrementalIndexerIT#testIncrementalSchemaVersionDegradation — 场景 S4 — 核心断言: schema_version 不匹配时降级为全量
- [x] IncrementalIndexerIT#testIncrementalEmptyCacheDegradation — 场景 S5 — 核心断言: file_cache 为空时降级为全量
- [x] IncrementalIndexerIT#testStaleCascadeMarking — 场景 S7 — 核心断言: 修改被依赖文件后，依赖方 stale=1
- [x] IndexCommandIT 验证全量索引后 file_cache / project_meta / file_dependencies 非空 — 场景 S11

**Gate**: `mvn test-compile -q && mvn test -Dtest="FileCacheServiceTest" -q` — ① test-compile exit 0 ② test fails with AssertionError (红灯)

#### Phase 3: Implementation

- [x] FileCacheService: computeFileHashes(sourcePaths) — SHA-256 per file
- [x] FileCacheService: detectChanges(projectRoot, fileCache) — 对比 disk hashes vs file_cache → changed/new/deleted
- [x] IncrementalIndexer: indexIncremental(projectRoot, changedFiles, newFiles, deletedFiles) — 单事务内 DELETE + re-extract + INSERT + update file_cache + derive file_dependencies + mark stale
- [x] IndexCommand: --incremental 分支委托 IncrementalIndexer
- [x] IndexCommand: 默认全量流程改为清表(DELETE FROM nodes WHERE 1=1 等)而非 Files.deleteIfExists(dbPath)，索引完成后写入 file_cache / project_meta / file_dependencies
- [x] IndexCommand: --full 显式全量(语义与默认一致)
- [x] IndexCommand: stdout 新增 "File cache: <n> entries" 行
- [x] IndexCommandIT: 断言 file_cache 行数 = 源文件数；project_meta 含 java_version/classpath_hash

**Gate**: `mvn test -Dtest="FileCacheServiceTest,IncrementalIndexerIT,IndexCommandIT" -q` — exit 0, all tests green

---

### T3: WatchCommand [REQ-008, REQ-009, REQ-010, REQ-011]

**Status**: [x] done

#### Phase 1: Skeleton

- [x] WatchCommand — class — picocli Callable，WatchService + debounce + 增量索引集成
- [x] AnatomistCli — 修改 — subcommands 新增 WatchCommand.class

**Gate**: `mvn compile -q` — exit 0

#### Phase 2: DSL Test

- [x] WatchCommandIT#testWatchDetectsModification — 场景 S8 — 核心断言: 修改文件后 watch 输出包含 [MODIFY] 行
- [x] WatchCommandIT#testWatchAutoIndex — 场景 S9 — 核心断言: --auto-index 模式下修改文件触发增量索引输出
- [x] WatchCommandIT#testWatchPomChangeTriggersFullReindex — 场景 S10 — 核心断言: pom.xml 变更导致全量重索引
- [x] WatchCommandIT#testWatchExtensionsFilter — 场景 S8 变体 — 核心断言: --extensions ".java" 过滤 .xml 文件事件

**Gate**: `mvn test-compile -q && mvn test -Dtest="WatchCommandIT#testWatchDetectsModification" -q` — ① test-compile exit 0 ② test fails with AssertionError (红灯)

#### Phase 3: Implementation

- [x] WatchCommand: WatchService 注册源码目录监听
- [x] WatchCommand: 500ms ScheduledExecutorService 防抖
- [x] WatchCommand: 变更事件收集 → hash 对比 → 输出变更摘要
- [x] WatchCommand: --auto-index 模式调用 IncrementalIndexer
- [x] WatchCommand: --extensions 参数解析，过滤非目标扩展名
- [x] WatchCommand: pom.xml / build.gradle 变更检测 → classpath hash 比较 → 触发全量重索引
- [x] WatchCommand: Ctrl+C (SIGINT) 优雅关闭
- [x] AnatomistCli: subcommands 新增 WatchCommand.class

**Gate**: `mvn test -Dtest="WatchCommandIT" -q` — exit 0, all tests green
