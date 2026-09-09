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
| `anatomist-branch-work-impact` | 错把 Base 后续工作归因分支、漏掉 TEST | 自动准备含 TEST 快照、共同祖先、两侧调用路径和源码 |
| `anatomist-branch-tip-impact` | 多配置缓存干扰端点比较 | 正确配置、调用目标替换、直接与可能 TEST 调用方 |
| `anatomist-impact-boundary` | 用不完整快照排除其他调用方 | 固定快照、缺失覆盖、派发边界及两侧源码 |

新增版本场景只描述用户目标，允许 Agent 自行选择命令；旧管道协议用例保留 canonical NDJSON 约束。索引状态、
真实 pipe trace、stream evidence、Git 和固定源码共同构成 Oracle；命令清单不能替代
结果证据。

## 前置条件

| 依赖 | 要求 |
|---|---|
| Anatomist | `target/anatomist` 可执行；也可设置 `ANATOMIST_E2E_BIN` |
| Jury | Jury 1.x；`JURY_BIN` 可覆盖命令路径 |
| Codex | CLI 已登录，Jury 使用 `codex_sdk` runner |
| Skill | adapter 提供当前仓库 SKILL.md 临时副本，不依赖用户全局版本 |
| Schema | Jury 所在 Python 环境安装 `e2e/requirements.txt` |

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
`target/`。adapter 用自有命令入口固定 native binary，登录 shell 同样保留该 PATH，并把 Agent cwd
收敛到 Case 的临时 Java 项目。

## 门禁策略

| 命令 | 频率 | 默认 CI |
|---|---|---|
| `just agent-e2e-unit` | 每次修改校验器 | 默认 CI；无需 Jury、模型或 native |
| `just agent-e2e-contract` | 每次修改 E2E 资产 | 单测及 Jury 严格配置校验，不调用模型 |
| `just agent-e2e-versions` | 版本能力变更 | 5 条真实 Agent 用例（含捕获边界、GC 预览与回收） |
| `just agent-e2e-smoke` | 手工或 nightly | 不进入默认 Maven 测试 |

当前不启用额外 LLM Judge。adapter 从 Jury normalized trace 中检查真实 Anatomist
子命令、返回输出、`next_queries` continuation、Git 改动范围、Maven 验收、Doctor 状态、
`file_cache` 内容 SHA-256 新鲜度和回答中的稳定语义锚点，并把提取结果保存为
`anatomist-evidence.json`。复杂 fixture
的默认测试通过，而 `-Pacceptance` 在 Agent 修改前必须失败、修改后必须通过。

完整 smoke 共 12 条；首次 Maven 依赖准备会增加耗时。6 条复杂用例可使用
`e2e/jury-suites/complex.yaml`。需要开放式语义评分时再单独配置 Judge，不能用它替代
确定性 Oracle。

## 被测产物与证据

显式 `ANATOMIST_E2E_BIN` 无效时立即失败；未设置时只用 `target/anatomist`，不回退到 PATH。
每次记录二进制路径、版本、SHA256、源码提交及 SKILL 哈希。每例独立 `ANATOMIST_HOME`，
版本 fixture 使用自有 Maven settings 和仓库，按固定版本构造真实多模块 Git 历史。
版本用例允许在自有 fixture 创建 Git worktree；同时独立检查 HEAD、分支及工作区内容未变。
可用 `ANATOMIST_E2E_MAVEN_SEED=/path/to/cache` 提供依赖种子；每例复制到自有仓库，
不写种子目录。首次从 Maven Central 准备依赖可能较慢，准备阶段单独记录。

`diff_evidence` 独立解析 anatomist-diff/v2 的 JSON/NDJSON，校验 Schema、端点、调用路径和证明。
`snapshot_navigation` 将 Agent 获取的 source_slice 实体及三个语义身份关联到对应侧；
历史快照不按当前 checkout 做 freshness 校验。semantic stream 必须有正确头尾，只认顶层记录，
不能用混合 diff 输出、嵌套对象或失败命令补齐证据。独立复查不替代 Agent 实际取证。
版本导航同时接受完整 NDJSON 和 JSON envelope；JSON 必须含三个身份字段、results 和 stream evidence。
NDJSON 是语义命令的默认输出；`tee` 可保留完整证据流，`jq`、`head` 等过滤器不能代替完整证据。
大输出可保存到自有 `../evidence/`。校验器将 diff 重定向与后续实际读取关联，验证文件的完整协议，
并按快照身份和实体 ID 验证源码；未读取文件、越界路径和符号链接不作为证据。
`cat`、`jq`、`sed` 和 Python 读取均可建立消费关联，但仍校验保留文件的完整协议；
`wc`、`ls`、哈希命令只证明文件存在，不能代替读取。
已消费文件及哈希随 Jury 报告归档，fixture 清理后仍可复查。

真实模型故障、准备失败和检查失败分别记录，不能算成通过。不使用额外 LLM Judge。
完整 suite 使用 `--continue-on-failure` 保留全部用例结果；需要单例定位时使用 `--member`。
若服务端拒绝 Jury 内置 Codex 版本，可用 `JURY_CODEX_BIN=/path/to/codex` 指定兼容的本机 CLI，
保留原失败记录后重跑受影响用例；不要通过放宽业务检查掩盖运行器故障。

版本生命周期进程验收：`just diff-lifecycle-e2e`。测试使用自有 Git 仓库和
ANATOMIST_HOME，验证强杀恢复、worktree 注册残留、缓存读取并发及清理诊断。
性能与快照回收报告保存在 target/；默认回收 fixture，--keep-on-failure 才保留。
`just diff-stress-e2e` 验证超过 8 MiB 的真实 diff、慢读者保护、断管诊断与暂存清理。
`just diff-performance` 测量 1k/10k Java 文件及 100 份历史快照；与旧版本对照时使用
`scripts/diff-performance.py --native <new> --baseline <old>`，避免与编译或其他负载同时运行。
有 baseline 时，缓存比较或单文件增量的中位数回退超过 10% 会使命令失败并保留报告。
