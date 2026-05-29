# /diorama consolidate

调用 diorama skill，phase: consolidate。

Consolidate 是 generate 之后的强制 phase，用于将本次 task 产出的增量知识回写到项目知识文件。

适用于：
- 每个 task 完成后，自动进入 consolidate phase
- 从已完成的 task 产物中提取可复用的项目级知识更新
- 更新 glossary、domain-model 和 rules

consolidate 属于 task phase，遵循完整的 entry/exit 协议。
consolidate 的 checkpoint commit 包含知识文件更新。

若确认无增量知识可提炼，需设置 task.json 中 `consolidate.noop = true`，
phase-exit 仍会完成 checkpoint 和 handoff。
