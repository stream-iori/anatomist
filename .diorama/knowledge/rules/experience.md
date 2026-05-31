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

**情景**: MethodExtractor / FieldExtractor 在 BR-007("Phase 1 不发射 LOCAL_CLASS Node")约束下运行。

**坑**: 若不显式过滤，VoidVisitorAdapter 会进入 local class 的方法 body，生成 METHOD Node，其 `source_id` 指向不存在的 LOCAL_CLASS Node → SQLite 外键约束失败 → 整个事务 rollback → 索引完全失败。匿名类不在此列——TypeExtractor 已经发射 ANONYMOUS_CLASS Node。Lambda / MethodReference 也不在此列——T3/T4(20260530-001)落地后 MethodExtractor 自身发射 LAMBDA / METHOD_REF Node,并通过 `AstEnclosing` 让 CallGraphExtractor / FieldAccessExtractor / ReferenceExtractor 正确归因。

**对策**: MethodExtractor.`skipDeclaringType` 检查 `declType.isClass()/isInterface()/isEnum()/isAnnotation()` 之外的情况直接 return。下个 task 实现 LOCAL_CLASS Node 后移除此守卫。

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

## E9: pruneDanglingInternalEdges 仍是最后防线

**情景**: 任何含 Lambda / method reference / anonymous-class body 的真实项目。

**坑**: 即便 T3/T4(20260530-001)落地了 LAMBDA / METHOD_REF Node 和 AstEnclosing 归因,残留的 dangling 边仍来自 TypeExtractor 对匿名类 ID 的编码方式(`<parentMethod>$anon@L<line>`)与 SymbolSolver 解析匿名类内方法时返回的 `Anonymous-<uuid>` 不一致;CallGraphExtractor 会产出 `source_id = Anonymous-<uuid>#run()` 形式的 CALLS/READS/CONTAINS 边,在 nodes 表中无匹配。

**对策**: 保留 `IndexCommand.pruneDanglingInternalEdges` 作为最后防线,只 warn 不 abort。彻底消除需要独立 task 重写匿名类 ID 编码,使其与 SymbolSolver 解析路径对齐(或反向调整 TypeExtractor)。

## E11: VoidVisitorAdapter 的子类必须显式 super.visit() 才会递归

**情景**: 给现有 Extractor(MethodExtractor / TypeExtractor / FieldExtractor / ReferenceExtractor)新增 `visit(SomeNode n, Void arg)` override。

**坑**: 在新 override 里只调 emit 逻辑、忘记 `super.visit(n, arg)`,VoidVisitorAdapter 不会继续递归 SomeNode 的子树。表现为嵌套 lambda 只发射外层、record 内部成员不发射等。

**对策**: 任何新增的 `visit(...)` 重写必须以 `super.visit(n, arg);` 结尾(emit 逻辑写在 super 之前或之后都行,但 super 调用本身不能省)。MethodExtractor.visit(LambdaExpr) 嵌套 lambda 测试就是这条规则的回归保护。

## E12: SymbolSolver "可恢复"失败的统一惯例 — Node 仍发射 + bindingResolved=false

**情景**: MethodReferenceExpr.resolve() 在构造器引用、外部依赖缺失、跨工程符号缺位等场景会抛 `UnsolvedSymbolException` / `UnsupportedOperationException`。

**坑**: 旧惯例是 catch 之后整体 skip,但这会让上层 Edge 找不到 Node target(METHOD_REF Node 没了, CALLS 边目标也消失)。

**对策**: T4(20260530-001)起的新惯例 —— **Node 永远发射**(位置信息足以建 Node),仅把 "我没解析出绑定" 写入 `metadata.bindingResolved = false`,放弃产出后续依赖 binding 的 Edge(如 CALLS 边)。CallGraphExtractor.emit 已有相同模式,后续 Extractor 也应一致。

## E13: AstEnclosing 是 LAMBDA / METHOD_REF 归因的唯一入口

**情景**: 实现 Lambda body 内部 CALLS / READS / WRITES / REFERENCES 的 `source_id`。

**坑**: 旧的 `findAncestor(CallableDeclaration.class)` 只能到达 MethodDeclaration / ConstructorDeclaration,Lambda 体内的调用会被错归到外层方法,破坏图遍历的因果关系。

**对策**: `com.anatomist.extract.AstEnclosing.ownerIdOf(Node)` 按 LambdaExpr → MethodReferenceExpr → MethodDeclaration → ConstructorDeclaration → FieldDeclaration → TypeDeclaration 顺序找最近祖先。任何归因到"当前所在方法/Lambda/MethodRef"的代码都应走它,不要再写新的 `findAncestor(CallableDeclaration)`。Extractor 内 `enclosing = new AstEnclosing(ctx.idGenerator())` 一次性持有即可。

## E10: --no-classpath 模式下 Spring 注解 unresolved

**情景**: 测试用 `--no-classpath` 跑 fixture(避免联网拉 Spring 依赖)。

**坑**: AnnotationExtractor 通过 `AnnotationExpr.resolve()` 取 FQN。Spring 注解(`@Service`/`@Autowired`/`@Transactional`)需要 classpath 才能解析,无 classpath 时这些 `resolve()` 调用抛 `UnsolvedSymbolException`,annotations 表里只剩 `ReflectionTypeSolver` 命中的 JDK 内置注解(`@Override` / `@Deprecated` / `@SuppressWarnings`)。

**对策**: 测试断言用 `@Override`(JDK 内置,只要 `--vm-classpath true` 即可解析)而非 Spring 注解。若要测 Spring 注解,需要在 IT 中先确保 ClasspathDetector 工作正常,或显式传 `--classpath`。生产场景下用户跑 `anatomist index` 默认走 ClasspathDetector,不受此限制。

## E14: JavaParser `Node.equals()` 是结构相等 — Set/Map 必须用身份语义

**情景**: 任何 Extractor 需要把 AST Node 放进 Set/Map 做"标记过/跳过"判定时（写点集、已访问集、归因缓存）。

**坑**: `com.github.javaparser.ast.Node.equals()` 走 `EqualsVisitor`，是**结构相等**：两个不同实例只要 AST 子树相同就判等。最直观的"踩雷"是 `x = x + 1` —— LHS 和 RHS 各是一个 `NameExpr("x")`，结构完全相同。如果 `Set<Node> writeSites = new HashSet<>()` 加了 LHS，那么 `writeSites.contains(rhsNameExpr)` 返回 `true`，RHS 被当成写点跳过，**整个 READS 边静默丢失**。更隐蔽的是辐射效应：同一个类里**其它方法**的 `return x` 因为也是 `NameExpr("x")`，同样命中 `contains`，导致该字段在全类范围内的 READS 全部消失。`FieldAccessExtractor` 曾因此让 `MicroFixtureIT.fieldReadWrite_emitsReadsAndWrites` 报 0 READS——而 5 条单元测试全过(因为每个单测要么不带写、要么写 + 字面量 RHS，恰好回避了"同名 NameExpr 出现在写点和读点"组合)。

**对策**:
- 任何 `Set<Node>` / `Map<Node, ?>` 一律用 `Collections.newSetFromMap(new IdentityHashMap<>())` 或 `new IdentityHashMap<>()`，绝不用 `HashSet` / `HashMap`
- 写注释解释**为什么** load-bearing（防止后来人"清理"成普通 HashSet），见 `FieldAccessExtractor.extract()` Pass 1 上方注释
- 单元测试要覆盖"同名 Node 同时出现在写点和读点"组合，否则结构相等 bug 一律抓不到。`FieldAccessExtractorTest.selfAssignment_rhsEmitsRead_andOtherReadsInSameClassSurvive` 是模板

## E15: 写 enrich 类 IT 时不要让 IndexDocsCommand 跑在已索引的 db 上

**情景**: 任何 IT 想同时拿到「nodes/edges 全部 + documents」，会想着先 `IndexCommand` 索引代码、再 `IndexDocsCommand` 索引 markdown。

**坑**: `SqliteStore.initSchema()` 直接执行 `schema.sql` 里的 `CREATE TABLE ...`（无 `IF NOT EXISTS`）。`IndexCommand` 在 db 已存在时跳过 initSchema 改走 DELETE 清表；但 `IndexDocsCommand` **无条件**调用 `initSchema()`，跑在已索引的 db 上时会因 `CREATE TABLE nodes` 报错 `already exists`，整个测试 setup 崩。

**对策**: IT 内不要串联这两个命令到同一个 db；要测 `--with-docs` 路径时，直接用 `SqliteStore.insertDocuments(List<Document>)` 在 `@BeforeAll` 里手工塞一两条 Document。等价覆盖、还更稳定。模板见 `EnrichQueryIT.buildIndex` / `EnrichCommandIT.buildIndex`。

## E16: SemanticPostProcessor 在 IT setup 阶段会自动加 CONVENTION 行

**情景**: 写 annotate / enrich 类 IT，setup 中 `IndexCommand` 跑完后再 `upsertSemanticAnnotation` 一条 LLM 行，断言 "semantic_annotations 表只有 1 条"。

**坑**: `IndexCommand` 在 write 前会跑 `new SemanticPostProcessor().process(result)`，对识别为 BUSINESS_SERVICE / REPOSITORY / CONTROLLER 的类型自动写 source=CONVENTION 的语义注解。OrderService / PaymentService 这种带 "Service" 后缀的类型就会被自动加一条 CONVENTION 行，断言 count==1 永远 fail。

**对策**: IT 断言应按 `(category, source)` 过滤而不是简单 count。`readSemanticAnnotations_returnsSeeded` 模板：`assertTrue(rows.stream().anyMatch(r -> "BUSINESS_SERVICE".equals(r.category) && "LLM".equals(r.source)))`。直接 count 等于固定值的断言一律不可靠。
