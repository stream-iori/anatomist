# Scripts Design

本文档定义 Diorama skill 中 scripts 辅助层的职责边界与最小接口。

目标：
- 用少量脚本承载确定性判断
- 减少 LLM 在状态聚合、最低完成判定、git 读取上的机械负担
- 不把 scripts 演化为新的 workflow engine

## 职责边界

### scripts 应负责
- 读取 task.json / session
- 检查关键产物是否存在
- 执行 phase 最低完成判定
- 检查 git working tree 状态
- 解析 completed / checkpoints / next phase
- 为 status / router 返回结构化事实
- 执行确定性写入步骤（incept, phase-entry, phase-exit, rewind-exec）
- 校验 phase 前置输入与产出产物

### scripts 不应负责
- 生成 design.md / tasks.md / code
- 做业务理解或场景推理
- 决定最终业务动作
- 直接执行 destructive git 操作
- 取代 skill 成为独立 workflow runtime

原则：scripts 只返回结构化事实，不做最终业务决策。

## 形态建议

推荐采用：一个脚本，多子命令 — `skills/diorama/scripts/diorama_runtime.py`

## 子命令清单

### 读取类子命令（只读，无副作用）

1. **status-check** — 聚合当前 task/session/git/产物状态
2. **status-render** — 将 status-check 结果渲染为 Markdown 文本
3. **minimum-check** — 对指定 phase 执行最低完成判定（specify, plan, generate, consolidate）
4. **resolve-next-phase** — 根据 current/completed 解析下一个 phase
5. **working-tree-check** — 检查 git working tree 状态
6. **rewind-target-check** — 验证 rewind 目标是否合法（支持 phase 和 task ID 目标）

### 写入类子命令（确定性协议步骤）

7. **incept** — 检查 working tree → 创建 task 分支 → 创建 task 目录 + task.json + proposal.md + session → inception commit
8. **phase-entry** — 校验前置输入 → 写 session dirty=true + task.json current
9. **phase-exit** — 校验产物落盘 → generate 阶段额外校验 task_checkpoints 非空（强制） → git checkpoint commit → 更新 session/task.json → handoff commit
10. **rewind-exec** — 校验 working tree clean → git reset → 更新 task.json/session → rewind commit
11. **merge-back** — 读取 base_branch → checkout base_branch → merge task-branch → 合并 commit
12. **task-checkpoint** — 校验 task done → git checkpoint commit → 更新 task.json/session → record commit

写入类子命令只执行 phase-protocol.md 中定义的确定性步骤，不做业务决策。

## 最低完成判定接口

### minimum-check --phase specify

检查 design.md：
- 存在且非空
- 包含至少一个 `REQ-` 编号或一个场景规格标题（`### S`）

输出示例：
```json
{
  "ok": true,
  "phase": "specify",
  "passed": true,
  "reason": "design.md exists and contains at least one REQ or scenario"
}
```

### minimum-check --phase plan

检查 tasks.md + task.json anchors：
- tasks.md 存在且非空
- task.json 中 dev_entries / acceptance_entries 至少一项写入

### minimum-check --phase generate

检查 tasks.md + 解析 checklist 进度：
- tasks.md 存在且非空
- 解析子阶段 checkbox 进度
- 读取 task.json 中 task_checkpoints

### minimum-check --phase consolidate

检查 task.json consolidate 字段：
- `consolidate.noop == true` → 通过（确认无增量知识）
- `consolidate.updated_files` 非空 → 通过（至少更新了一个知识文件）
- 否则 → 未通过

## Phase 顺序

```
specify → plan → generate → consolidate → done
```

## 错误处理原则

- 返回结构化错误，而不是自然语言长文本
- 不直接修改 git 历史
- 不自动清理 working tree
- 不隐式修正 task.json / session

## 与 skill 的协作方式

- skill/LLM 负责：解释脚本输出、做最终动作选择、生成文档与代码
- scripts 负责：给 skill 提供稳定、可重复的事实输入
