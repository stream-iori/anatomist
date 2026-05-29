# Diorama Skill

Diorama 是面向 Java 项目的场景驱动开发（SDD）技能。

它通过 `/diorama` 命令族触发，将模糊意图挑战为可落地 PRD、分解为任务清单、生成代码与测试，全程由 Phase Protocol 协调状态流转。

---

## 命令清单

| 命令 | 触发动作 |
|------|---------|
| `/diorama` | 主入口：Inception / 推进当前 phase / 中断恢复 |
| `/diorama status` | 聚合当前状态：phase、checkpoints、anchors、working tree |
| `/diorama survey` | 显式触发领域知识冷启动/刷新 |
| `/diorama rewind <step>` | 回退到指定 phase checkpoint 或 task checkpoint |
| `/diorama coverage-matrix` | 输出当前 task 的 8 维度覆盖矩阵 |
| `/diorama review` | 对照 design.md 和 rules/index.md 验证代码合规性 |
| `/diorama cancel` | 取消当前 task：标记 cancelled，保留分支，切回 base_branch |

注：consolidate 是强制 phase（generate 之后自动进入），无需单独命令。

---

## 路由规则

`/diorama` 读 `session` 和 `task.json` 决定动作：

```
session 为空
  → Inception：创建 task 目录 + 收集意图写入 proposal.md + 自动进入 specify

phase.current ∈ phase.completed
  → 进入下一个 phase（正常推进）

phase.current == done
  → 执行 merge-back（task 分支合并回 base_branch）→ task 已完成

phase.current ∉ phase.completed
  → 中断恢复（见下方中断恢复段）
```

### 首个 phase

固定为 specify。

Inception 时从对话上下文收集用户意图写入 `proposal.md`（若用户尚未表达意图则主动追问），然后自动进入 specify phase，无需用户再次执行 `/diorama`。

Inception 后检查 knowledge 文件是否存在，不存在则建议用户先执行 `/diorama survey`。

---

## 动作模式

Diorama skill 内部通过 action 区分不同能力：

| Action | 说明 | 触发方式 |
|--------|------|---------|
| `incept` | 检查 working tree → 创建 task 分支 → 收集意图写入 proposal.md、session → inception commit → 自动进入 specify | `/diorama` 自动路由 |
| `specify` | 意图挑战 + PRD 产出 → design.md | `/diorama` 自动路由 |
| `plan` | 任务分解 + 锚点写入 → tasks.md | `/diorama` 自动路由 |
| `generate` | 代码落地（Skeleton → DSL Test → Implementation） | `/diorama` 自动路由 |
| `task-checkpoint` | generate 阶段 task 完成后创建 git checkpoint（强制：generate phase-exit 前必须完成） | generate 子阶段自动触发 |
| `merge-back` | task 完成后合并 task 分支回 base_branch | `/diorama` 自动路由（done 后触发） |
| `survey` | 抽取领域知识 → glossary.json, domain-model.md | `/diorama survey` 显式触发 |
| `consolidate` | 增量提炼项目知识 | `/diorama` 自动路由（generate 完成后自动进入） |
| `coverage-matrix` | 输出 8 维度覆盖矩阵 | `/diorama coverage-matrix` |
| `review` | 对照 design.md 和 rules/index.md 验证代码合规性 | `/diorama review`；generate 后置步骤自动执行 |
| `design-amend` | generate 中轻量修改 design.md（新增字段、补充细节、修正 typo） | generate 阶段按需触发 |
| `cancel` | 取消当前 task：标记 cancelled、保留分支、清理 session、切回 base_branch | `/diorama cancel` 显式触发 |
| `status` | 聚合状态视图 | `/diorama status` |
| `rewind` | 回退到指定 phase | `/diorama rewind <step>` |

---

## Phase Protocol

详细的写入规则、状态推断、rewind 协议见 [`references/phase-protocol.md`](references/phase-protocol.md)。

### Phase 顺序与差异

```text
specify → plan → generate → consolidate → done
```

| Phase | 前置输入 | 产物 | anchors | next |
|-------|---------|------|---------|------|
| specify | proposal.md | proposal.md(更新), design.md | 不碰 | plan |
| **plan** | design.md | tasks.md, task.json(anchors) | **写** | generate |
| generate | tasks.md | 代码 | append-only | consolidate |
| **consolidate** | tasks.md | knowledge files updated | 不碰 | done |

详细 workflow 定义见：

- [`references/workflow-specify.md`](references/workflow-specify.md)
- [`references/workflow-plan.md`](references/workflow-plan.md)
- [`references/workflow-generate.md`](references/workflow-generate.md)
- [`references/workflow-survey.md`](references/workflow-survey.md)（领域知识冷启动）
- [`references/workflow-consolidate.md`](references/workflow-consolidate.md)（增量提炼项目知识）
- [`references/workflow-coverage-matrix.md`](references/workflow-coverage-matrix.md)（8 维度覆盖分析）
- [`references/workflow-review.md`](references/workflow-review.md)（design 对照 + rules 合规检查）
- [`references/status-view.md`](references/status-view.md)（status 聚合视图与 next action 规则）
- [`references/git-semantics.md`](references/git-semantics.md)（git 触发点、commit 边界与 message 规范）
- [`references/router.md`](references/router.md)（`/diorama` 主入口决策树）
- [`references/scripts-design.md`](references/scripts-design.md)（scripts 辅助层职责与接口）

### 中断恢复

```
phase.current ∈ phase.completed  → 正常推进下一 phase
phase.current == done             → 执行 merge-back → task 完成
phase.current ∉ phase.completed:
  dirty=false, working tree clean  → 继续（Entry 完成 Exit 未开始）
  dirty=true                       → 选择：
    1. /diorama rewind <last completed>  — 安全回退
    2. /diorama（继续）                    — 从断点恢复
    3. 手动清理 working tree 后再操作
```

generate 的子阶段进度由 `tasks.md` checklist 跟踪，中断恢复时读 checklist 从未勾选项继续。
若 generate 已有 task_checkpoints，可从最后一个已完成 task 的 checkpoint 恢复。
支持 task-level rewind：`/diorama rewind T2` 重做特定 task。
其他 phase 产物是单原子文件，中断时检查是否已落盘：已落盘走 Exit，未落盘 rewind 重做。

---

## 过程管理模型

三层协作：

| 层 | 载体 | 职责 |
|----|------|------|
| 结构化控制面 | `task.json` | phase 状态、checkpoints、anchors |
| 热路径指针 | `session/current-session.json` | 当前 task/phase/dirty，先行写入 |
| 历史与回退 | git checkpoint | 每个 phase 完成时 commit，generate 内每个 task 完成时也 commit，rewind 基础 |

---

## TodoList 管理建议

Agent 在执行 diorama workflow 时，建议维护与当前阶段对应的 TodoList：

| 阶段 | TodoList 内容 |
|------|-------------|
| Inception | 1 项：Inception |
| Specify | 1 项：Specify |
| Plan | 1 项：Plan + coverage-matrix advisory |
| Plan → Generate | 替换"Generate"为 tasks.md 中的详细任务列表 |

### Plan 完成后 TodoList 替换规则

Plan phase-exit 完成后，Agent 应：

1. 读取 tasks.md
2. 生成新的 TodoList，将"Generate"替换为 tasks.md 中每个任务的子阶段（含 Gate 命令）：

   ```text
   T1 Phase 1: Skeleton — Gate: `mvn compile -pl <module> -q`
   T1 Phase 2: DSL Test — Gate: `mvn test-compile -pl <module> -q && mvn test -pl <module> -Dtest=<TestClass> -q`
   T1 Phase 3: Implementation — Gate: `mvn test -pl <module> -q`
   T1 task-checkpoint
   T2 Phase 1: Skeleton — Gate: `mvn compile -pl <module> -q`
   T2 Phase 2: DSL Test — Gate: `mvn test-compile -pl <module> -q && mvn test -pl <module> -Dtest=<TestClass> -q`
   T2 Phase 3: Implementation — Gate: `mvn test -pl <module> -q`
   T2 task-checkpoint
   ...
   ```

3. 按此 TodoList 逐项推进 generate 子阶段：完成代码 → 执行 Gate → Gate 通过后勾选 checkbox

### Generate 完成后 TodoList 替换

所有子阶段完成后，将 TodoList 替换为后置步骤（见 `workflow-generate.md` 后置步骤段）：

   ```text
   最终 Gate 验证
   Conformance-check（design.md 对照 + rules 合规）
   phase-exit (generate → consolidate)
   consolidate (增量知识提炼)
   phase-exit (consolidate → done)
   Human Confirmation (向用户确认变更概要，等待用户同意后合并)
   merge-back (task 分支 → base_branch)
   ```

---

## 运行时目录模型

```text
.diorama/
├── tasks/<YYYYMMDD-NNN-hint>/
│   ├── proposal.md
│   ├── design.md
│   ├── tasks.md
│   └── task.json
├── knowledge/
│   ├── facts/
│   │   ├── glossary.json
│   │   ├── domain-model.md
│   │   └── tech-context.md
│   ├── rules/
│   │   ├── index.md
│   │   └── experience.md
│   └── references/
│       └── index.md
├── session/
│   └── current-session.json
├── templates/
└── manifest.json
```

注：`skills/` 与 `commands/` 属于 Agent Integration Layer，安装在 `.qoder/`、`.claude/` 或 `.codex/` 中，不在 `.diorama/` 内。

Task 命名规则：`YYYYMMDD-NNN-<hint>`（UTC 日期 + 当天序号 + 意图关键词），确保唯一性和可追溯性。

---

## 原则

- `diorama-sdd/` 负责定义和演进 AI 能力；`.diorama/` 负责在目标项目中承载运行时状态与产物
- workflow 中提到的 `tasks/<task>/...` 默认指运行时 `.diorama/tasks/<task>/...`
- 写入顺序：session 先行，task.json 随后
- task.json 更新独立 commit，不 amend 到 checkpoint commit
- survey 只沉淀慢变领域知识（glossary + domain-model），不抽取 task-specific 实现信息
