# Git Semantics

本文档定义 Diorama skill 中与 git 相关的触发语义。

目标是明确：

- 哪些动作必须触发 git
- 哪些动作建议触发 git
- 哪些动作不应触发 git
- task workflow 线与 knowledge 演化线如何区分

---

## 基本原则

- **task workflow 线**：服务当前 task 的 phase history、rewind 与恢复
- **knowledge 演化线**：服务 glossary / domain-model / rules 的项目级更新
- 二者都可以落在同一个 git 仓库中，但语义上必须区分

---

## 必须触发 git 的动作

### 1. Inception

Inception 时必须：
1. 检查 working tree clean
2. 创建并切换到 task 分支：`git checkout -b task/<task-name>`
3. 创建 task 文件后形成 inception commit（非 phase checkpoint）

推荐 message: `diorama(incept): <task-name>`

### 2. Phase Exit Checkpoint

每个 phase 完成时，必须形成 checkpoint commit。

适用 phase：specify, plan, generate, consolidate

推荐 message:
```
diorama(specify): finish specify <task>
diorama(plan): finish plan <task>
diorama(generate): finish generate <task>
diorama(consolidate): finish consolidate <task>
```

### 3. Phase Handoff

phase checkpoint 之后，更新 `task.json` 与 `session`，形成独立 handoff commit。

推荐 message:
```
diorama(handoff): specify → plan
diorama(handoff): plan → generate
diorama(handoff): generate → consolidate
diorama(handoff): consolidate → done
```

### 4. Rewind

执行 rewind 时，必须：
1. `git reset --hard <target checkpoint>`
2. 更新 `task.json` / `session`
3. 形成 rewind commit

推荐 message: `diorama(rewind): → <target>`

### 5. Task Completion Merge

task 完成后（consolidate exit → done），必须将 task 分支合并回 base_branch：
1. `git checkout <base_branch>`
2. `git merge <task-branch>`
3. 形成合并 commit

推荐 message: `diorama(merge): <task-name> → <base_branch>`

### 6. Task Checkpoint (generate phase)

generate 阶段中，每个 task (T1, T2, ...) 完成全部 3 个子阶段且 Phase 3 Gate 通过后，必须形成 task checkpoint。

每个 task checkpoint 包含两个 commit：

1. **Task Checkpoint Commit** — 捕获该 task 的所有代码与 tasks.md 变更
   推荐 message: `diorama(task): finish T1 <task-name>`

2. **Task Checkpoint Record Commit** — 记录 task.json (task_checkpoints) 与 session 更新
   推荐 message: `diorama(task-checkpoint): T1 <task-name>`

触发条件：
- tasks.md 中该 task 的 `**Status**: [x] done` 已勾选
- 该 task 的 Phase 3 Gate 已通过

与 Phase Exit 的区别：Task Checkpoint 不改变 phase.current、不追加 phase.completed。

**强制规则**：generate phase-exit 时脚本校验 `phase.task_checkpoints` 非空。若为空则返回 `error: no_task_checkpoints` 拒绝退出。即：task-checkpoint 不是可选步骤，而是 generate 阶段 phase-exit 的前置必要条件。

Task-level Rewind 也触发 git：
- 推荐 message: `diorama(rewind): → T2`

### 7. Task Cancellation

取消 task 时，必须：
1. 若 working tree dirty，先提交 WIP：`diorama(cancel-wip): <task-name>`
2. 标记 cancelled 并清理 session 后 commit
3. 切回 base_branch

推荐 message: `diorama(cancel): <task-name>`

取消不删除 task 分支，保留用于回溯。

---

## 建议触发 git 的动作

### 1. survey (updates facts/glossary.json, facts/domain-model.md — knowledge commit)

注：consolidate 的知识文件更新现在属于 task workflow commit（phase checkpoint），不再是独立 knowledge commit。

---

## 不应触发 git 的动作

### 1. Phase Entry — 写 session/task.json，但不单独形成 git commit
### 2. generate 内部子阶段进度跟踪 — 只通过 tasks.md checklist 跟踪，子阶段本身不单独 commit；但 task 完成后的 checkpoint 触发 git（见下方 §6）

---

## task 与 knowledge 的边界

task workflow commit 服务当前 task (phase checkpoint, handoff, rewind)。consolidate 的知识文件更新属于 task workflow commit。
knowledge commit 服务项目级知识演化 (survey)。

---

## Working Tree 约束

- inception 前必须 working tree clean（创建分支需要干净状态）
- rewind 前必须 working tree clean
- task-checkpoint 前不需要 working tree clean（task-checkpoint 本身就是提交变更的操作）
- status 必须报告 dirty / clean
- Diorama 默认不自动清理用户改动

## 与 status 的关系

status 默认展示 task workflow 线的最近 checkpoint；不混入 survey 的 knowledge commit。status 展示 last_task_checkpoint 和 generate 的 task_checkpoints 进度。
