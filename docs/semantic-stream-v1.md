# Semantic Stream v1

`semantic-stream/v1` 是 Anatomist 1.0 唯一公开查询契约：UTF-8 NDJSON，每行一个 JSON 对象。

```text
data(seed A)* → evidence(seed A)
data(child B) → evidence(child B) → evidence(parent A)
... → exactly one evidence(scope=stream)
```

每条 data 带 `seed_id`、`index_revision_id`、`source_snapshot_id`、`semantic_profile_id`。扩展操作为每个结果创建 child seed，并保留 `parent_seed_id` 和有界 `derived_from`。

## Evidence

| 字段/状态 | 解释 |
|---|---|
| `coverage=complete` | 在声明的 world、limit 和能力范围内完整 |
| `truncated=true` | 必须扩大 limit 或继续 offset |
| `negative_conclusion_safe=true` | 只有此时空结果才可用于否定结论 |
| `confirmed_empty` | 完整边界内确认无结果 |
| `indeterminate` / non-complete | 缺能力、开放世界、启发式或上游不完整 |

下游不能把上游 `partial`、`unknown` 或 `truncated` 升级为完整。`--accept-unframed` 只为外部 data-only 输入保留，必然降低 coverage。

## 格式与限制

| 项目 | 限制 |
|---|---:|
| 单行 | 1 MiB |
| JSON 深度 | 64 |
| 整条流 record | 1,000,000 |
| 单 seed record | 100,000 |
| seed | 100,000 |
| source lines | 1,000 |

`ndjson` 可流式组合；`json` 会缓冲成终端数组，`table` 仅供人读。Unix 下游提前正常关闭按 broken pipe 成功处理。

## 退出码

| Exit | 含义 |
|---:|---|
| 0 | 成功，包括可信空结果 |
| 1 | 内部或 I/O 错误 |
| 2 | CLI、selector、record type 或输入格式错误 |
| 3 | 索引、schema、语义版本、能力或健康门禁失败 |
| 4 | framing、revision、snapshot 或 profile 冲突 |

能力默认 `--on-unsupported=fail`；`continue` 会发 `UNSUPPORTED_CAPABILITY` evidence，并保持流不完整。

### Fused pipeline 错误

所有只读查询错误使用 `anatomist-error/v1`。`message` 只供人读，Agent 依据稳定的 `code/category/details` 决策。`anatomist pipeline` 把任意规格、组合或 stage 失败映射为 exit 5：

```json
{"contract":"anatomist-error/v1","code":"PIPELINE_STAGE_FAILED","category":"pipeline","exit":5,"message":"...","operation":"pipeline","stage":{"position":2,"operation":"calls"},"cause":{"contract":"anatomist-error/v1","code":"INPUT_TYPE_MISMATCH","category":"argument","exit":2,"message":"...","operation":"calls"}}
```

| code | 含义 |
|---|---|
| `PIPELINE_INVALID_SPEC` | JSON/argv/命令不合法 |
| `PIPELINE_INCOMPATIBLE_STAGES` | 前段输出 record 不能被后段消费 |
| `PIPELINE_STAGE_FAILED` | 某段查询失败；`cause_*` 保留单命令类别 |
| `PIPELINE_LIMIT_EXCEEDED` | stage、argv、spec 或 typed stream 超限 |

失败前 stdout 可能已有完整 seed，但一定没有最终 `evidence(scope=stream)`。

## Operation support、availability 与 coverage

| 层次 | 状态 | 来源 |
|---|---|---|
| provider support | `supported/unsupported` | `operations` 静态目录 |
| index availability | `unchecked/available/unavailable` | `operations --index` / `pipeline --check` |
| query coverage | `complete/partial/unknown/unsupported` | seed/stream evidence |

Operation ID 与语言正交，例如 `calls` + `language=java`，不再公开 `java-call-sites` 形式的复合 ID。

## 1.0 Java 能力

| 操作 | 输入 | 输出 | 边界 |
|---|---|---|---|
| `type-relations` | entity(type) | type_relation | subtype/conformance 分开表达 |
| `runtime-implementations` | entity(type) | entity + proof | 开放世界可能不完整 |
| `callable-relations` | entity(callable) | callable_relation | override/contract，不是调用 |
| `calls` | entity(callable) | call_site | 源码语法与静态 target |
| `dispatch` | call_site | dispatch_target | 静态候选，绝非运行观察 |
| `members` | entity(container) | entity | Java/配置 containment |
| `bindings` | entity | binding_relation | 跨域静态配置关系 |
| `source` | entity/site/dispatch | source_slice | 源码快照校验 |

Spring XML 创建 artifact 根和有序配置实体。抽象、parent、factory 与嵌套 bean 即使没有 class binding 也会保留。

## 兼容性

| 版本 | 政策 |
|---|---|
| 0.1x | 旧 JSON 聚合命令；不属于 1.0 查询契约 |
| 1.0.x | 仅 `semantic-stream/v1`；旧命令无 alias |

1.0 使用 schema 21 / graph semantics 4。旧索引只能通过显式 `index --recreate` 重建。

机器可读 schema：[semantic stream](schema/semantic-stream-v1.schema.json)、[artifact IR](schema/artifact-ir-v1.schema.json)、[operation catalog](schema/operation-catalog-v1.schema.json)、[pipeline plan](schema/pipeline-plan-v1.schema.json) 和 [error](schema/error-v1.schema.json)。对象允许增加字段；未知 record type 或不兼容 major contract 会失败。
