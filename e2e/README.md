# Anatomist Agent E2E

这些用例验证真实 Agent 是否正确使用 Anatomist 证据，不重复验证节点、边、SQLite
或 native/JVM 一致性。

```text
自然语言问题 → Codex SDK → anatomist skill/CLI → 结构证据 → Agent 结论
                    Jury 记录 trace ────────────────┘
```

## 场景

| Case | 验证风险 | 确定性 Oracle |
|---|---|---|
| `anatomist-stale-index-repair` | 使用旧索引直接下结论 | `resolve | source`，最终 index 新鲜 |
| `anatomist-lombok-evidence-boundary` | 把调用点推断说成完整生成声明 | `resolve | calls | source` + `lombok_usage` boundary |
| `anatomist-static-runtime-boundary` | 把静态可达路径说成线上已执行 | `resolve | regions | sites-in` + `resolve | source` |
| `anatomist-complex-business-path` | 同名类型、跨模块路径和 Spring 候选被混淆 | annotations、calls/dispatch、artifact members 管道 |
| `anatomist-complex-paged-flow` | 只读源码第一页就下完整结论 | `resolve | source --offset` continuation + regions/sites-in |
| `anatomist-complex-callback-impact` | 漏掉传递实现、字段、XML 或 lambda 调用 | type/runtime、calls/source、accesses、artifact members 管道 |
| `anatomist-complex-change-closure` | Agent 改码后没有测试、重索引或复核 | dispatch 影响、限定 diff、测试、增量索引和 `resolve | source` |
| `anatomist-semantic-type-dispatch` | 新类型/dispatch 管道退化为旧命令 | `search | resolve | runtime-implementations | source` 与 `resolve | calls | dispatch` |
| `anatomist-semantic-artifact` | 新 Artifact IR 管道退化为旧命令 | `search | resolve | members` 与 `search | resolve | bindings` |

Prompt 以用户问题为主，并明确指定该场景必须走的 canonical NDJSON 管道。索引状态、
真实 pipe trace、stream evidence、Git 和固定源码共同构成 Oracle；命令清单不能替代
结果证据。

## 前置条件

| 依赖 | 要求 |
|---|---|
| Anatomist | `target/anatomist` 可执行；也可设置 `ANATOMIST_E2E_BIN` |
| Jury | Jury 1.x；`JURY_BIN` 可覆盖命令路径 |
| Codex | CLI 已登录，Jury 使用 `codex_sdk` runner |
| Skill | 当前用户已安装 `anatomist` skill |

例如使用相邻源码仓库里的 Jury：

```bash
export JURY_BIN=/path/to/jury/.venv/bin/jury
just agent-e2e-contract
just agent-e2e-fixture
just agent-e2e-smoke
```

只跑一个 Case：

```bash
"$JURY_BIN" run e2e/cases/anatomist-lombok-evidence-boundary.yaml \
  --cwd e2e \
  --runs-dir e2e/jury-runs \
  --adapter anatomist_jury_adapter:create_adapter
```

`--cwd e2e` 很重要：Jury isolated 模式只复制这个小目录，不复制整个仓库和
`target/`。adapter 把当前 native binary 所在目录放入 Agent PATH，并把 Agent cwd
收敛到 Case 的临时 Java 项目。

## 门禁策略

| 命令 | 频率 | 默认 CI |
|---|---|---|
| `just agent-e2e-contract` | 每次修改 E2E 资产 | 可运行；不调用模型，也不要求 native binary |
| `just agent-e2e-smoke` | 手工或 nightly | 不进入默认 Maven 测试 |

当前不启用额外 LLM Judge。adapter 从 Jury normalized trace 中检查真实 Anatomist
子命令、返回输出、`next_queries` continuation、Git 改动范围、Maven 验收、Doctor 状态、
`file_cache` 内容 SHA-256 新鲜度和回答中的稳定语义锚点，并把提取结果保存为
`anatomist-evidence.json`。复杂 fixture
的默认测试通过，而 `-Pacceptance` 在 Agent 修改前必须失败、修改后必须通过。

9 条完整 smoke 预计耗时约 20 分钟；6 条复杂用例实测约 15 分钟，可使用
`e2e/jury-suites/complex.yaml`。需要开放式语义评分时再单独配置 Judge，不能用它替代
确定性 Oracle。
