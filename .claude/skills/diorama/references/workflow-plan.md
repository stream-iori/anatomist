# Plan: 任务分解 + 锚点写入

## 职责

基于 specify 阶段产出的 PRD，将需求分解为可执行的任务清单，写入关键锚点。

它应：
- 将 PRD 中的功能需求分解为开发任务
- 每个任务标注 REQ 来源，形成追溯链
- 从 PRD §6 实现上下文获得入口代码和 SUT 边界
- 写入 task.json 中的关键锚点

它不应：
- 重新分析需求（specify 已完成）
- 扫描代码（specify 已锚定）
- 发明 PRD 中未提及的任务

## Phase 协议

遵循 phase-protocol.md 通用 Entry/Exit 协议。

| 项 | 值 |
|----|-----|
| 前置输入 | design.md |
| 产物 | tasks.md, task.json (anchors 更新) |
| anchors | **首次写入** dev_entries + acceptance_entries |
| next | generate |

## 输入与输出

- 输入：`.diorama/tasks/<current_task>/design.md`
- 输出：`.diorama/tasks/<current_task>/tasks.md`
- 锚点：`.diorama/tasks/<current_task>/task.json`（anchors 段更新）

## 读取内容

- `.diorama/tasks/<current_task>/design.md`
- `.diorama/tasks/<current_task>/proposal.md`（如需回看原始意图）
- `.diorama/knowledge/facts/glossary.json`
- `.diorama/knowledge/facts/domain-model.md`
- `.diorama/knowledge/facts/tech-context.md`
- `.diorama/knowledge/references/index.md`

## Task Context 要求

tasks.md 的技术评估节保留最关键的两个锚点：
- 开发入口（可多个）—— 从 design.md §6 获得
- 验收入口（可多个）—— 从 design.md §8 获得

task.json 同步记录：
- `anchors.dev_entries`
- `anchors.acceptance_entries`

其余 task-specific context 由 CodingAgent 在运行时渐进扫描获得，不要求提前静态固化。

## REQ 追溯

每个任务必须标注其关联的 REQ 和 BR 编号：

```markdown
### T1: <任务摘要> [REQ-001, BR-001]
```

这确保 generate 阶段的 guardrail 可以精确约束：实现必须可追溯到 REQ-ID，超出即 scope creep。

## Gate 命令规范

每个子阶段的 Gate 必须是**可直接执行的完整命令**（包含模块路径、测试类名等），而不是模糊描述如 `mvn compile pass`。

格式：`**Gate**: `<完整可执行命令>` — <期望结果>`

示例：

| 子阶段 | Gate 格式 |
|--------|----------|
| Phase 1: Skeleton | `**Gate**: `mvn compile -pl <module> -q` — exit 0` |
| Phase 2: DSL Test | `**Gate**: `mvn test-compile -pl <module> -q && mvn test -pl <module> -Dtest=<TestClass>#<method> -q` — ① test-compile exit 0（类与方法已建立）② test fails with AssertionError（红灯；编译失败不算红灯）|
| Phase 3: Implementation | `**Gate**: `mvn test -pl <module> -q` — exit 0, all tests green` |

Agent 在 generate 阶段会直接执行 Gate 命令验证，因此命令必须可复制粘贴到终端执行。

## 最低完成判定

满足以下条件，plan 可视为已完成并进入 Exit：

- `tasks.md` 存在且非空
- `task.json` 已写入 anchors，且 `anchors.dev_entries` / `anchors.acceptance_entries` 至少已有一项被填充
- 若另一项 anchors 为空，应在 `tasks.md` 的技术评估节中说明原因

若 tasks.md 已创建但 anchors 尚未形成，应视为半成品，中断恢复时重做当前 phase。

## Coverage-Matrix Advisory

Plan phase-exit 前，Agent 建议执行 `/diorama coverage-matrix` 并将结果作为参考输出。

coverage-matrix 帮助用户发现 specify 阶段可能遗漏的覆盖维度（如依赖失败、鲁棒性、可观测性等）。

- 执行时机：plan 产物完成、phase-exit 之前
- 结果用途：提示用户是否需要回到 specify 补充场景，或在 generate 中注意额外维度
- 不阻塞 phase-exit：coverage-matrix 结果是建议性的，不影响 plan 是否可以 exit
