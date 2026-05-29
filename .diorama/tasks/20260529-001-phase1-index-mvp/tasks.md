# Tasks: 20260529-001-phase1-index-mvp

**PRD**: [design.md](./design.md)
**Branch**: task/20260529-001-phase1-index-mvp

> generate 阶段中断恢复时，CodingAgent 应从第一个未完成的 `Status` 或未勾选项继续。

## 技术评估

- 开发入口:
  - `src/main/java/com/anatomist/cli/IndexCommand.java#call()` — CLI 主入口
  - `src/main/java/com/anatomist/store/SqliteStore.java` — schema + 写入
  - `src/main/java/com/anatomist/core/JdtParserFactory.java` — JDT 解析编排
- 验收入口:
  - `src/test/java/com/anatomist/cli/IndexCommandIT.java` — 端到端集成测试(对 fixture 跑)
  - `src/test/java/com/anatomist/store/SqliteStoreTest.java` — schema/写入单测
  - `src/test/java/com/anatomist/extract/TypeExtractorTest.java`、`MethodExtractorTest.java` — Extractor 单测
  - `src/test/java/com/anatomist/core/ProjectScannerTest.java`、`JdtParserFactoryTest.java`、`ClasspathDetectorTest.java`
- SUT 边界: `com.anatomist.{cli,core,extract,store}` 中本期被实现的类(见 design.md §7 变更清单)。已有 `com.anatomist.model` 的 4 个数据类直接复用,不修改。其余 5 个 Extractor 保留 throw,不在边界内。
- 锚点说明: 单模块 Maven 项目,所有命令在仓库根目录执行(无 `-pl`)。集成测试自动指向 `fixtures/mini-spring-shop/service` 子目录。

## 任务清单

### T1: pom 加 JUnit + schema.sql + SqliteStore.initSchema [REQ-008, AC-003]

**Status**: [x] done

#### Phase 1: Skeleton

- [x] `pom.xml` — 修改 — 新增 `<dependencies>` 中 `org.junit.jupiter:junit-jupiter:5.10.2` (scope=test);`<build><plugins>` 新增 `maven-surefire-plugin:3.2.5`
- [x] `src/main/resources/schema.sql` — 新增 — 抄 scenario-1-index.md §完整 DDL 中的 nodes/edges/annotations/node_names + 触发器(documents/semantic_annotations 留 Phase 2,本期不建)
- [x] `src/main/java/com/anatomist/store/SqliteStore.java` — 修改 — `initSchema()`/`close()`/`write()` 保留签名;`initSchema()` 读 classpath 资源 schema.sql 按 `;` 拆分执行;`write()` 暂时仍 throw

**Gate**: `mvn -q compile test-compile` — exit 0 ✓

#### Phase 2: DSL Test

- [x] `src/test/java/com/anatomist/store/SqliteStoreInitSchemaTest.java#initSchema_createsExpectedTablesAndIndexes` — 场景 AC-003
- [x] 同文件 `#initSchema_createsFts5Triggers`

**Gate**: 测试直接通过(Skeleton 阶段顺带实现了 initSchema,因为该方法本质是加载 schema 文件,与 Skeleton 难以分离)

#### Phase 3: Implementation

- [x] `SqliteStore.initSchema()` 已实现(BEGIN..END 块感知的 SQL 拆分器)
- [x] `close()` 已实现

**Gate**: `mvn test -Dtest=SqliteStoreInitSchemaTest` — exit 0, 2/2 green ✓

---

### T2: ProjectScanner [REQ-003, AC-005]

**Status**: [x] done

#### Phase 1: Skeleton

- [x] `src/main/java/com/anatomist/core/ProjectScanner.java` — 修改 — 默认排除集合常量(target/build/.gradle/.git/.idea/node_modules);`scan(Path root)` 签名保留;新增 `scan(List<Path> roots)` 重载

**Gate**: `mvn -q compile` — exit 0 ✓

#### Phase 2: DSL Test

- [x] `ProjectScannerTest#scan_skipsDefaultExcludes`
- [x] `ProjectScannerTest#scan_appliesCustomExcludes`
- [x] `ProjectScannerTest#scan_ignoresSymlinks`

**Gate**: 同 T1, 测试随 Skeleton 直接通过(逻辑简单到 Skeleton 与 Impl 难以分阶段)

#### Phase 3: Implementation

- [x] `Files.walk` + 排除目录段过滤 + 符号链接默认不跟(`Files.walk` 默认不跟 symlink)

**Gate**: `mvn test -Dtest=ProjectScannerTest` — exit 0, 3/3 green ✓

---

### T3: ClasspathDetector + 降级 [REQ-002, REQ-010, REQ-012, AC-002]

**Status**: [x] done

#### Phase 1: Skeleton

- [x] `ClasspathDetector` 字段/seam 就位:`detect`/`detectSourcePaths`/`isMavenProject`/`runMvn` (protected, 可 override)

**Gate**: `mvn -q compile` — exit 0 ✓

#### Phase 2: DSL Test

- [x] `ClasspathDetectorTest#detect_returnsEmptyAndWarnsWhenMvnUnavailable`
- [x] `#detect_returnsEmptyForNonMavenProject`
- [x] `#detectSourcePaths_returnsSrcMainJavaForMavenProject`
- [x] `#detect_parsesClasspathFromMockedMvnOutput`

#### Phase 3: Implementation

- [x] `isMavenProject` = pom.xml 存在
- [x] `detect`: 调用 seam `runMvn`(timeout 60s),IOException/非零退出码 → stderr WARN + 返回空
- [x] `detectSourcePaths`: Maven → `src/main/java`(若存在);非 Maven → projectRoot

**Gate**: `mvn test -Dtest=ClasspathDetectorTest` — exit 0, 4/4 green ✓

---

### T4: NodeIdGenerator + ExtractionContext + JdtParserFactory [REQ-004, AC-006]

**Status**: [x] done

#### Phase 1: Skeleton

- [x] `NodeIdGenerator.java` — 新增 — forType/forMethod/forField
- [x] `ExtractionContext.java` — 新增 — projectRoot/sourcePaths/idGenerator/module/scope + isProjectInternal
- [x] `JdtParserFactory.java` — 修改 — newParser + parseAll(files, requestor)

**Gate**: `mvn -q compile` — exit 0 ✓

#### Phase 2: DSL Test

- [x] `NodeIdGeneratorTest#forType_preservesCase`
- [x] `NodeIdGeneratorTest#forMethod_usesErasedSignature` — 验证泛型擦除 + 大小写保留 + 重载消歧
- [x] `JdtParserFactoryTest#parseAll_resolvesCrossFileBindings` — A.java/B.java 跨文件 binding 解析

#### Phase 3: Implementation

- [x] forType 用 `binding.getErasure().getQualifiedName()`
- [x] forMethod 用 `IMethodBinding.getMethodDeclaration().getParameterTypes()[i].getErasure().getQualifiedName()` 拼接(而非 `getKey()`,避免跨 JDT 版本差异)
- [x] parseAll 用 `parser.createASTs(paths, encodings, ...)`(基于 path 形式,JDT 自动读文件)

**注解**: 单测 JdtParserFactoryTest 必须 `includeRunningVmClasspath=true` —— JDT 的 `createASTs` 需要系统库可寻址,否则报 "Missing system library"。生产侧 IndexCommand 默认 false(避免向 Java 8 项目注入新 API),测试关心的是跨文件 binding 而非 API 集,可放宽。

**Gate**: `mvn test -Dtest=NodeIdGeneratorTest,JdtParserFactoryTest` — exit 0, 3/3 green ✓

---

### T5: TypeExtractor [REQ-005, BR-001, BR-003, BR-005, BR-007, AC-004]

**Status**: [ ] done

#### Phase 1: Skeleton

- [ ] `src/main/java/com/anatomist/extract/TypeExtractor.java` — 修改 — 构造器 `TypeExtractor(ExtractionContext)`;`extract(unit, result)` 实现走 ASTVisitor

**Gate**: `mvn -q compile` — exit 0

#### Phase 2: DSL Test

- [ ] `src/test/java/com/anatomist/extract/TypeExtractorTest.java#extract_emitsClassNode` — 解析 `class Order {}` → 期望 1 个 CLASS Node,id=`pkg.Order`,kind=CLASS,metadata JSON 含 `isAbstract=false`、`isInterface=false`
- [ ] 同文件 `#extract_emitsInterfaceAndEnum` — 解析 `interface I {}` + `enum E { A, B }` → 期望 INTERFACE 和 ENUM 节点,ENUM metadata 含 `constants:["A","B"]`
- [ ] 同文件 `#extract_emitsNestedTypes` — 解析 `class A { class B {} }` → 两个 Node,id 分别 `pkg.A` 和 `pkg.A.B`
- [ ] 同文件 `#extract_skipsWhenBindingNull` — 通过 stub binding(或刻意制造解析错误)验证不产 Node

**Gate**: `mvn -q test-compile && mvn -q test -Dtest=TypeExtractorTest` — ① test-compile exit 0 ② 红灯

#### Phase 3: Implementation

- [ ] ASTVisitor:`visit(TypeDeclaration)`/`visit(EnumDeclaration)`,resolveBinding(),null → 跳过 + 计数(metadata "bindingResolved":"false" 可省,直接计数);否则生成 Node
- [ ] 填充 label/kind/qualifiedName/sourceFile/sourceLocation/module/scope/javadoc/metadata
- [ ] metadata 用 Jackson `ObjectMapper.writeValueAsString` 生成

**Gate**: `mvn -q test -Dtest=TypeExtractorTest` — exit 0

---

### T6: MethodExtractor + CONTAINS Edge [REQ-006, REQ-007, BR-002, BR-005, BR-007, AC-004]

**Status**: [ ] done

#### Phase 1: Skeleton

- [ ] `src/main/java/com/anatomist/extract/MethodExtractor.java` — 修改 — 构造器 `MethodExtractor(ExtractionContext)`;`extract(unit, result)` 实现

**Gate**: `mvn -q compile` — exit 0

#### Phase 2: DSL Test

- [ ] `src/test/java/com/anatomist/extract/MethodExtractorTest.java#extract_emitsMethodNodeAndContainsEdge` — 解析 `class A { void foo(){} }` → 1 个 METHOD Node + 1 条 CONTAINS Edge(A → A#foo())
- [ ] 同文件 `#extract_distinguishesOverloads` — `void foo()` + `void foo(String s)` → 2 个 METHOD Node,id 分别 `pkg.A#foo()` 和 `pkg.A#foo(java.lang.String)`
- [ ] 同文件 `#extract_handlesConstructorAndGenericList` — `class A { A(){} void bar(java.util.List<Integer> xs){} }` → 构造器 ID `pkg.A#A()`,bar 的 ID 含 `java.util.List`(擦除)

**Gate**: `mvn -q test-compile && mvn -q test -Dtest=MethodExtractorTest` — ① test-compile exit 0 ② 红灯

#### Phase 3: Implementation

- [ ] ASTVisitor:`visit(MethodDeclaration)`,resolveBinding(),null → 跳过;否则用 `NodeIdGenerator.forMethod` 生成 ID
- [ ] 同时写 CONTAINS Edge,source = decl class node id,target = method id,is_external=0,relation=CONTAINS
- [ ] metadata JSON: returnType / parameters[{name,type}] / modifiers / isConstructor / signature (人类可读)

**Gate**: `mvn -q test -Dtest=MethodExtractorTest` — exit 0

---

### T7: SqliteStore.write [REQ-008, REQ-009, AC-001]

**Status**: [ ] done

#### Phase 1: Skeleton

- [ ] `SqliteStore.write(ExtractionResult)` — 修改 — 保留签名;增加内部 PreparedStatement helper

**Gate**: `mvn -q compile` — exit 0

#### Phase 2: DSL Test

- [ ] `src/test/java/com/anatomist/store/SqliteStoreWriteTest.java#write_persistsNodesAndEdges` — 构造 ExtractionResult 含 2 Nodes(CLASS+METHOD)+ 1 CONTAINS Edge,写入临时 SQLite,断言 SELECT count 一致;断言 node_names FTS5 命中 label
- [ ] 同文件 `#write_isAtomic` — 注入一个 violate CHECK 约束的 Edge(`is_external=0, target_id=null`),期望抛异常且事务回滚(写入前后 nodes 表为空)
- [ ] 同文件 `#write_supportsIdempotentRewrite` — 同 ExtractionResult 写两次,期望第二次不抛(INSERT OR REPLACE),最终 nodes 数等于第一次

**Gate**: `mvn -q test-compile && mvn -q test -Dtest=SqliteStoreWriteTest` — ① test-compile exit 0 ② 红灯

#### Phase 3: Implementation

- [ ] `write`: 开事务,nodes/edges/annotations 用 `INSERT OR REPLACE` PreparedStatement + addBatch,commit;异常 rollback 再 rethrow

**Gate**: `mvn -q test -Dtest=SqliteStoreWriteTest` — exit 0

---

### T8: IndexCommand 集成 + 端到端 IT [REQ-001, REQ-011, REQ-012, AC-001]

**Status**: [ ] done

#### Phase 1: Skeleton

- [ ] `IndexCommand.call()` — 修改 — 串起所有组件:解析参数 → ClasspathDetector → ProjectScanner → JdtParserFactory.parseAll(回调中调用 TypeExtractor + MethodExtractor)→ SqliteStore.initSchema + write → 输出 stats → 返回 0
- [ ] 选项处理: `--output` 默认 `<projectPath>/.anatomist/index.db`(自动 mkdir 父目录);`--no-classpath` 跳过 ClasspathDetector.detect;`--classpath`/`--project-source` 按 `File.pathSeparator` 拆分覆盖检测

**Gate**: `mvn -q compile` — exit 0

#### Phase 2: DSL Test

- [ ] `src/test/java/com/anatomist/cli/IndexCommandIT.java#indexesMiniSpringShopServiceModule` — 场景 S1,AC-001 — 用 `CommandLine.execute("index", "<repoRoot>/fixtures/mini-spring-shop/service", "--output", tmpDb, "--no-classpath")`,断言: 退出码 0;DB 存在;OrderService CLASS Node 存在;METHOD 节点数 ≥ 1;CONTAINS 边数 > 0;FTS5 MATCH 'OrderService' 命中
- [ ] `src/test/java/com/anatomist/cli/IndexCommandTest.java#defaultOutputPath` — 场景 S4 — 不传 --output,验证 DB 落到 `<path>/.anatomist/index.db`
- [ ] `src/test/java/com/anatomist/cli/IndexCommandTest.java#noClasspathSkipsMvnDetection` — 场景 S3 — 用 spy/stub 验证 ClasspathDetector.detect 未被调用;退出码 0

**Gate**: `mvn -q test-compile && mvn -q test -Dtest=IndexCommandIT,IndexCommandTest` — ① test-compile exit 0 ② 红灯

#### Phase 3: Implementation

- [ ] 完成 IndexCommand.call() 全部串联逻辑,异常路径打印到 stderr 并返回 1
- [ ] 通过 `repoRoot` 自动定位 fixture:测试中用系统属性 `user.dir` 拼接相对路径

**Gate**: `mvn -q test` — exit 0, all green (全套测试 T1..T8 全绿)

---

## Coverage-Matrix 备注

依赖失败/降级路径:T3 覆盖 mvn 不可用;FTS5 不可用未单独覆盖(REQ-009 风险段已说明用 CREATE 探测,本期不做回退,可在 IT 失败时给清晰错误)。
可观测性:本期只有 stdout/stderr 文本统计,无 metrics/日志框架(MVP 范围内)。
鲁棒性:T7 的 `write_isAtomic` 用例已验证事务回滚。
