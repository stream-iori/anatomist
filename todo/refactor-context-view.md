# Refactor：以 Method Source Context View 为主的 Agent 阅读路径

> 结论性质：待实施方案。

## 结论

对于“理解一个已经定位的方法”，主流程应当是：

```text
定位 class / method
        │
        ▼
读取 method source context view
        │
        ├── 信息足够：直接推理
        ├── 需要关系：查询 callers / callees / field-access
        └── 需要路径证明：按需查询 branches / guards / flow
```

图索引负责定位和缩小范围，源码负责解释代码。不要用更复杂的控制区域结构替代源码阅读。

当前紧凑表达继续保留：

```text
for@L40>if-else@L42
```

它适合作为 Agent 的位置提示和过滤条件，但不应成为理解方法语义的主要证据。

## 第一性原理

| 层次 | 职责 | 最合适的表达 |
|---|---|---|
| 定位 | 找到相关 class、method、field、call edge | 图索引 |
| 理解 | 看条件、参数、局部变量、执行顺序、return/throw | 方法源码 |
| 筛选 | 找循环或分支内的调用和字段访问 | 紧凑 `context` |
| 证明 | 分析可达性、def-use、异常和污点 | CFG / flow |

LLM 能直接理解 Java。已经找到方法后，再把方法体转换成一套更冗长的 block JSON，通常会增加 token，却丢失源码中的命名、条件和顺序信息。

## 当前实现缺口

当前 `context` 不是 source context view。

[`ContextResult`](../src/main/java/com/anatomist/query/ContextResult.java) 只返回：

```text
node
members
annotations
framework
callees（可选）
```

[`TypeContextService`](../src/main/java/com/anatomist/query/TypeContextService.java) 查询成员、注解、框架关系和可选调用关系，没有读取方法体。

当前 [`nodes`](../src/main/resources/schema.sql) 只有字符串 `source_location`，没有声明结束位置，因此无法直接、精确地截取整个方法体。

现有 [`SourceWindowService`](../src/main/java/com/anatomist/query/SourceWindowService.java) 只能围绕单个行号读取固定窗口，适合查看调用点，不适合表达完整方法边界。

```text
当前 context
    method 元数据 + 关系
                × 没有方法体

目标 context --source
    method 元数据 + 精确方法源码 + 可选关系
```

## 目标 Agent 工作流

### 已知 class，查找方法

```bash
anatomist context com.example.OrderService \
  --methods-only \
  --members-limit 50
```

只返回方法清单，避免把字段、方法和全部关系一次性塞给 Agent。

### 已知 method，理解实现

```bash
anatomist context 'com.example.OrderService#createOrder(java.lang.String)' \
  --source
```

需要一层直接调用时：

```bash
anatomist context 'com.example.OrderService#createOrder(java.lang.String)' \
  --source \
  --with-callees=1
```

### 方法过长

```text
method source range
        │
        ├── 未超过限制：返回完整方法体
        └── 超过限制：分页返回，并提供 next_queries
```

不要静默截断，也不要默认返回整个 Java 文件。

## CLI 契约建议

在现有 `context` 上增加：

| 参数 | 含义 |
|---|---|
| `--source` | 返回精确声明范围内的源码 |
| `--source-limit <N>` | 本次最多返回 N 行；默认设置安全上限 |
| `--source-offset <N>` | 从方法体内第 N 行继续读取 |

约束：

- `--source` 只支持可映射到源码声明的 node；
- class 使用时返回 class 声明范围，但默认仍建议先缩小到 method；
- 超出 `source-limit` 时返回 `source_truncated=true` 和下一条查询；
- 源文件与索引 snapshot 不一致时，返回明确的 stale warning，不能把新源码当成旧索引的证据。

## 输出建议

保持紧凑，不输出 AST 树：

```json
{
  "method": {
    "id": "method:OrderService#createOrder(java.lang.String)",
    "qualified_name": "com.example.OrderService#createOrder(java.lang.String)",
    "source_file": "src/main/java/com/example/OrderService.java",
    "source_range": "L38:C5-L57:C6"
  },
  "source": {
    "start_line": 38,
    "end_line": 57,
    "snippet": "38 | public Order createOrder(...) {\n..."
  },
  "callees": []
}
```

设计原则：

| 保留 | 避免 |
|---|---|
| 带行号的原始 Java | 完整 AST JSON |
| 精确方法签名和范围 | 重复输出 class 元数据 |
| 可选的一层 callees | 默认展开多跳调用图 |
| 明确的 truncation | 静默裁剪源码 |
| `next_queries` | 要求 Agent 自己猜分页参数 |

## 数据模型最小改造

优先给源码声明补充精确范围：

```text
nodes / declarations
├── begin_line
├── begin_column
├── end_line
└── end_column
```

继续保留 `source_location`，兼容现有输出；新查询使用整数范围列。

源码正文不写入 SQLite，查询时从当前 checkout 读取：

```text
SQLite：保存范围和 snapshot 证据
文件系统：保存唯一一份源码正文
```

这比默认建立所有 `control_regions` 更轻：

| 方案 | 存储和构建成本 | 直接收益 |
|---|---|---|
| declaration source range | 很低，不需要额外关系图 | 精确读取 class/method 源码 |
| `control_regions` | 预计增加数 MiB 和额外索引 | block 身份、层级和批量分支查询 |
| basic block / CFG | 更高，适合按需构建 | 可达性和路径分析 |

范围列初期不需要单独建立 B-tree 索引。查询先通过 node id 定位，范围只是该行的附属数据。

## `context` 字符串的定位

继续保留：

```text
context = "for@L40>if-else@L42"
```

适用场景：

- `callees-of --in-loop`；
- `callers-of --in-branch`；
- 为调用点提供一眼可读的位置提示；
- 在读取源码前缩小候选边。

不承担：

- 还原完整方法；
- 表达空分支；
- 证明路径可达；
- 代替 CFG；
- 代替源码中的条件表达式。

输出时应在 branch slice 级别只展示一次 context，避免每条内部 edge 重复相同字符串。

## `control_regions` 的新优先级

`control_regions` 从默认前置方案降为有指标驱动的后续能力。

满足以下需求时再引入：

| 需求 | 是否需要 region |
|---|---|
| 精确区分同一行多个分支 | 是 |
| block 需要稳定 id，供后续工具引用 | 是 |
| 查询没有 CALL/READ/WRITE 的空分支 | 是 |
| 跨大量方法做 block 层级聚合 | 是 |
| 只是理解一个已定位方法 | 否 |
| 只是降低 LLM token | 否，先优化输出分组 |

如果未来引入 region，存储仍可结构化，但 Agent 输出继续派生为紧凑路径：

```text
control_regions + innermost FK
              │
              ▼
查询时生成 for@L40>if-else@L42
```

内部准确性和外部低 token 不冲突。

## 实施顺序

```text
P0  声明提取阶段保存 begin/end range
 │
P1  增加 SourceContextService
 │
P2  context --source + 分页 + snapshot warning
 │
P3  优化 JSON，避免 context 和 node 元数据重复
 │
P4  用 Agent 任务评测决定是否建设 control_regions
```

### P0：声明范围

- 从 JavaParser declaration range 直接提取；
- 覆盖 class、interface、enum、record、method、constructor；
- incremental replacement 随源文件一起更新；
- schema migration 保留旧 `source_location`。

### P1：源码读取服务

- 按 begin/end range 读取；
- 每次查询内缓存文件内容；
- 校验文件位于 source root；
- 避免路径穿越；
- 输出带原始文件行号的 snippet。

### P2：查询契约

- JSON 和 Markdown 都支持；
- 大方法分页；
- 返回 total lines、offset、limit、truncated；
- 生成可直接执行的 `next_queries`。

### P3：Agent 输出瘦身

- source view 中不重复输出每条 edge 的相同 context；
- callees 默认关闭；
- class members 支持分页；
- 不默认输出完整文件。

### P4：效果评测

对比以下两种工作流：

```text
A：branches/context 字符串 → Agent 推理
B：定位 method → source context view → Agent 推理
```

至少记录：

| 指标 | 目标 |
|---|---|
| 任务正确率 | B 不低于 A，方法理解类任务应更高 |
| 输入 token | B 在受限源码范围内可控 |
| 工具调用次数 | B 更少 |
| 首次有效证据时间 | B 更短 |
| 长方法截断后的继续查询成功率 | 可稳定续读 |
| stale source 误用 | 0 |

只有当评测显示 Agent 经常需要精确 block 身份、空分支或跨方法 block 聚合时，再把 `control_regions` 提升为默认索引能力。

## 验收条件

- `context <exact-method> --source` 返回完整且行号准确的方法体；
- 重载方法必须使用完整签名消歧；
- class 查询不会默认输出所有方法体；
- 超长方法明确分页，不静默丢失；
- 源码与索引 snapshot 不一致时有明确警告；
- jar 和 native binary 行为一致；
- 新增 JSON 字段有单元测试和 golden scenario；
- 默认索引时间和数据库体积无明显回归；
- 现有 `context`、`callees-of`、`branches-of` 输出保持兼容。

## 最终判断

```text
已定位的方法
    ↓
优先看源码
    ↓
关系图用于导航
context 字符串用于提示
CFG/flow 用于证明
```

对 Agent 而言，源码是理解方法最紧凑、信息最完整的表示。下一步最值得投资的是精确的 method source context view，而不是先扩大 block 图模型。
