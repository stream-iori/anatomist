# Semantic Stream v1

`semantic-stream/v1` 只有一种 NDJSON 结构，不兼容旧的逐记录 identity 格式：

```text
stream_header(identity)
data(seed A)*
evidence(scope=seed, seed A)
...
evidence(scope=stream)
```

| 规则 | 含义 |
|---|---|
| 首条必须是 `stream_header` | contract 和 index/source/profile identity 只出现一次 |
| 一个逻辑输入一个 seed | 多个结果共用 seed，不创建结果级 child seed |
| 每个 seed 一条 evidence | 正常结果不重复确认 |
| 最后一条必须是 stream evidence | 缺失即失败，不能使用部分 stdout 下结论 |
| 普通记录禁止 identity/parent 字段 | 避免新旧格式混用 |

`json` 是终端 envelope：`contract + identity + results[] + evidence`；`table`
只供人阅读。只有 NDJSON 能进入下一条命令。

## Evidence

| 字段 | 规则 |
|---|---|
| `coverage=complete` | 声明的能力、world 和 limit 内完整 |
| `truncated=true` | 必须继续分页或扩大 limit |
| `status=empty` | 当前 seed 没有结果 |
| `negative_conclusion_safe=true` | 只有此时空结果可支持否定结论 |

下游不能把 partial、unknown、unsupported 或 truncated 升级为完整。
`--accept-unframed` 只允许 transform 接受“有 header 但没有 evidence”的数据，
输出 coverage 必须降为 unknown。producer 不暴露该参数。

## 关系与调用

`relationship_id=rel:sha256:<64 hex>` 表示与位置无关的关系事实；`id` 表示具体
源码出现位置。`calls` 只给源码调用点及静态 target；`dispatch` 显式扩展可能的
虚调用目标，不能证明运行时执行。

## 限制与退出码

| 项目 | 限制 |
|---|---:|
| 单行 | 1 MiB |
| JSON 深度 | 64 |
| 整条流 record | 1,000,000 |
| 单 seed record | 100,000 |
| seed | 100,000 |

| Exit | 含义 |
|---:|---|
| 0 | 成功，包括可信空结果 |
| 1 | 内部或 I/O 错误 |
| 2 | CLI、selector、record type 或 JSON 错误 |
| 3 | 索引、schema、能力或健康门禁失败 |
| 4 | header、evidence、revision、snapshot 或 profile 冲突 |
| 5 | fused pipeline 规格、组合或 stage 失败 |

Schema 24 的旧索引必须通过 `index <project> --recreate` 重建，不提供迁移。
机器契约见 [JSON Schema](schema/semantic-stream-v1.schema.json)。
