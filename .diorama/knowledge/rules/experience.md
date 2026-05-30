# Experience Rules

项目级实践经验,服务后续 task 的 plan / generate。

## E1: ReflectionTypeSolver 与跨 JDK 版本"假阳性 API"

**情景**: 用 `JavaParserFactory` 解析目标项目源码，且目标项目使用比 anatomist 运行 JDK 更老的 Java 版本。

**坑**: 若 `--vm-classpath true`（即在 `CombinedTypeSolver` 里加 `ReflectionTypeSolver`），当前 JVM 暴露的 JDK 类会被识别为可用 API。分析 JDK 8 项目时，`String.strip()` / `List.of()` / sealed 类等 JDK 11+ 才有的成员仍可解析成功，产生"假阳性"绑定。

**对策**:
- 生产侧 IndexCommand 默认 `--vm-classpath true`（保证 `java.lang.*` 可解析），但提示用户在分析远低于自身 JVM 的项目时显式关掉
- 测试侧 `JavaParserTestSupport.combinedTypeSolver()` 默认开启 ReflectionTypeSolver，但 JDK 8 语义验证测试要单独关掉做 negative 断言
- 真正想要目标版本 JDK 类时，把那一版的 `rt.jar` / 抽出来的 jrt 模块 jar 走 `--classpath` 传给 `JarTypeSolver`

## E2: Anonymous/Local class 的方法发射受 BR-007 约束

**情景**: MethodExtractor / FieldExtractor 在 BR-007("Phase 1 不发射 LOCAL_CLASS / LAMBDA 节点")约束下运行。

**坑**: 若不显式过滤，VoidVisitorAdapter 会进入 local class 的方法 body，生成 METHOD Node，其 `source_id` 指向不存在的 LOCAL_CLASS Node → SQLite 外键约束失败 → 整个事务 rollback → 索引完全失败。匿名类不在此列——TypeExtractor 已经发射 ANONYMOUS_CLASS Node。

**对策**: MethodExtractor.`skipDeclaringType` 检查 `declType.isClass()/isInterface()/isEnum()/isAnnotation()` 之外的情况直接 return。下个 task 实现 LOCAL_CLASS / LAMBDA Node 后移除此守卫。

## E3: FTS5 default tokenizer 把 Java 标识符切碎

**情景**: 用 `node_names MATCH '<term>'` 全字段搜索时。

**坑**: 默认 unicode61 tokenizer 把 `pkg.Class#method()` 拆为 `pkg`, `Class`, `method`,导致单 token 查询命中过多 row。测试 `MATCH 'A'` 时同时命中 `com.x.A`(label=A)和 `com.x.A#foo()`(qualified_name 含 A)。

**对策**:
- 测试断言聚焦时,用 `label MATCH '<term>'` 限定到 label 列
- 业务侧若需要精确符号查询,走 nodes.qualified_name 等值查询而非 FTS5

## E4: SQL DDL 拆分必须感知 BEGIN..END

**情景**: SqliteStore.initSchema 读 schema.sql 按 `;` 拆分执行。

**坑**: FTS5 触发器 `CREATE TRIGGER ... BEGIN INSERT ...; END;` 内部含 `;`,简单按 `;` 切会切碎 trigger body,SQLite 报语法错。

**对策**: `SqliteStore.splitSqlStatements` 维护 `BEGIN..END` 深度计数,深度 > 0 时忽略内部 `;`,只在深度归零时按 `;` 切。

## E5: ClasspathDetector 用 protected runMvn seam 提供测试入口

**情景**: 需要测试 mvn 不可用 / mvn 输出解析等场景,但又不想真实 spawn `mvn`。

**对策**: `protected int runMvn(workingDir, args)` 作为测试 seam。测试匿名子类化 ClasspathDetector,override runMvn 直接抛 IOException 或写假 outputFile,即可覆盖降级路径与解析路径,完全脱离 mvn 二进制。

## E6: 单测想要符号绑定时用 JavaParserTestSupport

**情景**: TypeExtractor/MethodExtractor/NodeIdGenerator 等单测需要构造 `ResolvedReferenceTypeDeclaration` / `ResolvedMethodDeclaration`。

**对策**: `src/test/java/com/anatomist/core/JavaParserTestSupport.java` 封装：
- `parse(source)`：直接传入源码字符串，内部挂 `JavaSymbolSolver(CombinedTypeSolver(ReflectionTypeSolver + MemoryTypeSolver))`
- `resolveType(cu, simpleName)` / `resolveMethods(cu, type, method)` / `resolveConstructor(cu, type)`：便捷拿到 `Resolved*` 声明

测试 scope 直接拿到带绑定的 CompilationUnit。**注意**: 该 helper 必须 public(被跨包测试引用)。

## E7: ExtractionContext.isProjectInternal 用 TypeSolver 类型判定

**情景**: HierarchyExtractor / ReferenceExtractor / CallGraphExtractor / FieldAccessExtractor 都需要区分项目内 vs 外部依赖。

**坑**: JavaParser 不在 binding 上提供 `isFromSource()` 这类直接信号。我们必须基于"哪一个 TypeSolver 返回了它"来判定。

**对策**: `ExtractionContext.isProjectInternal(ResolvedTypeDeclaration)` 用 `instanceof` 判断：
- 项目内 = `JavaParserClassDeclaration` / `JavaParserInterfaceDeclaration` / `JavaParserEnumDeclaration` / `JavaParserAnnotationDeclaration` / `JavaParserAnonymousClassDeclaration`（都来自 `JavaParserTypeSolver`）
- 外部 = 其余（`ReflectionClass*Declaration` 或我们自实现的 `JavassistClassDeclaration`）

`JarTypeSolver` 落地后只要保持 `JavassistClassDeclaration` 不是 `JavaParser*Declaration` 子类即可继续按"外部"处理。

## E8: 外部方法 FQN 用 ResolvedMethodDeclaration 的擦除签名

**情景**: `CallGraphExtractor.emit` 想给外部调用记录稳定的 `external_target_fqn`。

**坑**: `MethodCallExpr.resolve()` 返回的 `ResolvedMethodDeclaration` 上，参数泛型可能已被实参替换；如果跟着取 `getParam(i).getType()`，"同一个外部方法的所有调用"会变成 N 个不同 FQN，Phase 2 callers-of 查询完全失效。

**对策**: 统一用 `NodeIdGenerator.erasedTypeDescribe(resolvedType)`（内部走 `ResolvedType.erasure().describe()`）拼擦除签名；公用 helper `NodeIdGenerator.externalMethodFqn(...)`，HierarchyExtractor / CallGraphExtractor 共用。

## E9: Lambda / METHOD_REF 未实现时,IndexCommand 必须 pruneDanglingInternalEdges

**情景**: 任何含 Lambda / method reference(`stream().filter(x -> ...)`、`::method`)的真实项目。

**坑**: CallGraphExtractor / FieldAccessExtractor 在 Lambda body 内 visit 到的 `MethodCallExpr` 会通过"上溯找到 enclosing MethodDeclaration"的逻辑归到外层方法。多数情况 OK,但当 Lambda **本身**是 functional interface 的合成方法（如 `BiFunction.apply`）时，enclosing 可能解析失败或落到一个没有对应 nodes 行的合成签名 → CONTAINS/CALLS 边 source_id 找不到节点 → 整个事务被 SQLite FK violation 推翻。

**对策**: IndexCommand 在 `store.write` 之前调 `pruneDanglingInternalEdges(result)`:扫描所有 internal 边,过滤掉 source_id / target_id 不在 nodes 集合中的边,只 warn 不 abort。LAMBDA Node 落地后该 helper 可保留作为最后防线。

## E10: --no-classpath 模式下 Spring 注解 unresolved

**情景**: 测试用 `--no-classpath` 跑 fixture(避免联网拉 Spring 依赖)。

**坑**: AnnotationExtractor 通过 `AnnotationExpr.resolve()` 取 FQN。Spring 注解(`@Service`/`@Autowired`/`@Transactional`)需要 classpath 才能解析,无 classpath 时这些 `resolve()` 调用抛 `UnsolvedSymbolException`,annotations 表里只剩 `ReflectionTypeSolver` 命中的 JDK 内置注解(`@Override` / `@Deprecated` / `@SuppressWarnings`)。

**对策**: 测试断言用 `@Override`(JDK 内置,只要 `--vm-classpath true` 即可解析)而非 Spring 注解。若要测 Spring 注解,需要在 IT 中先确保 ClasspathDetector 工作正常,或显式传 `--classpath`。生产场景下用户跑 `anatomist index` 默认走 ClasspathDetector,不受此限制。
