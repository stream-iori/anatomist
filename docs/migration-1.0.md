# 从 0.1x 迁移到 1.0

结论：1.0 是一次有意的大版本断点。旧索引必须重建，旧查询命令必须改成 NDJSON 原子管道；没有兼容别名。

## 升级步骤

```text
备份人工来源 ──> 安装 1.0 ──> index --recreate ──> doctor ──> 改写查询 ──> 验证 evidence
```

```bash
anatomist --version
anatomist index /path/to/project --recreate --output /path/to/index.db
anatomist doctor --agent-preflight --format json --index /path/to/index.db
```

`--recreate` 会删除旧 SQLite 及 sidecar。源码、配置和可重建文档不需要保留数据库副本；人工 `annotate` 数据必须先从其来源备份。未传 `--recreate` 时，1.0 只报错和待丢弃数量，不会静默重建。

## 命令映射

| 0.1x | 1.0 |
|---|---|
| `context Type` | `resolve Type --kind type --unique \| describe`，成员另接 `members` |
| `context 'Type#method()' --source` | `resolve ... --kind callable --exact --unique \| source` |
| `context --with-callees` / `callees-of` | `resolve callable \| calls [\| dispatch]`；多跳用 `trace` |
| `callers-of` | `resolve callable \| calls --direction incoming` |
| `branches-of` | `resolve callable \| regions \| sites-in` |
| `bean-config` | `search --kind artifact/component \| resolve \| members`，或 `bindings` |
| `hierarchy` | `resolve type \| type-relations` |
| `implementors-of` | `resolve type \| runtime-implementations` |
| `deps-of` | `resolve type \| members --recursive \| references --direction outgoing` |
| `used-by` | `resolve \| references --direction incoming`，调用者则用 incoming `calls` |
| `field-access` | `resolve value \| accesses --mode all` |
| `call-path` | `resolve callable \| trace --to ... --dispatch resolved\|possible` |
| `survey-baseline` | `overview` + `doctor --format json` |

## 输出变化

| 0.1x | 1.0 |
|---|---|
| 聚合 JSON v2 envelope | 一行一条 `semantic-stream/v1` record |
| 一个命令混合实体、调用、源码 | 每段只做一种语义操作 |
| `stats.total` | `result_count` 或 seed/stream `evidence` |
| `next_queries` 字符串 | 用 `limit/offset` 明确续页，并检查 `truncated`/coverage |
| 调用边与调用点重复存储 | 字典化的 `call_site_owners` + `call_sites` + targets |

`json` 和 `table` 仍可用于终端展示，但不能作为中间管道。新脚本优先用 `anatomist pipeline --index <db> -- ... --then ...`；原 Unix 管道继续支持，使用时应启用 `set -o pipefail` 并把同一个 `--index` 传给每段。

## 兼容性验收

迁移不是要求 1.0 输出与旧 JSON 字节一致，而是要求旧场景都有明确 recipe，并由新协议验证事实与 evidence：

| 门禁 | 命令 |
|---|---|
| JUnit + 集成测试 | `mvn clean test` |
| 真实 pipeline golden | `mvn -Dtest=GoldenFileIT test` |
| JVM/native 对拍 | `just native-smoke` |
| 扩展生命周期 | `just extension-e2e-jvm` / `just extension-e2e-native` |
| Agent E2E 合同与 fixture | `just agent-e2e-contract` / `just agent-e2e-fixture` |
| 已安装旧版的性能和 DB 体积 | `just bench-query-refactor` |
| 可复现旧提交基线 | `just bench-query-refactor-git <baseline-ref>` |
| 1.0 pipeline 引擎优化 | `just bench-semantic-pipeline <baseline-ref>` |

1.0 的 `calls` 只证明源码调用语法及静态解析，`dispatch` 只给可能目标；Spring `INJECTS/WIRES` 用于缩小候选，不会制造不存在的调用事实。
