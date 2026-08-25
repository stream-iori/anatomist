# SurrealDB 替代 SQLite 与细粒度代码图模型分析

> 分析日期：2026-08-25
>
> 结论性质：架构调研，不代表已经决定迁移。

## 结论

SurrealDB 能让 anatomist 的代码图谱表达和遍历更加自然，特别适合：

- 多跳调用图、反向依赖和最短路径；
- 代码块、调用点、控制流、数据流之间的细粒度关系；
- 图查询与全文检索、向量检索结合；
- 多项目、多版本、多人共享的代码知识库。

但是，如果 anatomist 的核心定位仍是“单机、离线、一个 native binary、一个本地索引文件”，现阶段不适合直接用 SurrealDB 全面替换 SQLite。

更合理的路线是：

1. 先在现有 SQLite 后端中验证 `code_block / call_site / basic_block` 模型；
2. 把查询层从 JDBC 中抽象出来；
3. 将 SurrealDB 做成可选的图谱或服务端后端；
4. 只有当产品方向明确走向“共享代码知识图谱 + 混合搜索”时，再考虑让 SurrealDB 成为主存储。

核心判断是：

> SurrealDB 最大的提升不是“可以存关系”——SQLite 现在已经存了关系；真正的提升是让关系成为原生查询模型，并把图、文档、全文、向量和实时能力放进同一个引擎。

## 当前 anatomist 的实际模型

本次分析前已同步当前 checkout 的 anatomist 索引，并通过 `integrity` health gate。使用 `--include-tests` 时，当前项目索引大约包含：

- 6,733 个结构节点；
- 44,844 条结构边；
- 342 个源码文件；
- SQLite 文件约 59 MB；
- 当前索引未开启 dataflow，因此 `flow_nodes` 和 `flow_edges` 为空。

现有模型已经是一个建立在 SQLite 上的属性图：

- `nodes` 保存类、方法、字段、lambda、route、bean 等；
- `edges` 保存 `CALLS / CONTAINS / READS / WRITES / IMPLEMENTS / OVERRIDES` 等关系；
- 边已经带有 `confidence`、`context`、`source_location` 和 `metadata`；
- 数据流另有独立的 `flow_nodes / flow_edges` 图；
- `node_names` 和 `doc_content` 使用 SQLite FTS5；
- `analysis_coverage` 和 `index_diagnostics` 保存证据完整性。

相关实现：

- [`src/main/resources/schema.sql`](../src/main/resources/schema.sql)
- [`docs/data-model.md`](../docs/data-model.md)
- [`src/main/java/com/anatomist/store/SqliteStore.java`](../src/main/java/com/anatomist/store/SqliteStore.java)
- [`src/main/java/com/anatomist/store/StagedGraphStore.java`](../src/main/java/com/anatomist/store/StagedGraphStore.java)

因此，SQLite 当前的主要不足不是表达不了图，而是图遍历和组合查询需要大量手写 SQL、Java BFS 和结果拼装。

例如调用图查询目前逐层查询 frontier，并在 Java 中额外处理 override、callback body、去重、深度截断和 evidence disclosure：

- [`src/main/java/com/anatomist/query/CallGraphService.java`](../src/main/java/com/anatomist/query/CallGraphService.java)
- [`src/main/java/com/anatomist/query/FlowQueryService.java`](../src/main/java/com/anatomist/query/FlowQueryService.java)

SurrealDB 可以把其中一部分转换为数据库内原生递归遍历和 shortest-path，但 override、callback 穿透、sanitizer 截断等 anatomist 特有语义仍然需要预先物化或保留应用逻辑。

## SurrealDB 能力与可能收益

### 原生属性图关系

SurrealDB 的 `RELATE` 会创建真正的关系记录，边具有 `in` 和 `out`，并能像普通记录一样保存属性：

```surql
RELATE method:a->calls->method:b
SET call_kind = "INTERFACE",
    confidence = "INFERRED",
    source_line = 42;
```

它支持正向、反向和递归遍历；递归 idiom 还支持收集路径、收集唯一节点和最短路径。

这可能简化：

- `callees-of`；
- `callers-of`；
- `used-by`；
- `hierarchy`；
- `implementors-of`；
- `call-path`；
- `flow-path`。

但不能把它理解成“换数据库后这些功能自动获得”。需要把现有统一 `edges.relation` 模型映射成明确的关系表，例如：

```text
calls
contains
implements
overrides
reads
writes
next
guard_true
guard_false
def_use
```

如果仍然只使用一个通用 `edge` 表，并在属性中保存 `relation="CALLS"`，会失去一部分 SurrealDB 原生图语法、关系类型约束和 Studio 可视化能力。

### 边记录可以承载证据

代码关系边可以直接保存：

```text
call_kind
confidence
resolution
source_range
control_context
dispatch_kind
snapshot
evidence
```

这很适合代码分析，但 anatomist 当前 SQLite `edges` 已经能保存这些属性。因此这一项主要改善的是关系约束、遍历语法和嵌套结果返回，而不是从无到有的表达能力。

### 图、全文和向量统一查询

SurrealDB 当前提供：

- BM25 全文索引；
- HNSW 向量索引；
- DISKANN 向量索引；
- 结构过滤、图遍历和向量相似度的组合。

这可能是对 anatomist 最有产品价值的提升。例如可以表达：

```text
找出语义上与“订单幂等校验”相似的方法
→ 限定在从 POST /orders 可达的调用子图中
→ 排除测试代码
→ 优先返回写数据库且位于条件分支中的代码块
```

SQLite FTS5 能完成名称和文档检索，但向量搜索、图约束和全文排名组合起来需要更多应用侧拼接。

需要注意，SurrealDB 的全文索引一次索引一个字段；当前 anatomist 的 FTS5 把 `qualified_name / label / javadoc` 组织在同一个虚表中。迁移后的召回、排序和高亮契约不能假设与现状等价。

### 服务化和多项目能力

SurrealDB 可以从嵌入式模式扩展到远程单机或分布式部署，并提供权限、session、changefeed、live query 等能力。

适合未来的：

- 团队共享代码图谱；
- 多仓库跨项目调用关系；
- Web 图谱浏览器；
- 索引进度和变化推送；
- 持续更新的代码知识库；
- 不同项目之间的权限隔离。

`LIVE SELECT` 可以帮助 UI 感知数据库变化，但不能代替文件监听、Java 解析和增量影响分析。当前官方文档还说明 live query 只支持单节点，跨写入者的通知顺序也不是严格全局提交顺序。

## Code block 建模建议

### Code block 应该是节点，而不是关系

Code block 具有自己的身份和属性：

```text
kind: IF_THEN / IF_ELSE / LOOP_BODY / CASE / CATCH / FINALLY / BASIC_BLOCK
source_range
normalized_hash
parent_method
nesting_depth
control_role
snapshot
```

因此，应建立 `code_block` 节点，再通过关系连接其他实体：

```text
method    -> contains       -> lexical_block
block     -> contains       -> nested_block
block     -> contains       -> call_site
block     -> true_next      -> block
block     -> false_next     -> block
block     -> loop_back      -> block
block     -> exception_next -> block
call_site -> invokes        -> method
block     -> reads          -> field
block     -> writes         -> field
definition-> def_use        -> use
condition -> guards         -> block
```

### 分成四层图

```text
Symbol Graph
  类、方法、字段、Bean、Route
  稳定、规模较小、用于日常查询

Control Graph
  lexical block、basic block、call site、branch
  更细、更容易随源码变化

Flow Graph
  parameter、definition、use、return、throw、taint
  高基数、按需构建

Evidence Graph
  file、source range、diagnostic、document、annotation
  用于解释“为什么存在这条事实”
```

跨层保留方法级汇总边：

```text
method A -> CALLS -> method B
```

但这条边应当能够追溯到：

```text
method A
  -> contains call_site
  -> located_in block
  -> invokes method B
```

这样普通 `callees-of` 不需要扫描大量细粒度节点，而 `branches-of`、guard 和 taint 查询又可以深入调用点。

### 何时需要 call_site 节点

如果调用关系只需要行号、置信度和 call kind，把这些数据放在关系边上就足够。

如果需要表达以下事实，则 `call_site` 应成为节点：

- 调用点位于哪个 block；
- 受哪个 condition 控制；
- 参数来自哪些 definition；
- 返回值流向哪里；
- 属于哪个 catch、loop 或 case。

原因是这时需要“关系指向一次调用事件”，简单的 method-to-method 边已经不够。

### 不要第一步索引所有 AST 表达式

全面 AST 图会导致：

- 节点数量膨胀一个数量级；
- 索引时间和磁盘占用明显上升；
- 源码轻微编辑导致大量身份漂移；
- 查询结果被低价值 AST 噪声淹没。

第一阶段只建议建立：

- 分支 block；
- loop block；
- catch/finally block；
- basic block；
- call site；
- return/throw；
- field read/write site。

当前 [`FlowAnalyzer.java`](../src/main/java/com/anatomist/flow/FlowAnalyzer.java) 已经生成 parameter、condition、return、throw、control、local definition、call result 等节点，可以在现有可选 dataflow 图上扩展，不需要先迁移数据库。

### Block 身份与版本

方法的逻辑身份相对稳定，但 block 的行号和 AST path 会频繁变化。不要把跨版本稳定性完全寄托在线号上。

建议区分：

- 当前 snapshot 内的 block storage id；
- 由 parent method、block role、normalized token hash、AST path 等生成的匹配指纹；
- 跨 snapshot 的 `evolves_to` 或匹配结果。

如果未来 SurrealDB 的主要价值包含多版本图谱，应进一步拆分：

```text
logical_symbol
symbol_version
snapshot
```

结构和控制关系连接 `symbol_version`，避免不同版本关系混在同一个 symbol 上。

## 主要风险与迁移成本

### Java native-image 是最大的技术风险

SurrealDB Java SDK 的嵌入模式通过 JNI 运行 Rust 引擎，磁盘模式使用 `surrealkv://`。官方 Java SDK 支持一组 JDK 和操作系统架构，但未找到官方 GraalVM native-image 兼容承诺。

必须区分：

```text
支持在 JDK 21/25 上运行
不等于
支持被编译进 GraalVM native image
```

anatomist 已经为 sqlite-jdbc、JavaParser 和 picocli 维护了大量 reachability、JNI 和 native resource 配置。切换到 Rust + JNI 的 SurrealDB SDK，很可能需要重新解决：

- native library 打包；
- JNI 注册；
- 多平台交叉发布；
- native-image 链接；
- binary size；
- 启动时解压与加载行为。

在证明 `just native` 可行之前，这应被视为迁移 blocker。

### 嵌入式持久化成熟度

SurrealDB 官方架构文档当前仍将 SurrealKV 标注为 beta，而 Java 磁盘嵌入使用的正是 SurrealKV。官方对生产单节点更倾向于 RocksDB 服务模式。

因此，“SurrealDB embedded 可以无风险地替代 SQLite”目前不能作为前提。

### 当前 staging/promotion 机制需要重做

anatomist 目前使用 file-backed SQLite staging sidecar，将解析结果分批落盘，再通过 `ATTACH DATABASE` 和目标事务进行 promotion：

```text
parse/extract
  -> staging sidecar
  -> SQL graph finalization
  -> index.db transaction
```

这保证：

- 解析失败时旧索引不变；
- finalize 失败时旧索引不变；
- promotion 失败时整体回滚；
- 高基数 flow facts 不需要常驻 Java heap。

SurrealDB 有 ACID 事务，但使用 snapshot isolation，而不是 serializable，并明确存在 write-skew 可能。虽然 anatomist 当前通常是单写入者，这不是主要并发问题，但 staging、promotion、崩溃恢复和后台 rebuild 都需要重新设计。

另外，Java SDK 当前在 transaction 中不支持 `queryBind()`；动态值需要使用 `LET` 或写入 SurrealQL 文本。大量节点和边的安全批量导入必须专门验证。

可能的替代设计包括：

- 在临时 namespace/database 中构建完整 snapshot，再切换 current pointer；
- append-only snapshot records，事务中只切换项目当前 snapshot；
- 在同一数据库中写入带 snapshot id 的新图，验证后更新 current snapshot；
- 对增量索引继续使用受影响文件集合，在事务中删除并重建对应 version records。

这些方案都会增加空间、清理和查询 scope 管理复杂度。

### 查询与测试迁移规模很大

当前至少有 27 个 production Java 文件直接依赖 JDBC/SQL，覆盖：

- store；
- query；
- flow persistence；
- incremental replacement；
- CLI annotation；
- schema、health 和 coverage。

此外 SQLite 数据库文件本身是当前 CLI 契约：

```text
--index /path/to/index.db
```

SurrealKV 使用目录型持久化，备份通常变成 SurrealQL export/import。脚本、测试、doctor、默认 locator 和用户调试方式都会变化。

### 外部符号必须实体化

当前外部方法没有 `nodes` 行，只存在于：

```text
edges.external_target_fqn
```

而原生图关系最好指向真实 record。SurrealDB 的 `RELATE` 默认可以连接不存在的记录；若要严格完整性，需要定义 `ENFORCED` relation table。

迁移时最好显式创建：

```text
external_symbol
external_method
```

否则外部依赖的双向图遍历会产生语义缺口。这会增加节点数量，但也能消除当前 internal/external reverse-query 的双路径实现。

### 许可证变化

SQLite 基本没有产品分发限制；SurrealDB 3.0 核心使用 BSL 1.1，不属于 OSI Open Source。

其附加授权允许在自己的应用中嵌入、用于生产和分发，但限制把 SurrealDB 作为商业数据库服务提供给第三方。普通 anatomist CLI 通常不受影响，但如果未来产品允许第三方管理数据库 schema/table，需要进行专门许可证审查。

## 推荐实施路线

### 第一阶段：不换数据库，先证明 block 模型

直接扩展现有 `flow_nodes / flow_edges`：

```text
FlowNode kind += LEXICAL_BLOCK, BASIC_BLOCK, CALL_SITE

FlowEdge relation += CONTAINS_BLOCK,
                     TRUE_NEXT,
                     FALSE_NEXT,
                     LOOP_BACK,
                     EXCEPTION_NEXT,
                     LOCATED_IN
```

将它放在 `--dataflow` 或 scoped dataflow 后面，避免默认索引膨胀。

这一步应先回答：

> Code block 成为一等图实体后，Agent 查询质量到底提高了多少？

如果价值不明显，换 SurrealDB 也不会创造价值。

### 第二阶段：抽象查询能力，而不是抽象 SQL

现有 `IndexWriter` 已经提供了写入侧缝隙，但 query 层仍直接持有 `Connection`。

建议定义面向能力的端口：

```text
SymbolResolver
GraphTraversal
BranchQuery
FlowTraversal
HybridSearch
SnapshotStore
```

不要实现一个模拟 JDBC 的通用 DAO。SQLite 和 SurrealDB 在递归遍历、全文、事务和返回结构上差异太大，统一成最低公共 SQL 接口会丢失 SurrealDB 的优势。

### 第三阶段：SurrealDB sidecar spike

保持 SQLite 为权威索引，把同一批 `Node / Edge / FlowNode / FlowEdge` 镜像到 SurrealDB，只实现以下对照场景：

1. `callees / callers / call-path`；
2. `branches-of + block traversal`；
3. `flow-path / taint-path`；
4. 图约束下的全文和向量混合搜索。

必须比较：

- full index 时间；
- incremental index 和 promotion 时间；
- 冷、热查询 p50/p95；
- 深度 5、10、20 的 traversal；
- 磁盘占用；
- 峰值 RSS；
- crash consistency；
- golden JSON 等价性；
- JVM jar 和 Graal native 构建；
- 多平台发布体积。

不能仅根据“原生图数据库”推断性能一定更好。当前 anatomist 的图规模对 SQLite 并不大，SurrealDB 的收益更可能先体现在查询组合能力和代码简化上，而不是单查询延迟。

### 最可能成功的最终架构

```text
本地 CLI / native binary
    SQLite：权威、离线、单文件、成熟

可选 server/team mode
    SurrealDB：多项目、多版本、图遍历、全文、向量、实时 UI
```

如果未来 anatomist 变成 code intelligence server，SurrealDB 的价值会快速超过迁移成本。

如果它继续是 Agent 调用的本地 IDEA companion，SQLite 仍然更匹配；此时最值得投资的是 block、call-site 和 CFG 模型，而不是数据库替换。

## 决策条件

满足以下多数条件时，可以考虑 SurrealDB 主后端：

- 需要长期保存多个项目和多个 snapshot；
- 需要团队共享和权限控制；
- 需要 Web 图谱和实时更新；
- 需要图约束下的全文/向量混合检索；
- 单机 SQLite 查询代码已成为明显维护瓶颈；
- Java/GraalVM native-image spike 已通过；
- SurrealKV 或目标 server 部署达到要求的稳定性；
- staging、增量 promotion 和故障恢复已证明等价。

如果主要需求只是“让 code block 可以建立关系”，不应以更换数据库作为前置条件。这个能力首先取决于 extractor 和图模型，而不是存储引擎。

## 官方资料

- [SurrealDB RELATE](https://surrealdb.com/docs/reference/query-language/statements/relate)
- [Recursive traversals](https://surrealdb.com/docs/learn/data-models/graph/recursive-traversals)
- [Recursive idioms、path、collect 与 shortest path](https://surrealdb.com/docs/reference/query-language/language-primitives/idioms)
- [DEFINE INDEX：全文、HNSW、DISKANN](https://surrealdb.com/docs/reference/query-language/statements/define/indexes)
- [SurrealDB architecture](https://surrealdb.com/docs/architecture)
- [Transactions and isolation](https://surrealdb.com/docs/transactions-and-isolation)
- [Java embedded databases](https://surrealdb.com/docs/reference/java/concepts/embedded-databases)
- [Java transactions](https://surrealdb.com/docs/reference/java/concepts/transactions)
- [Java SDK repository](https://github.com/surrealdb/surrealdb.java)
- [LIVE SELECT](https://surrealdb.com/docs/reference/query-language/statements/live-select)
- [SurrealDB licensing](https://github.com/surrealdb/license)
