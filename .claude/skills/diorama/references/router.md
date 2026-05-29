# Router

本文档定义 `/diorama` 主入口的决策树。

目标：

- 统一 Inception / survey / phase 推进 / 中断恢复 / done 后建议
- 让主入口行为可预测、可恢复、可追溯

---

## 输入来源

`/diorama` 每次执行都应实时读取：

- `session/current-session.json`
- `task.json`
- 当前 task 目录中的关键产物
- git working tree 状态
- 最低完成判定结果

---

## 主决策树

```text
0. 若 action == survey（显式调用 `/diorama survey`）
   → 执行 survey（重新扫描并增量更新 glossary + domain-model）
   → 建议形成独立 knowledge commit
   → 不更新 task phase checkpoints
   → 不改变 task workflow 状态
   → 结束

0b. 若 action == review（显式调用 `/diorama review`）
    → 执行 conformance-check（对照 design.md 和 rules/index.md 验证代码合规性）
    → 输出合规报告
    → 不更新 task phase checkpoints
    → 不改变 task workflow 状态
    → 结束

0c. 若 action == cancel（显式调用 `/diorama cancel`）
    → 执行 cancel（标记 task 为 cancelled，保留分支，清理 session，切回 base_branch）
    → 输出取消确认
    → 结束

1. 若 session 为空
   → Inception

2. 若 task.phase.current == done
   → 执行 merge-back（task 分支合并回 base_branch）
   → task 已完成

3. 若 phase.current ∈ phase.completed
   → 进入下一 phase

4. 若 phase.current ∉ phase.completed
   → 进入中断/继续判定
```

---

## Inception

当 session 为空时：

1. 检查 working tree clean — 不干净则拒绝（避免将无关改动带入 task 分支）
2. 生成 task 名称：`YYYYMMDD-NNN-<hint>` 格式
   - `YYYYMMDD`：UTC 当前日期
   - `NNN`：当天已有 task 的最大序号 + 1（3 位补零）
   - `<hint>`：用户提供的意图关键词（空则用 `task`，特殊字符替换为 `-`）
   - 示例：`20260507-001-payment-service`、`20260507-002-task`
3. 记录当前分支作为 base_branch（用于 task 完成后合并回去）
4. 创建并切换到 task 分支：`git checkout -b task/<task-name>`
5. 创建 `.diorama/tasks/<task>/` 目录（使用生成的名称）
6. 使用 `proposal-template.md` 初始化 `proposal.md`
7. 收集用户意图并写入 `proposal.md`
   - 从当前对话上下文提取用户的意图描述（做什么、为什么做）
   - 若用户尚未提供意图，主动追问后收集，需要触发Ask User/Question相关能力，用于收集意图信息
   - 将意图写入 `proposal.md` 的"你想做什么"和"为什么做"段落
   - 收集需求类型（feature / bugfix / refactor / tech-debt）写入"需求类型"段落
   - 收集约束条件（接口兼容性、截止日期、性能要求等）写入"约束条件"段落
8. 初始化 `task.json`（包含 base_branch）和 `session`
9. Inception commit（非 phase checkpoint，仅使 working tree clean）：`diorama(incept): <task-name>`
10. 首个 phase 固定为 specify
11. 检查 `.diorama/knowledge/facts/glossary.json` 是否存在
    - 不存在则建议用户先执行 `/diorama survey`
    - 不自动执行 survey（让用户决定）
12. Inception 完成后自动进入 specify phase（无需用户再次执行 `/diorama`）

---

## 正常推进

当 `phase.current ∈ phase.completed` 时：

1. 读取 phase 顺序：`specify → plan → generate → consolidate → done`
2. 找到下一个 phase
3. 检查下一个 phase 的输入是否具备
4. 若具备，则进入该 phase
5. 若下一 phase 为 generate，则 Agent 应读取 tasks.md 并将 TodoList 中的"Generate"替换为 tasks.md 中的详细子阶段列表（含 Gate 命令，见 SKILL.md TodoList 管理建议）
6. 若不具备，则输出 status 风格的缺失说明，不强行推进

---

## 当前 phase 的继续 / 中断恢复

当 `phase.current ∉ phase.completed` 时：

### 情况 A：`session.dirty == false` 且 working tree clean

说明 Entry 已写入，但没有明显中断痕迹。

处理：
- 继续当前 phase
- 若当前 phase 产物已经通过最低完成判定，则直接补 Exit 与 handoff

### 情况 B：`session.dirty == true` 或 working tree dirty

说明当前 phase 存在中断或未吸收改动。

处理：
1. 执行当前 phase 的最低完成判定
2. 若判定通过：
   - 继续 `/diorama`
   - 补 phase Exit 与 handoff
3. 若判定失败：
   - 推荐 `/diorama rewind <current-phase>`
   - 或提示用户清理半成品后重做当前 phase
4. 若当前 phase 为 generate 且 task_checkpoints 非空：
   - 推荐从最后一个 task checkpoint 恢复：`/diorama`（继续未完成的 task）
   - 可选 task-level rewind：`/diorama rewind T2`（重做特定 task）

---

## done 后的行为

当 `task.phase.current == done`：

1. **Human Confirmation**：在执行 merge-back 前，Agent 必须向用户确认：
   - 展示本次变更概要（涉及文件、新增/修改内容）
   - 展示 conformance-check 结果摘要（PASS/CONDITIONAL/FAIL）
   - 若存在遗留问题（NEEDS CLARIFICATION / CONDITIONAL 项），列出待确认事项
   - 明确询问"是否执行 merge-back 将 task 分支合并回 base_branch？"
   - 用户确认后继续步骤 2；用户拒绝时保留当前状态
2. 执行 merge-back：将 task 分支合并回 base_branch
   - 使用 `merge-back` 子命令：`python scripts/diorama_runtime.py merge-back --task <task-dir> --repo <repo>`
   - 合并成功后，当前分支已切换回 base_branch
3. 不再推进 phase
4. 输出 task 已完成
5. status 仍按 task workflow 线展示最近 checkpoint

---

## Review

当 `action == review` 时（显式调用 `/diorama review`）：

1. 读取 design.md，提取 REQ-/BR-/AC- 编号
2. 读取 tasks.md，提取任务与需求追溯关系
3. 读取 task.json anchors，定位代码文件
4. 若 rules/index.md 存在，读取索引中规则文件，验证规则索引
5. 执行 conformance-check，输出合规报告
6. 不修改任何文件，不更新 phase 状态

Review 不受 task phase 状态限制，可在任何阶段触发。

---

## 与 status 的关系

- `/diorama status` 负责展示当前聚合状态
- `/diorama` 负责基于同样的事实源做动作决策
- 二者必须共享：
  - phase 状态推导
  - 最低完成判定
  - dirty/clean 判定
  - next action 逻辑

如果 status 与 router 推导结果不一致，应以 router 规则为准，并修正文档使二者重新一致。

---

## Next 决策表

### 当前 phase 中断，且最低完成判定失败

- `Recommended: /diorama rewind <current-phase>`
- `Alternative: 清理半成品后重做当前 phase`

### 当前 phase 为 generate，且已有 task_checkpoints

- `Recommended: /diorama（从最后 task checkpoint 恢复）`
- `Alternative: /diorama rewind T2（重做特定 task）`
