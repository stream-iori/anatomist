# Elasticsearch + 图数据库的代码智能架构分析

> 分析日期：2026-08-25
>
> 结论性质：长期架构调研，不代表已经决定同时引入两个外部数据库。

## 结论

Elasticsearch 与图数据库组合后，能力会更加完整，但不一定更加简单或更适合当前阶段。

如果职责划分清楚，这是一套很强的代码智能架构：

```text
Elasticsearch
  负责“找到什么可能相关”
  全文、模糊、向量、混合排序、聚合、代码块召回

图数据库
  负责“它们是否真的相关”
  调用路径、反向影响、继承、控制流、数据流、最短路径
```

典型查询过程：

```text
用户问题
  ↓
Elasticsearch 语义/关键词检索
  ↓ 候选 method/block IDs（例如 Top 200）
图数据库精确过滤与扩展
  ↓ 可达路径、调用关系、guard、证据
应用层综合 ES 分数与图路径
  ↓
最终 Agent 上下文
```

真正决定系统质量的不是“用了两个数据库”，而是：

1. 两边职责没有含糊重叠；
2. 两边读取同一个 immutable snapshot；
3. 不进行双向同步；
4. 跨数据库候选集合有严格上限；
5. 图结论保留 source evidence；
6. 任一后端失败时都不会发布半成品快照。

对于当前 anatomist，建议先做 `SQLite + Elasticsearch`，验证 code block 混合检索的收益。只有图规模和图查询复杂度证明现有 Java BFS 已成为瓶颈后，再增加专门图数据库。

## 两类数据库的职责

### Elasticsearch：搜索平面

Elasticsearch 适合承担：

- Java symbol 精确搜索；
- BM25 全文搜索；
- analyzer/tokenizer；
- 模糊匹配和高亮；
- dense/sparse vector；
- kNN；
- RRF 混合排序；
- semantic reranking；
- project/module/scope/kind 过滤；
- 聚合、统计和候选召回。

它解决的是：

> 哪些 method、code block、document chunk 最可能与用户问题相关？

### 图数据库：关系平面

图数据库适合承担：

- `CALLS` 正向和反向遍历；
- `OVERRIDES / IMPLEMENTS / INHERITS`；
- route 到 repository 的可达路径；
- code block 嵌套和控制流；
- shortest path；
- def-use；
- exception flow；
- taint path；
- 多种关系混合遍历。

它解决的是：

> 候选代码之间是否存在精确的静态关系，路径和证据是什么？

### anatomist：编排与证据平面

anatomist 仍然负责：

- Java 解析和 SymbolSolver；
- framework analyzer；
- source identity；
- block、call-site、CFG 和 flow fact 抽取；
- override 和 callback 等 Java 特有语义；
- evidence coverage；
- source window；
- 查询边界、深度和 truncation disclosure；
- 跨 Elasticsearch 和图数据库的查询编排。

数据库不能代替这些静态分析工作。

## 混合查询示例

用户问题：

> 找出语义上与“订单幂等保护”相似，并且能够从 `POST /orders` 到达、最终写数据库的代码。

推荐执行方式：

1. Elasticsearch 搜索与“订单幂等保护”相似的 method 或 code block；
2. 只保留 Top 100～500 个候选 ID；
3. 图数据库检查候选所属方法是否可从 `POST /orders` route 到达；
4. 沿 `CALLS / LOCATED_IN / WRITES` 等关系返回精确路径；
5. 应用层保留 Elasticsearch 相关性分数并结合路径长度、confidence 排序；
6. anatomist 返回源码窗口和 evidence coverage。

结果同时具备：

- 语义相关性；
- 静态可达性；
- 数据库写入证据；
- 路径和源码位置；
- 完整性披露。

## Code block 在两个系统中的投影

同一个 code block 在两边承担不同职责。

### Elasticsearch document

```json
{
  "block_id": "block:abc",
  "method_id": "method:checkout",
  "kind": "IF_THEN",
  "project_id": "shop",
  "snapshot_id": "sha256:...",
  "module": "service",
  "scope": "MAIN",
  "source_file": "OrderService.java",
  "start_line": 42,
  "end_line": 47,
  "source": "if (existing != null) { return existing; }",
  "branch_path": ["if-then@L42"],
  "called_method_ids": ["method:findExisting"],
  "written_field_ids": [],
  "embedding": []
}
```

用于：

- BM25；
- semantic search；
- 高亮；
- block kind 过滤；
- source/package/module 过滤；
- 相关性排序。

### 图数据库节点和关系

```text
method:checkout
  -> CONTAINS
block:abc
  -> GUARDED_BY
condition:existing_not_null
  -> TRUE_NEXT
block:return_existing

block:abc
  -> INVOKES
method:findExisting
```

用于：

- 分支结构；
- 可达性；
- 调用路径；
- CFG；
- def-use；
- taint；
- shortest path。

不需要在 Elasticsearch 中展开完整 CFG，也不必在图数据库中复制用于 BM25 的全部倒排索引结构。

## 统一快照是核心约束

双数据库架构最大的风险是：

```text
Elasticsearch 已经是 snapshot B
图数据库仍然是 snapshot A
```

可能导致：

- Elasticsearch 返回新方法，但图数据库找不到；
- 图数据库返回旧调用路径，但源码已经变化；
- block ID 因源码移动失效；
- Agent 把不同 commit 的事实组合在一起；
- 一个后端认为查询为空，另一个后端仍保留旧节点。

因此不建议普通的“双写后异步同步”，更不能进行双向同步。

### 两边都作为同一事实快照的投影

最终事实来源应当是：

```text
Git source snapshot
    +
anatomist extraction result
```

而不是 Elasticsearch 或图数据库中的任意一个。

推荐发布流程：

```text
源码 snapshot S
  ↓
解析并生成中立 GraphFacts
  ├── 写 ES generation S
  └── 写 Graph generation S
        ↓
分别校验
  - node/edge/block 数量
  - dangling edge
  - checksum
  - health/coverage
  - smoke queries
        ↓
两个后端都 READY
        ↓
发布 current_snapshot = S
```

每条记录都必须包含：

```text
project_id
snapshot_id
source_snapshot_fingerprint
schema_version
analysis_profile
```

查询 API 在开始时确定一个 `snapshot_id`，之后 Elasticsearch 与图数据库查询必须固定使用同一个 snapshot，不能分别读取各自的“最新”。

如果任一后端构建失败：

```text
current_snapshot 继续指向旧版本
```

旧 generation 延迟清理，以支持回滚和在线查询。

## 不要进行跨数据库大规模 JOIN

危险模式：

```text
图数据库返回 500 万个可达节点 ID
→ 全部发送给 Elasticsearch terms query
```

或者：

```text
Elasticsearch 返回几十万个候选
→ 逐个查询图数据库
```

这会带来：

- 大请求体；
- 高内存占用；
- 网络往返；
- query clause 限制；
- 排序和分页困难；
- timeout 和部分失败。

### 搜索优先

适合自然语言问题：

```text
Elasticsearch Top 100～1000
→ 图数据库验证、过滤、展开
```

### 图优先

适合明确结构问题：

```text
图数据库获得小型调用子图
→ Elasticsearch 对这些节点排序或补充语义结果
```

### 高频图属性物化到 Elasticsearch

可以将常用的结构摘要冗余到 Elasticsearch：

```text
fan_in
fan_out
is_entrypoint
route_ids
writes_database
branch_kinds
package_layer
implements_types
reachable_from_entrypoints（受控数量）
```

这些字段只是搜索加速投影，不是权威图事实。它们必须能够从相同 snapshot 的图事实重建。

## SurrealDB 与专门图数据库的选择

如果已经确定使用 Elasticsearch，SurrealDB 的一部分能力会与之重叠：

- 全文索引；
- 向量索引；
- 文档模型；
- 实时订阅；
- 部分聚合和过滤。

此时选择 SurrealDB 的核心理由应当是：

- 原生 relation record；
- 双向遍历；
- 递归路径；
- shortest path；
- 图和普通记录的统一事务；
- 希望图数据与项目元数据使用同一查询语言。

换句话说：

> Elasticsearch + SurrealDB 可以成立，但 SurrealDB 的 multi-model 优势会被 Elasticsearch 覆盖一部分。

如果图查询已经成为独立核心能力，也可以评估专门图数据库。不能只看通用社交图 benchmark，应使用 anatomist 的真实查询测试：

- 深度不超过 20 的有界 BFS；
- 高频反向 `CALLERS`；
- `CALLS + OVERRIDES + callback` 混合路径；
- CFG shortest path；
- def-use/taint path；
- project/snapshot 强制隔离；
- 增量替换一个 source file 的子图；
- 大量高 fan-out 方法；
- evidence 和 source location 返回成本。

## 运维和工程成本

两个外部数据库意味着两套：

- 部署；
- 认证和权限；
- TLS 和 secret；
- 监控和告警；
- 备份恢复；
- schema migration；
- 容量规划；
- 性能调优；
- 客户端兼容；
- 故障排查；
- snapshot garbage collection。

同时会出现更多故障组合：

```text
ES 可用 / Graph 不可用
ES 不可用 / Graph 可用
两边可用 / generation 不一致
两边一致 / source object 不可用
```

因此查询 API 必须明确降级策略：

- search-only 是否可以返回；
- graph-only 是否可以返回；
- 缺少图验证时是否必须标记结果；
- evidence coverage 不完整时是否允许负面结论；
- snapshot 不一致时应直接失败还是降级。

## 推荐实施阶段

### 第一阶段：SQLite + Elasticsearch

```text
Java source
    ↓
anatomist extractor
    ├── SQLite
    │     精确、本地、离线、增量、一致性、native CLI
    │
    └── Elasticsearch projection
          全文、向量、混合召回、聚合、团队共享
```

目标：

- 验证 method/block 级混合检索；
- 建立稳定 snapshot id；
- 建立中立 GraphFacts；
- 测量 Agent 召回质量提升；
- 保持现有 CLI 和图查询契约不变。

Agent 查询：

```text
1. Elasticsearch 找到候选 method/block
2. anatomist/SQLite 精确图遍历验证
3. 返回 source window 和 evidence
```

### 第二阶段：抽象查询能力

建议抽象：

```text
GraphTraversal
HybridSearch
SnapshotPublisher
EvidenceStore
SourceResolver
```

使以下实现可以并存：

```text
GraphTraversal
  ├── SQLiteGraphTraversal
  └── RemoteGraphTraversal

HybridSearch
  ├── SqliteFtsSearch
  └── ElasticsearchHybridSearch
```

不要构造一个模仿 JDBC 的最低公共 DAO；应保留每个后端的原生能力。

### 第三阶段：满足触发条件后加入图数据库

出现以下多数信号后再引入：

- 多仓库图达到百万或千万级边；
- Java BFS 网络往返成为主要延迟；
- shortest-path 和路径过滤成为高频查询；
- 需要多人共享和并发图查询；
- 图算法成为产品核心；
- Elasticsearch 中的结构冗余越来越难维护；
- SQLite 单项目索引不再满足集中式服务需求；
- 已完成双后端 snapshot publisher 和故障恢复验证。

## 最可能的长期架构

```text
Git / Source Object Store
  源码平面
        │
        ▼
anatomist Extractor + Snapshot Publisher
  抽取、证据和发布平面
        │
        ├───────────────┐
        ▼               ▼
Elasticsearch       Graph Database
搜索平面             关系平面
        │               │
        └───────┬───────┘
                ▼
       anatomist Query Orchestrator
                ▼
              Agent
```

但这应当是需求和规模驱动的结果，而不是一开始的默认架构。

## 最终建议

1. Elasticsearch 与图数据库组合在能力上非常强；
2. Elasticsearch 负责候选召回和相关性，图数据库负责精确关系与路径；
3. code block 在 Elasticsearch 中是可搜索文档，在图数据库中是一等节点；
4. 两个数据库都应由同一 immutable extraction snapshot 构建；
5. 不做双向同步，不把任一数据库当作唯一不可重建事实源；
6. 跨库只传递有界候选 ID，不做大规模 JOIN；
7. 当前先落地 `SQLite + Elasticsearch`；
8. 图数据库等真实图规模、延迟和产品需求证明必要后再加入。

## 官方资料

- [Elasticsearch Hybrid search](https://www.elastic.co/docs/solutions/search/hybrid-search)
- [Elasticsearch Vector search](https://www.elastic.co/docs/solutions/search/vector)
- [Elasticsearch join field 与反规范化建议](https://www.elastic.co/docs/reference/elasticsearch/mapping-reference/parent-join)
- [Elasticsearch Graph Explore API](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-graph-explore)
- [Elasticsearch Bulk API](https://www.elastic.co/guide/en/elasticsearch/reference/current/docs-bulk.html/)
- [Elasticsearch near real-time search](https://www.elastic.co/docs/manage-data/data-store/near-real-time-search)
- [Elasticsearch optimistic concurrency control](https://www.elastic.co/docs/reference/elasticsearch/rest-apis/optimistic-concurrency-control)
- [Elasticsearch aliases](https://www.elastic.co/guide/en/elasticsearch/reference/current/aliases.html)
- [SurrealDB RELATE](https://surrealdb.com/docs/reference/query-language/statements/relate)
- [SurrealDB recursive traversals](https://surrealdb.com/docs/learn/data-models/graph/recursive-traversals)
