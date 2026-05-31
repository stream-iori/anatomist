# Tasks: Phase 1 场景 1 缺口全量补齐

**Source**: [design.md](./design.md)

## 技术评估

### 开发入口

- `src/main/java/com/anatomist/extract/MethodExtractor.java` — Lambda / MethodReference / isAccessor 主要修改点
- `src/main/java/com/anatomist/extract/TypeExtractor.java` — RecordDeclaration 新增 visit
- `src/main/java/com/anatomist/extract/CallGraphExtractor.java` — enclosingMethodId 扩展识别 LambdaExpr / MethodReferenceExpr
- `src/main/java/com/anatomist/extract/FieldAccessExtractor.java` — enclosingId 同步扩展
- `src/main/java/com/anatomist/extract/ReferenceExtractor.java` — LambdaExpr 参数/返回类型 REFERENCES
- `src/main/java/com/anatomist/core/NodeIdGenerator.java` — forLambda / forMethodRef 新增
- `src/main/java/com/anatomist/core/ClasspathDetector.java` — detectJavaVersion 新增（SAX 读 pom.xml）
- `src/main/java/com/anatomist/core/JavaParserFactory.java` — toLanguageLevel 补齐 JAVA_14/16/17/19
- `src/main/java/com/anatomist/cli/IndexCommand.java` — Java 版本检测接线、统计输出新增 LAMBDA/METHOD_REF/RECORD、isType 增加 RECORD

### 验收入口

- `src/test/java/com/anatomist/extract/MethodExtractorTest.java` — Lambda/METHOD_REF/isAccessor 单元用例
- `src/test/java/com/anatomist/extract/TypeExtractorTest.java` — RECORD 单元用例
- `src/test/java/com/anatomist/extract/CallGraphExtractorTest.java` — Lambda body CALLS 归因
- `src/test/java/com/anatomist/extract/FieldAccessExtractorTest.java` — Lambda body READS/WRITES 归因
- `src/test/java/com/anatomist/extract/ReferenceExtractorTest.java` — Lambda 参数/返回类型 REFERENCES
- `src/test/java/com/anatomist/core/ClasspathDetectorTest.java` — pom.xml Java 版本检测
- `src/test/java/com/anatomist/core/NodeIdGeneratorTest.java` — forLambda / forMethodRef ID 格式
- `src/test/java/com/anatomist/cli/IndexCommandIT.java` — 端到端 fixture 基线 + Pruned dangling = 0

### 模块结构

仅一个 Maven 模块（根模块 `anatomist`）。所有 Gate 命令统一使用 `mvn ... -q`，不带 `-pl`。

---

## 任务清单

每个任务遵循 Skeleton → DSL Test → Implementation 三相节奏。Gate 命令可直接复制执行。

---

### T1: isAccessor metadata 标记 [REQ-004, BR-002]

**Status**: [x] done

**目标**: `MethodExtractor.methodMetadata()` 新增 `isAccessor` 字段，getter（`get`/`is` 前缀 + 0 参 + 非 void 返回）和 setter（`set` 前缀 + 1 参 + void 返回）为 true，其余为 false。

**Phase 1: Skeleton**
- 在 `MethodExtractor` 新增私有方法 `isAccessor(MethodDeclaration md)`，返回 `boolean`，占位返回 `false`
- 在 `methodMetadata(...)` 写入 `metadata.put("isAccessor", isAccessor(md))`
- **Gate**: `mvn compile -q` — exit 0

**Phase 2: DSL Test**
- `MethodExtractorTest` 新增三个用例：
  - `methodMetadata_marksGetterAsAccessor` — `String getName()` → `isAccessor=true`
  - `methodMetadata_marksSetterAsAccessor` — `void setName(String n)` → `isAccessor=true`
  - `methodMetadata_marksNonAccessorAsFalse` — `void process()` / `boolean isActive()`（注意 `is` + 非 boolean 返回 / boolean 返回的语义）覆盖反例
- **Gate**: `mvn test-compile -q && mvn test -Dtest=MethodExtractorTest -q` — ① test-compile exit 0 ② 三个新用例 fail with AssertionError（红灯）

**Phase 3: Implementation**
- 实现 `isAccessor`：getter = (name 以 `get` 开头且长度>3 + 0 参 + 非 void 返回) OR (name 以 `is` 开头且长度>2 + 0 参 + boolean 返回)；setter = (name 以 `set` 开头且长度>3 + 1 参 + void 返回)
- **Gate**: `mvn test -Dtest=MethodExtractorTest -q` — exit 0, all green

---

### T2: NodeIdGenerator 新增 forLambda / forMethodRef [REQ-001, REQ-002]

**Status**: [x] done

**目标**: 在 `NodeIdGenerator` 新增两个静态方法，提供 LAMBDA / METHOD_REF 的稳定 ID 生成。

**Phase 1: Skeleton**
- `NodeIdGenerator.forLambda(String parentId, int line, int column)` → 返回 `parentId + "$lambda@L" + line + "C" + column`
- `NodeIdGenerator.forMethodRef(String parentId, int line, int column)` → 返回 `parentId + "$methodref@L" + line + "C" + column`
- 两方法均先以正确 return 实现（极小逻辑，无法做合理 skeleton 占位）
- **Gate**: `mvn compile -q` — exit 0

**Phase 2: DSL Test**
- `NodeIdGeneratorTest` 新增用例：
  - `forLambda_concatsParentWithLineColumn`
  - `forMethodRef_concatsParentWithLineColumn`
  - `forLambda_isStableAcrossCalls`（相同输入幂等）
- 在测试中将期望值故意写错一位以确保红灯，确认后改回正确期望
- **Gate**: `mvn test-compile -q && mvn test -Dtest=NodeIdGeneratorTest -q` — ① test-compile exit 0 ② 新用例 fail（红灯）

**Phase 3: Implementation**
- 修正期望值，测试转绿
- **Gate**: `mvn test -Dtest=NodeIdGeneratorTest -q` — exit 0

---

### T3: LAMBDA Node 提取 + enclosingId 扩展 + Lambda REFERENCES [REQ-001, BR-001, BR-004]

**Status**: [x] done

**目标**:
1. `MethodExtractor` 新增 `visit(LambdaExpr)` 产出 LAMBDA Node（含 parameters/returnType/signature metadata）+ CONTAINS 边（parent method → LAMBDA）
2. `CallGraphExtractor.enclosingMethodId()` 与 `FieldAccessExtractor.enclosingId()` 沿 AST 向上识别 `LambdaExpr` 并返回 LAMBDA Node ID（取代默认的父 method ID）
3. `ReferenceExtractor` 新增 `visit(LambdaExpr)` 为 Lambda 参数类型 / 返回类型产出 REFERENCES 边（source_id = LAMBDA Node ID）
4. 嵌套 Lambda 时 enclosing 取最近一层 Lambda

依赖：T2（需要 `NodeIdGenerator.forLambda`）

**Phase 1: Skeleton**
- `MethodExtractor` 新增 `@Override visit(LambdaExpr n, Void arg)`，TODO 占位（仅 `super.visit(n, arg)`）
- `CallGraphExtractor` 抽出 `enclosingNodeId(Node ast)` 私有方法，先沿用旧逻辑（仅 CallableDeclaration）但留 TODO 标记
- `FieldAccessExtractor` 同上
- `ReferenceExtractor` 新增 `visit(LambdaExpr n, Void arg)` 占位
- **Gate**: `mvn compile -q` — exit 0

**Phase 2: DSL Test**
- `MethodExtractorTest` 新增：
  - `visit_lambdaExpr_emitsLambdaNodeAndContainsEdge`
  - `visit_nestedLambda_emitsDistinctIds`
- `CallGraphExtractorTest` 新增：
  - `enclosingId_lambdaBodyCall_attributesToLambdaNode`
- `FieldAccessExtractorTest` 新增：
  - `enclosingId_lambdaBodyFieldAccess_attributesToLambdaNode`
- `ReferenceExtractorTest` 新增：
  - `visit_lambdaParameterType_emitsReferencesEdge`
- **Gate**: `mvn test-compile -q && mvn test -Dtest=MethodExtractorTest,CallGraphExtractorTest,FieldAccessExtractorTest,ReferenceExtractorTest -q` — ① test-compile exit 0 ② 新用例红灯

**Phase 3: Implementation**
- 在 `MethodExtractor.visit(LambdaExpr)` 中：先沿 AST 上溯找到包裹的 CallableDeclaration → 得到 parent method ID（复用现有 NodeIdGenerator 逻辑）→ `NodeIdGenerator.forLambda(parentId, line, col)` → 产出 LAMBDA Node + CONTAINS 边；SymbolSolver resolve 失败时 `ctx.incrementUnresolved()` 且 metadata 置 `bindingResolved: false`，**不**跳过 Node 产出（位置信息足以建 Node）
- `enclosingNodeId` 通用算法：从给定 AST 节点向上找最近的 LambdaExpr / MethodReferenceExpr / CallableDeclaration；命中 LambdaExpr → `forLambda(...)`；命中 MethodReferenceExpr → `forMethodRef(...)`（T4 中接线）；命中 CallableDeclaration → 旧逻辑
- `ReferenceExtractor.visit(LambdaExpr)`：参数显式类型（非隐式 var）+ 推断的返回类型 → REFERENCES 边，source_id = LAMBDA Node ID
- **Gate**: `mvn test -q` — exit 0, all green（含已有用例不退化）

---

### T4: METHOD_REF Node 提取 + CALLS 边 [REQ-002, BR-001, BR-007]

**Status**: [ ] done

**目标**:
1. `MethodExtractor` 新增 `visit(MethodReferenceExpr)` 产出 METHOD_REF Node + CONTAINS 边 + CALLS 边
2. resolve 失败（含 `Foo::new` 构造器引用返回非 `ResolvedMethodDeclaration` 的情况）graceful 降级：Node 仍产出，metadata `bindingResolved: false`，**不**产出 CALLS 边
3. `enclosingNodeId` 在 T3 已经具备识别 MethodReferenceExpr 的能力，本任务接线

依赖：T2 (forMethodRef), T3 (enclosingNodeId)

**Phase 1: Skeleton**
- `MethodExtractor.visit(MethodReferenceExpr n, Void arg)` 占位 (super 调用)
- **Gate**: `mvn compile -q` — exit 0

**Phase 2: DSL Test**
- `MethodExtractorTest` 新增：
  - `visit_methodReferenceExpr_emitsMethodRefNodeAndCallsEdge` — `list.stream().map(Order::getTotal)` 验证 Node + CONTAINS + CALLS
  - `visit_methodReferenceExpr_resolveFailure_emitsNodeWithoutCallsEdge` — 故意构造无法 resolve 的引用（例如外部未提供的类型），断言 metadata `bindingResolved=false` 且无 CALLS 边
  - `visit_constructorReference_gracefulDegrade` — `list.stream().map(Order::new)` resolve 返回非 ResolvedMethodDeclaration 时 graceful
- **Gate**: `mvn test-compile -q && mvn test -Dtest=MethodExtractorTest -q` — ① test-compile exit 0 ② 新用例红灯

**Phase 3: Implementation**
- 在 `visit(MethodReferenceExpr)` 中：找 enclosing method（沿 AST 上溯到最近 CallableDeclaration 获得 ID）→ `NodeIdGenerator.forMethodRef(parentId, line, col)` → 产出 METHOD_REF Node + CONTAINS 边
- 尝试 `n.resolve()`：成功且返回 `ResolvedMethodDeclaration` → 产出 CALLS 边（target_id 复用现有 MethodDeclaration ID 推导）；否则 metadata 置 `bindingResolved: false`，仅 Node + CONTAINS
- **Gate**: `mvn test -q` — exit 0, all green

---

### T5: Java 版本自动检测 [REQ-003, BR-005]

**Status**: [ ] done

**目标**:
1. `ClasspathDetector.detectJavaVersion(Path projectRoot)` 用 JDK 内置 SAX 解析所有 pom.xml，读取 `<maven.compiler.source>` / `<java.version>`，多模块取最大值
2. 优先级：`--java-version` CLI 参数 > `<maven.compiler.source>` > `<java.version>` > 默认 8
3. `IndexCommand` 接线：未显式传 `--java-version` 时调用 `detectJavaVersion`，并在启动日志输出 `Parsing with Java <N>`
4. `JavaParserFactory.toLanguageLevel` 补齐 JAVA_14/16/17/19 映射（Record 至少 JAVA_16）

**Phase 1: Skeleton**
- `ClasspathDetector.detectJavaVersion(Path)` 新增，先返回 `Optional.empty()`
- `IndexCommand` 在 `--java-version` 未指定时调用 `detectJavaVersion`，仍硬编码 fallback 8
- `JavaParserFactory.toLanguageLevel` 补齐 case 14/16/17/19
- **Gate**: `mvn compile -q` — exit 0

**Phase 2: DSL Test**
- `ClasspathDetectorTest` 新增：
  - `detectJavaVersion_readsMavenCompilerSource` — `<maven.compiler.source>17</maven.compiler.source>` → 17
  - `detectJavaVersion_fallsBackToJavaVersionProperty` — 仅 `<java.version>11` → 11
  - `detectJavaVersion_multiModuleReturnsMax` — 模块 A=11, B=17 → 17
  - `detectJavaVersion_noPomReturnsEmpty` — 非 Maven 项目 → `Optional.empty()`
- `JavaParserFactoryTest`（若存在）新增 `toLanguageLevel_supportsJava17` 用例
- **Gate**: `mvn test-compile -q && mvn test -Dtest=ClasspathDetectorTest,JavaParserFactoryTest -q` — ① test-compile exit 0 ② 新用例红灯

**Phase 3: Implementation**
- 用 `javax.xml.parsers.SAXParserFactory` 实现，遍历 projectRoot 下所有 pom.xml（深度优先），用 SAX handler 累积 `<maven.compiler.source>` 和 `<java.version>` 的值，转 int 后取最大；`<maven.compiler.source>` 命中优先于 `<java.version>`（同一 pom 内）但跨 pom 仍是全局取最大
- `IndexCommand` 接线：显式 `--java-version` → 直接用；否则 `detectJavaVersion` → `getOrElse(8)`，并 `System.err.println("Parsing with Java " + version)`
- **Gate**: `mvn test -q` — exit 0, all green

**风险锚定**: 父 POM 继承场景下 SAX 简单遍历可能漏读；以 `--java-version` 显式参数兜底（design.md §9 已记录）。

---

### T6: RECORD 支持 [REQ-005, BR-002, BR-006]

**Status**: [ ] done

**目标**:
1. `TypeExtractor` 新增 `visit(RecordDeclaration)` 产出 kind=`RECORD` 的 Node + CONTAINS 边
2. `FieldExtractor` 提取 Record 组件（components）为 kind=`FIELD` 的 Node（参考 RecordDeclaration `getParameters()`）
3. `IndexCommand.isType()` 增加 `RECORD` 判断
4. RecordDeclaration 在 Java < 16 时跳过（catch parse 异常 / 检查 javaVersion）

依赖：T5（需要 Java 版本检测才能正确支持 Record 解析）

**Phase 1: Skeleton**
- `TypeExtractor.visit(RecordDeclaration n, Void arg)` 占位
- `FieldExtractor` 增加 Record 组件遍历占位
- `IndexCommand.isType` 增加 `RECORD` 分支
- **Gate**: `mvn compile -q` — exit 0

**Phase 2: DSL Test**
- `TypeExtractorTest` 新增：
  - `visit_recordDeclaration_emitsRecordNode` — 源码 `public record Point(int x, int y) {}` + javaVersion 17 → RECORD Node
  - `visit_recordDeclaration_belowJava16_skipsOrFailsGracefully` — javaVersion 8 → 不产出 RECORD Node 且不抛异常
- `FieldExtractorTest` 新增：
  - `extract_recordComponents_emitsFieldNodes` — `Point(int x, int y)` → 2 个 FIELD Node + CONTAINS
- **Gate**: `mvn test-compile -q && mvn test -Dtest=TypeExtractorTest,FieldExtractorTest -q` — ① test-compile exit 0 ② 新用例红灯

**Phase 3: Implementation**
- 实现 RECORD Node 产出（kind=`RECORD`, ID = FQN 同类规则）
- FieldExtractor 遍历 `RecordDeclaration.getParameters()` 产出 FIELD Node + CONTAINS 边
- 低版本 graceful：解析阶段 `RecordDeclaration` 节点不存在即天然跳过；保留 try/catch 防御
- **Gate**: `mvn test -q` — exit 0, all green

---

### T7: Fixture 端到端基线刷新 + Pruned dangling = 0 [BR-003, BR-007, AC-014, AC-015]

**Status**: [ ] done

**目标**:
1. 运行 fixture 端到端确认 baseline 单调增长（types ≥ 16, methods ≥ 47）且 LAMBDA / METHOD_REF / RECORD 计数已写入
2. `IndexCommandIT` 更新断言：Pruned dangling = 0；types / methods / 各类 edge 计数更新为新基线
3. 在 `CLAUDE.md` 中更新 "Fixture" 段提到的基线数字（仅当数字变化时）

依赖：T1..T6 全部完成

**Phase 1: Skeleton**
- 暂留 IT 中已有断言
- 新增 helper 方法 `assertNoPrunedDangling(...)`（先抛 `UnsupportedOperationException` 占位）
- **Gate**: `mvn test-compile -q` — exit 0

**Phase 2: DSL Test**
- `IndexCommandIT` 新增 `index_fixture_prunedDanglingIsZero`，断言 stderr / stats 中 Pruned dangling = 0
- 更新现有 `index_fixture_baseline_*` 用例，断言 types/methods/edges 不小于新基线
- 若 fixture 中尚无 Lambda/MethodReference/Record 样例，向 `fixtures/mini-spring-shop/service/src/main/java/...` 追加一个包含 lambda / method reference / record 的样例类（最小侵入）
- **Gate**: `mvn test-compile -q && mvn test -Dtest=IndexCommandIT -q` — ① test-compile exit 0 ② 新断言红灯（Pruned dangling 尚未 0 / 新基线未达）

**Phase 3: Implementation**
- 实现 `assertNoPrunedDangling`，通过解析 IT 的索引产物 stats 输出或直接读 stderr 验证
- 跑一次 `java -jar target/anatomist.jar index fixtures/mini-spring-shop ...` 取实际数字回填到 IT 期望
- 若数字与现有 CLAUDE.md 描述发生变更：更新 CLAUDE.md "Fixture" 段中的基线数字
- **Gate**: `mvn test -q` — exit 0, all green

---

## 任务依赖图

```text
T1 (isAccessor)               独立
T2 (NodeIdGenerator helpers)  独立
T3 (LAMBDA)                   → 依赖 T2
T4 (METHOD_REF)               → 依赖 T2, T3
T5 (Java version detection)   独立
T6 (RECORD)                   → 依赖 T5
T7 (Fixture IT)               → 依赖 T1..T6
```

推荐执行顺序：**T1 → T2 → T3 → T4 → T5 → T6 → T7**（线性化，便于按顺序 checkpoint）。

---

## REQ → Task 追溯矩阵

| REQ | 任务 |
|-----|------|
| REQ-001 | T2, T3 |
| REQ-002 | T2, T4 |
| REQ-003 | T5 |
| REQ-004 | T1 |
| REQ-005 | T6 |
| BR-001  | T3, T4 |
| BR-002  | T1, T6 |
| BR-003  | T7 |
| BR-004  | T3 |
| BR-005  | T5 |
| BR-006  | T6 |
| BR-007  | T4, T7 |
