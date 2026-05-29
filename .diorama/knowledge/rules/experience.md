# Experience Rules

项目级实践经验,服务后续 task 的 plan / generate。

## E1: JDT createASTs 需要可寻址的系统库

**情景**: 用 `ASTParser.createASTs(...)` 解析多个 Java 文件,且未提供 classpath 时。

**坑**: 若同时 `includeRunningVMClasspath = false` 且 classpath 为空, JDT 抛 `IllegalStateException: Missing system library`。

**对策**:
- 生产侧 IndexCommand 默认 false(避免向 Java 8 项目注入高版本 JDK API),依赖 ClasspathDetector 提供至少一个 jar
- 测试侧需要 createASTs 时,在不关心 API 集精度的 case 中传 `true`(只关心跨文件 binding 是否能 resolve)
- 用户传 `--no-classpath` 时接受 binding 不完整,但 TypeExtractor/MethodExtractor 仍可工作(它们只读声明 binding)

## E2: Anonymous/Local class 的方法不能在 BR-007 限定下提取

**情景**: MethodExtractor 在 BR-007("本期不提取 anonymous/lambda/field")约束下运行。

**坑**: 若不显式过滤,JDT visit(MethodDeclaration) 会进入 anonymous class 的方法 body,生成 METHOD Node,其 `source_id` 指向不存在的 anonymous CLASS Node → SQLite 外键约束失败 → 整个事务 rollback → 索引完全失败。

**对策**: MethodExtractor.emit 开头检查 `declClass.isAnonymous() || declClass.isLocal()`,直接 return。下个 task 实现 ANONYMOUS_CLASS Node 后移除此守卫。

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

## E6: 单测想要 JDT binding 时用最小内存 source + ASTParser.setSource

**情景**: TypeExtractor/MethodExtractor/NodeIdGenerator 等单测需要构造 ITypeBinding / IMethodBinding。

**对策**: `src/test/java/com/anatomist/core/JdtTestSupport.java` 封装 `parse(unitName, source)`,内部 `setEnvironment(new String[0], new String[0], null, true) + setUnitName + setSource(...) + createAST(null)`。test scope 直接拿到带 binding 的 CompilationUnit。**注意**: 该 helper 必须 public(被跨包测试引用)。

## E7: ExtractionContext.isProjectInternal 必须用 binding.isFromSource() 作为主信号

**情景**: HierarchyExtractor / ReferenceExtractor / CallGraphExtractor / FieldAccessExtractor 都需要区分项目内/外部 binding。

**坑**: 原实现只比对 `binding.getJavaElement().getPath()` 与 sourcePaths。在测试场景下 sourcePaths 为空 → 永远返回 false → 所有项目内 binding 都被当外部处理,断言失败。

**对策**: 优先用 `binding.isFromSource()`:JDT 对任何从源码解析(无论 createASTs 还是内存 setSource)的 binding 都返回 true,对从 jar 解析的返回 false。这恰好是"项目内"的语义。原 path 比对作为 fallback 保留。

## E8: 外部方法 FQN 必须用 IMethodBinding.getMethodDeclaration() 取声明参数,而非调用点参数

**情景**: `CallGraphExtractor.emit` 想给外部调用记录稳定的 `external_target_fqn`。

**坑**: `binding.getParameterTypes()` 返回的是**调用点解析后**的类型(泛型实参已替换)。如 `java.util.Objects.requireNonNull(pkg.A obj)` 中,调用 `requireNonNull(this)` 会让 binding 的参数类型变成 `pkg.A` 而不是 `T` 的擦除 `java.lang.Object` —— 这让"同一个外部方法的所有调用"变成 N 个不同 FQN,Phase 2 callers-of 查询完全失效。

**对策**: 用 `binding.getMethodDeclaration().getParameterTypes()` 取原始**声明**参数类型,再 `.getErasure().getQualifiedName()` 得到稳定 FQN。HierarchyExtractor / CallGraphExtractor 共用同一个 helper(`HierarchyExtractor.externalMethodFqn`)。

## E9: Lambda / METHOD_REF 未实现时,IndexCommand 必须 pruneDanglingInternalEdges

**情景**: 任何含 Lambda / method reference(`stream().filter(x -> ...)`、`::method`)的真实项目。

**坑**: CallGraphExtractor / FieldAccessExtractor 在 Lambda body 内 visit 到的 MethodInvocation 会通过 `enclosingMethodBinding` 上溯到外层方法(因为本期不发射 LAMBDA Node)。多数情况 OK,但当 Lambda **本身**是 functional interface 的合成方法(如 BiFunction 的 `apply`)时,enclosingMethod 可能返回 lambda 的 synthetic binding,其 `forMethod(...)` ID 与 nodes 表对不上 → CONTAINS/CALLS 边 source_id 找不到节点 → 整个事务被 SQLite FK violation 推翻。

**对策**: IndexCommand 在 `store.write` 之前调 `pruneDanglingInternalEdges(result)`:扫描所有 internal 边,过滤掉 source_id / target_id 不在 nodes 集合中的边,只 warn 不 abort。fixture 上典型 drop 数 = 3-5 条。LAMBDA Node 落地后该 helper 可保留作为最后防线。

## E10: --no-classpath 模式下 Spring 注解 binding 全 null

**情景**: 测试用 `--no-classpath` 跑 fixture(避免联网拉 Spring 依赖)。

**坑**: AnnotationExtractor 通过 `binding.getAnnotations()` 收集注解。Spring 注解(`@Service`/`@Autowired`/`@Transactional`)需要 classpath 才能 resolve,无 classpath 时这些注解的 binding 全是 null,annotations 表里只剩 JDK-builtin 注解(`@Override` / `@Deprecated` / `@SuppressWarnings`)。

**对策**: 测试断言用 `@Override`(JDK 内置,永远可解析)而非 Spring 注解。若要测 Spring 注解,需要在 IT 中先确保 ClasspathDetector 工作正常,或显式传 `--classpath`。生产场景下用户跑 `anatomist index` 默认走 ClasspathDetector,不受此限制。
