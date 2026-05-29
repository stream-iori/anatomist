# Phase Protocol

本文档定义 Diorama skill 的 phase 状态流转写入规则。

---

## 通用 Entry 协议（所有 phase 共享）

```
0. 分支检查：当前 git 分支必须与 task.json 中 task.branch 一致，否则拒绝进入
1. 前置检查：验证该 phase 的输入产物存在且非空，否则拒绝进入
2. 写 session:    current_phase = <this-phase>, dirty = true, last_updated_at = now
3. 写 task.json:  phase.current = <this-phase>
```

前置输入对照：

| Phase | 输入 |
|-------|------|
| specify | proposal.md |
| plan | design.md |
| generate | tasks.md |
| consolidate | tasks.md |

## 通用 Exit 协议（所有 phase 共享）

```
0. 分支检查：当前 git 分支必须与 task.json 中 task.branch 一致，否则拒绝退出
1. 产物已落盘
2. git commit（checkpoint commit）: "diorama(<phase>): finish <phase> <task>"
3. 写 session:  last_checkpoint_commit = <hash>, dirty = false, last_updated_at = now
4. 写 task.json: phase.checkpoints.<this-phase> = <hash>
5. 写 task.json: phase.completed 追加 <this-phase>
6. 写 task.json: phase.current = <next-phase>
7. git add task.json + session → 独立 commit: "diorama(handoff): <this-phase> → <next-phase>"
```

**generate 阶段 Exit 特殊规则**：

- **task_checkpoints 强制校验**：generate phase-exit 时必须 `phase.task_checkpoints` 非空。若为空，脚本返回 `error: no_task_checkpoints`，拒绝退出。即：每个 task 完成后必须先执行 task-checkpoint，最后才能 phase-exit。
- **checkpoint commit 可能仅含 metadata**：由于每个 task 的代码已在 task-checkpoint commit 中提交，generate phase-exit 的 checkpoint commit 可能仅包含 task.json 和 session 更新（working tree 无代码变更时）。
- **last_task_checkpoint 清空**：generate phase-exit 后，session 中 `last_task_checkpoint` 清空为 ""（不再处于 generate 阶段）。

## Task Checkpoint 协议（generate phase 内部）

```
0. 分支检查：当前 git 分支必须与 task.json 中 task.branch 一致
1. 验证：该 task 在 tasks.md 中标记为 **Status**: [x] done
2. git add -A → commit: "diorama(task): finish T1 <task-name>"
3. 获取 commit hash H
4. 写 session: last_checkpoint_commit = H, last_task_checkpoint = H, dirty = false, last_updated_at = now
5. 写 task.json: phase.task_checkpoints["T1"] = H
   （phase.current 和 phase.completed 不变）
6. git add task.json + session → commit: "diorama(task-checkpoint): T1 <task-name>"
```

注：与 Phase Exit 不同，Task Checkpoint 不改变 phase.current、不追加 phase.completed、不产生 handoff。

---

## 各 Phase 差异

| Phase | Skip 条件 | 产物 | anchors 操作 | next |
|-------|----------|------|-------------|------|
| specify | — | proposal.md(更新), design.md | 不碰 | plan |
| **plan** | — | tasks.md, task.json(anchors 更新) | **首次写入** dev_entries + acceptance_entries | generate |
| generate | — | 代码 | **append-only**（不删已有） | consolidate |
| **consolidate** | — | knowledge files updated | 不碰 | done |

### specify 阶段 proposal.md 更新规则

proposal.md 在 specify 阶段不是只读输入，而是**可更新的活文档**。

五维挑战过程中对意图的追加和修正应回写到 proposal.md：
- 只追加挑战后补全的信息（遗漏场景、边界条件）
- 只修正经用户确认的矛盾点或错误理解
- 不覆盖用户原始意图的表述，保留原始措辞
- 不删除已有内容，只增补和修正

这使得 rewind 到 specify 时，proposal.md 已包含上一次挑战的沉淀，重新分析时在此基础上继续深化。

---

## 状态推断

不使用额外的 status 字段。状态推断规则：

- `phase.current = X` 且 `X ∉ phase.completed` → X 进行中
- `X ∈ phase.completed` → X 已完成
- `phase.current = done` → task 完成
- `session.dirty = true` 且 working tree 有变更 → 中断态

---

## 中断恢复

路由规则见 SKILL.md 中断恢复段。

各 phase 的恢复策略：

- **generate**：子阶段进度由 `tasks.md` checklist 跟踪（Skeleton / DSL Test / Implementation），恢复时读 checklist 从未勾选项继续。若存在 task_checkpoints，可从最后一个已完成 task 的 checkpoint 恢复，而非从 phase 入口重做
- **specify / plan**：产物是单原子文件，中断时先执行"最低完成判定"；判定通过则补 Exit，未通过则 rewind 到当前 phase 重新开始

### 最低完成判定

用于判断中断后当前 phase 的产物是否已经足够进入 Exit。

- **specify**：`proposal.md` 存在且非空，`design.md` 存在且非空，并至少包含一个 `REQ-` 编号的功能需求或一个场景规格（`### S` 标题）
- **plan**：`tasks.md` 存在且非空，且 `task.json` 中 `anchors.dev_entries` / `anchors.acceptance_entries` 至少已有一项被写入（允许另一项为空，但应在 tasks.md 的技术评估节中说明）
- **generate**：`tasks.md` 存在且非空，且至少有一个 task 的 `**Status**: [x] done` 已勾选；或 `task.json` 中 `phase.task_checkpoints` 非空（即使 tasks.md 解析失败，已有 checkpoint 证明至少一个 task 已完成）
- **consolidate**：`task.json` 中 `consolidate.noop == true` 或 `consolidate.updated_files` 非空

若产物文件存在但未通过最低完成判定，应视为半成品，不进入 Exit，而是回退并重做当前 phase。

---

## Rewind 协议

支持两类 rewind 目标：
- **Phase-level**: specify, plan, generate, consolidate
- **Task-level**: T1, T2, ...（仅在 generate phase 内有效）

### Phase-level Rewind

```
1. 前置：working tree clean，否则拒绝
2. git reset --hard <target checkpoint hash>
3. phase.completed 移除 target 之后的 phase
4. phase.checkpoints.* 中 target 之后的置 ""
5. phase.current = <target>
6. phase.task_checkpoints 清空 {}（任何 phase-level rewind 都清空 task checkpoints）
7. anchors 清理：
   - target = specify → 清空 anchors
   - target = plan → 清空 anchors
   - target = generate → 保留全部 anchors
   - target = consolidate → 保留全部 anchors
   - 任何 rewind 均重置 consolidate: {noop: false, updated_files: []}
8. session 同步 current_phase, last_checkpoint_commit, last_task_checkpoint = "", dirty = false
9. git add task.json + session → "diorama(rewind): → <target>"
```

### Task-level Rewind

```
1. 前置：working tree clean，否则拒绝
2. 确定重置点：
   - rewind T1 → reset_hash = phase.checkpoints["plan"]
   - rewind TN (N>1) → reset_hash = phase.task_checkpoints["T(N-1)"]
3. git reset --hard <reset_hash>
4. 重新读取 task.json（reset 后磁盘上的版本来自目标 commit）
5. 清理 task_checkpoints（基于 reset 前的状态计算）：
   - rewind T1 → 清空所有 {}
   - rewind TN → 保留 T1..T(N-1)，删除 TN 及之后
6. phase.current = "generate"（不变）
7. phase.completed 不变
8. anchors 保留全部
9. consolidate 重置 {noop: false, updated_files: []}
10. session 同步：
    - current_phase = "generate"
    - last_checkpoint_commit = reset_hash
    - last_task_checkpoint = 保留的最后一个 task checkpoint hash（或 ""）
    - dirty = false
    - last_updated_at = now
11. git add task.json + session → "diorama(rewind): → TN"
```

支持的 rewind 目标：specify, plan, generate, consolidate, T1, T2, ...

---

## Task Cancellation

取消 task 会将 phase.current 设为 `cancelled`，保留 task 分支用于回溯，清理 session，切回 base_branch。

### Cancel 协议

```
1. 前置：phase.current != done 且 phase.current != cancelled，否则拒绝
2. 若 working tree dirty，先 git add -A 并 commit: "diorama(cancel-wip): <task-name>"
3. 写 task.json: phase.current = cancelled
4. 写 session: current_task = "", current_phase = "", dirty = false, last_updated_at = now
5. git add task.json + session → commit: "diorama(cancel): <task-name>"
6. git checkout <base_branch>（不删除 task 分支）
```

### cancelled 状态语义

- `cancelled` 不是一个 phase，是终态标记
- cancelled task 不可恢复（不可 rewind、不可 continue）
- task 分支保留在 git 中，可通过 `git log task/<task-name>` 查看历史产物
- session 中 current_task 清空，允许 incept 新 task

---

## Phase Handoff

每个阶段完成后，handoff 信息分布在以下位置，不要求单独文件：

- 当前 phase 产物文件
- `task.json`（checkpoints, completed, current, anchors, base_branch）
- `session`（current_phase, last_checkpoint_commit）
- git checkpoint commit

重启后通过读取以上四处即可完整恢复上下文。

---

## Task Completion（done 后合并回 base 分支）

当 consolidate exit → done 后：

1. 切换到 base_branch：`git checkout <base_branch>`（从 task.json 读取）
2. 合并 task 分支：`git merge <task-branch>`
3. 形成合并 commit：`diorama(merge): <task-name> → <base_branch>`
