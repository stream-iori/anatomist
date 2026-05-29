# Tasks: 20260529-002-phase1-rest-extractors

**PRD**: [design.md](./design.md)
**Branch**: task/20260529-002-phase1-rest-extractors

> 子阶段进度由本文件 checklist 跟踪。中断恢复时从首个未勾选项继续。

## 技术评估

- 开发入口:
  - `src/main/java/com/anatomist/extract/{Field,Annotation,Hierarchy,Reference,CallGraph,FieldAccess}Extractor.java` — 6 个 Extractor 实现
  - `src/main/java/com/anatomist/extract/{Type,Method}Extractor.java` — ANONYMOUS 处理 / 守卫移除
  - `src/main/java/com/anatomist/cli/IndexCommand.java` — Extractor 注册 + stats 输出
- 验收入口:
  - `src/test/java/com/anatomist/extract/{Field,Annotation,Hierarchy,Reference,CallGraph,FieldAccess}ExtractorTest.java` — 6 个单测
  - `src/test/java/com/anatomist/extract/TypeExtractorTest.java`、`MethodExtractorTest.java` — 扩展 ANONYMOUS 用例
  - `src/test/java/com/anatomist/cli/IndexCommandIT.java` — 新增 9 种边覆盖断言
- SUT 边界: extract/ + cli/IndexCommand 微改;ExtractionContext/NodeIdGenerator 已稳定不动;SqliteStore 不动(write 已正确按顺序刷)
- 锚点说明: 单模块 Maven,Gate 命令在仓库根目录执行,无 `-pl`

## 任务清单

### T1: FieldExtractor + ANONYMOUS_CLASS Node + 移除 MethodExtractor 守卫 [REQ-001, REQ-007, REQ-008, AC-001, AC-007]

**Status**: [x] done

#### Phase 1: Skeleton

- [x] FieldExtractor 实现
- [x] TypeExtractor 新增 AnonymousClassDeclaration 处理 + alias 节点(让 binding-derived id 与父方法 id+@L 都指向同一实体)
- [x] MethodExtractor 移除 anonymous 守卫(保留 local non-anon 跳过,因 local class node 仍未实现)

#### Phase 2: DSL Test

- [x] FieldExtractorTest 2/2
- [x] TypeExtractorTest#extract_emitsAnonymousClass
- [x] MethodExtractorTest#extract_handlesAnonymousMethods

#### Phase 3: Implementation

- [x] FIELD metadata: type/isStatic/isFinal
- [x] ANONYMOUS_CLASS 用 binding 的 FQN(如 `pkg.A$1`),与 MethodExtractor 通过同 binding 得到一致 source_id
- [x] NodeIdGenerator.forType 加 `getKey()` fallback,避免极端场景 null

**Gate**: `mvn test -Dtest=FieldExtractorTest,TypeExtractorTest,MethodExtractorTest` — 10/10 green ✓

---

### T2: AnnotationExtractor [REQ-002, AC-002]

**Status**: [x] done

- [x] 实现 + 测试,4 层级覆盖(类/字段/方法/参数);attributes JSON 含 `_param` / `_name` 标识参数注解
- [x] Gate: `mvn test -Dtest=AnnotationExtractorTest` — 1/1 green ✓

---

### T3: HierarchyExtractor [REQ-003, AC-003, S2]

**Status**: [x] done

- [x] INHERITS / IMPLEMENTS / OVERRIDES + 外部父类支持
- [x] BFS 收集 super 方法链(类+所有级接口),`IMethodBinding.overrides` 判定
- [x] 测试 4/4 green;过程中修复 `ExtractionContext.isProjectInternal`:加 `binding.isFromSource()` 主信号,让内存解析也能识别项目内 binding

---

### T4: ReferenceExtractor(仅项目内)[REQ-004, BR-EXT-1, AC-004, S3]

**Status**: [x] done

- [x] field/parameter/return + 泛型 args 递归(depth≤5)
- [x] 仅项目内 target 发射
- [x] 4/4 green

---

### T5: CallGraphExtractor(含外部)[REQ-005, BR-EXT-2, AC-005]

**Status**: [x] done

- [x] 5 种 call_kind 全覆盖
- [x] 外部 binding → external_target_fqn
- [x] 修复 `HierarchyExtractor.externalMethodFqn` 用 `getMethodDeclaration()` 取原始擦除签名,避免泛型实参污染(如 `Objects.requireNonNull(this)` 不再返回 `(pkg.A)` 而是 `(java.lang.Object)`)
- [x] 2/2 green

---

### T6: FieldAccessExtractor(仅项目内字段)[REQ-006, BR-EXT-3, AC-006, S4]

**Status**: [x] done

- [x] 两遍 visitor:第一遍收集 LHS/前后置 inc/dec 的 write sites,第二遍发射边
- [x] 复合赋值同时产 READS+WRITES
- [x] 局部变量、外部字段(System.out)正确跳过
- [x] 5/5 green

---

### T7: IndexCommand 集成 + 端到端 IT [REQ-010, REQ-011, AC-008, S1]

**Status**: [ ] done

#### Phase 1: Skeleton

- [ ] IndexCommand 注册 6 个新 Extractor(顺序按 REQ-010)
- [ ] stats 输出扩展每类边数

**Gate**: `mvn -q compile` — exit 0

#### Phase 2: DSL Test

- [ ] `IndexCommandIT#indexesFixtureWithFullEdgeCoverage` — AC-008/S1 9 种边覆盖断言

**Gate**: `mvn -q test -Dtest=IndexCommandIT` — 红灯(在 Extractor 全实现之前)/绿灯(全实现后)

#### Phase 3: Implementation

- [ ] 串接全部 Extractor,验证 fixture 索引产出符合 S1 基线

**Gate**: `mvn -q test` — exit 0,全套测试通过

---

## Coverage-Matrix 备注

- **happy path**: 全部 6 个 Extractor 单测 + IT
- **错误路径**: null binding 跳过(所有 Extractor)、外部 vs 项目内分流(Hierarchy/Reference/CallGraph/FieldAccess)、复合赋值边界(FieldAccess)、override 重载消歧(Hierarchy)
- **回归**: 原 21 个测试全跑,基线数字单调增长
- **未覆盖维度**: 性能(OVERRIDES O(N×M));可观测性(暂无 metrics);Lambda/METHOD_REF(明确不做);并发(Extractor 串行)
