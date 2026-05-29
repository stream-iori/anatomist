# Consolidate: 增量提炼项目知识

## 职责

Consolidate 在 generate 完成后，将本次 task 产出的增量知识回写到项目知识文件。

它应：
- 从已完成的 task 产物中提取新发现的领域概念
- 从 generate 阶段的代码实现中提炼技术经验
- 更新 glossary 和 domain-model
- 视情况生成或更新 experience.md（经验规则）

它不应：
- 重新执行 survey 的全量扫描
- 提取 task-specific 的实现决策（这些属于 task 产物）

## Phase 协议

遵循 phase-protocol.md 通用 Entry/Exit 协议。

| 项 | 值 |
|----|-----|
| 前置输入 | tasks.md |
| 产物 | knowledge files updated (glossary.json, domain-model.md, experience.md) |
| anchors | 不碰 |
| next | done |

## 触发条件

Consolidate 是强制 phase。generate exit 后自动进入。

## 输入

- `.diorama/tasks/<completed-task>/` 中的所有产物（proposal.md, design.md, tasks.md）
- 已有的 `.diorama/knowledge/facts/` 和 `.diorama/knowledge/rules/` 内容
- 当次 task 中新增/修改的代码变更

## 输出

增量更新：

- `.diorama/knowledge/facts/glossary.json` — 追加新术语
- `.diorama/knowledge/facts/domain-model.md` — 补充新实体/关系
- `.diorama/knowledge/rules/experience.md` — 补充技术经验规则（可选）
- `.diorama/knowledge/rules/index.md` — 若 experience.md 新增或更新，同步更新索引条目

### experience.md 定位

experience.md 是项目级测试与实现经验规则，服务于后续 task 的 plan 和 generate 阶段。

它是可选文件，仅在 consolidate 阶段发现有可沉淀的经验规则时写入。

关键约束：
- experience.md 不存在 → rules/index.md 中无对应条目
- experience.md 存在 → rules/index.md 中有对应条目（`experience.md — 经验规则 — <摘要>`）
- consolidate 写入 experience.md 时，必须同步更新 rules/index.md 中的索引条目

与 survey 扫描的规则文件不同，experience.md 存放的是 Agent 在实践中提炼的、没有自然归属文件的经验规则。

## 与 survey 的关系

| 维度 | survey | consolidate |
|------|--------|-------------|
| 触发 | 知识文件缺失时建议 | generate 完成后自动进入（强制） |
| 范围 | 全项目 | 当次 task 产物 |
| 性质 | 冷启动 | 增量更新 |
| 产出 | glossary + domain-model + tech-context | glossary + domain-model + experience.md + index.md 增量 |
| 协议 | 独立 knowledge commit | phase checkpoint + handoff commit |

## 增量更新规则

### glossary.json

- 新发现的术语：追加到 `terms` 数组
- 已有术语的 definition 改进：更新 definition 字段
- 涉及的术语：更新 `last_verified` 为当前日期（ISO 格式）
- 不主动删除任何术语

### domain-model.md

- 新实体/关系：追加到文档
- 已有实体描述改进：更新
- Mermaid 图更新：当新增或修改核心实体关系时，同步更新类图/时序图以反映最新领域结构
  - 类图仅保留一张，更新实体与关系
  - 时序图按场景组织，新增场景时追加新图，修改场景时更新对应图
  - **禁止**生成 Technology Stack 内容
- 不主动删除已有内容

#### 时序图回写规则

design.md §6 变更时序图中的内容**必须回写**到 domain-model.md，保证领域知识与实际代码一致：

1. **变更链路时序图 → 场景时序图**：将 design.md §6 变更链路时序图去掉 `[NEW]`/`[MOD]` 标注后，作为该场景的最新时序图写入 domain-model.md
2. **新增场景**：如果变更链路涉及 domain-model.md 中不存在的场景，追加新场景标题 + 时序图
3. **修改场景**：如果变更链路对应 domain-model.md 中已有的场景，用去标注后的版本替换原时序图
4. **参考时序图无需回写**：design.md §6 中的"参考时序图（来自 domain-model）"是变更前快照，不需要回写

### experience.md

- 新发现的测试/实现规则：追加
- 已有规则与实际情况不符：标注 `[OUTDATED]`，不直接删除
- 规则调整：根据项目实际需要调整规则索引

### rules/index.md

- experience.md 新增或更新时：同步更新对应索引条目
- 新发现外部规则文件时：追加条目（通常由 survey 阶段处理）
- 索引条目指向的文件不存在时：移除该条目

## task.json 记录

Consolidate phase 需在 task.json 中记录进度：

```json
{
  "consolidate": {
    "noop": false,
    "updated_files": ["knowledge/facts/glossary.json"]
  }
}
```

- 若更新了知识文件，将文件相对路径写入 `updated_files`
- 若确认无增量知识，设置 `noop: true`

## Git 语义

Consolidate 属于 task phase，写入 task checkpoints。

- Checkpoint commit: `diorama(consolidate): finish consolidate <task>`
  - 包含知识文件更新 + task.json 变更
- Handoff commit: `diorama(handoff): consolidate → done`

## 最低完成判定

满足以下任一条件，consolidate 可视为已完成：

- task.json 中 `consolidate.updated_files` 非空（至少更新了一个知识文件）
- task.json 中 `consolidate.noop == true`（确认无增量知识）

若确认无增量可提炼，应设置 `noop: true` 并输出简要说明，而非强制写入空变更。
