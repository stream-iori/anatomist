# Status View

`/diorama status` 是当前 task 的实时恢复视图 + 下一步动作建议。

实际运行时，status 可基于 scripts 返回的结构化结果渲染为用户可读文本。

它不缓存，每次实时聚合以下来源：

- `task.json`
- `session/current-session.json`
- git working tree 状态
- phase checkpoint 信息
- 当前 phase 对应产物的最低完成判定

---

## 输出目标

status 至少回答五个问题：

1. 当前 task 是什么
2. 当前 phase 是什么
3. 当前 phase 是 completed / current / interrupted / done 中的哪一种
4. 现在应该继续还是 rewind
5. 下一步建议动作是什么

---

## 展示区块

推荐输出以下八个区块：

### 1. Summary

一行结论：

- `ready to continue — next phase generate`
- `interrupted at plan — partial artifacts detected, rewind recommended`
- `task complete — all phases done`

### 2. Task

- current_task
- branch
- current_phase
- last_completed_phase
- last_checkpoint_commit
- last_task_checkpoint

### 3. Runtime

- session.dirty
- working_tree_clean
- phase_state

`phase_state` 为展示值，不写回文件。

可取值：
- `ready`
- `in_progress`
- `interrupted`
- `done`
- `cancelled`

### 4. Phase Progress

对每个 phase 展示：
- completed
- current
- interrupted
- pending

可带 checkpoint 简写。

### 5. Artifacts

只展示当前 phase 对应的关键产物与最低完成判定。

- specify：proposal.md, design.md
- plan：design.md, tasks.md, anchors
- generate：tasks.md, checklist progress
- consolidate：knowledge files updated (glossary.json, domain-model.md, experience.md)

### 6. Generate Progress（仅 generate phase）

当当前 phase 为 generate 且 minimum-check 返回 `sub_phases` 时，展示每个子阶段的详细进度：

```text
- T1 Phase 1 (Skeleton): done — 3/3 items ✓ a1b2c3
- T1 Phase 2 (DSL Test): in progress — 2/3 items
- T1 Phase 3 (Implementation): in progress — 0/3 items
```

每个子阶段包含：

- `task_id`：任务编号（T1, T2...）
- `phase_num`：子阶段编号
- `label`：子阶段名称（如 Skeleton、DSL Test、Implementation）
- `status_done`：任务级 `**Status**: [x] done` 是否已勾选
- `checkbox_checked` / `checkbox_total`：子阶段内 `- [x]` / `- [ ]` 复选框计数

每个已 checkpoint 的 task 在其第一个子阶段显示 ✓ <hash-short>（commit hash 前 7 位）。

此区块仅在 generate phase 且 `sub_phases` 非空时出现。

### 7. Anchors

- anchors.dev_entries
- anchors.acceptance_entries

### 8. Next

只给一条主建议；必要时再给一个 alternative。

---

## 状态推导规则

### done

- `task.phase.current == done`

### ready

- `phase.current ∈ completed`
- working tree clean
- 下一 phase 的输入已具备

### in_progress

- `phase.current ∉ completed`
- `session.dirty == false`
- working tree clean
- 当前 phase 未进入中断判定

### interrupted

满足以下任一：

- `session.dirty == true`
- working tree dirty
- 当前产物存在但未通过最低完成判定

---

## 最低完成判定在 status 中的作用

status 必须调用 phase 对应的最低完成判定：

- 判定通过 → 可建议继续并补 Exit
- 判定失败 → 视为半成品，建议 rewind 或重做

---

## Next 决策表

### session 为空

- `Start a new task with /diorama`

### task 已 done

- `Task is complete. Awaiting Human Confirmation before merge-back.`
- `All phases including consolidate are done.`
- `Agent must confirm with user before executing merge-back.`
- `Optional: /diorama review — verify code conformance against design.md`

### 当前 phase 已完成，可进入下一 phase

- `Continue with /diorama → <next-phase>`

### 当前 phase 进行中，但未中断

- `Continue current phase with /diorama`

### 当前 phase 中断，且最低完成判定通过

- `Resume /diorama to complete phase exit and handoff.`

### 当前 phase 中断，且最低完成判定失败

- `Recommended: /diorama rewind <current-phase>`
- `Alternative: clean partial artifacts, then rerun /diorama`

### working tree dirty，但状态文件正常

- `Working tree is dirty.`
- `Either clean changes manually, or rewind before continuing.`

### task 已 cancelled

- `Task has been cancelled. Branch preserved for reference.`
- `Start a new task with /diorama`
