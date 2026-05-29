# Runtime Layout

本文档只描述项目内 `.diorama/` 的运行时目录布局。

注意：
- `skills/` 与 `commands/` 属于 Agent Integration Layer，应安装在具体 Coding Agent 的 dotfiles 目录（`.qoder/`、`.claude/`、`.codex/`）
- `.diorama/` 只承载项目运行时过程、状态与产物

---

## `.diorama/` 目录结构

```text
.diorama/
├── templates/
├── knowledge/
│   ├── facts/
│   │   ├── glossary.json
│   │   └── domain-model.md
│   ├── rules/
│   │   ├── index.md
│   │   └── experience.md
│   └── references/
│       └── index.md
├── session/
├── tasks/
└── manifest.json
```

---

## 目录说明

### `templates/`

运行时产物模板。
用于生成：
- scenario
- prd
- tasks
- task.json

### `knowledge/`

项目知识层，分为三个子目录：

#### `knowledge/facts/`
从源码和 `AGENTS.md` 抽取的客观事实（source of truth）。
由运行时按需生成/更新：
- `glossary.json` — 术语表
- `domain-model.md` — 领域模型

#### `knowledge/rules/`
项目规则来源索引与经验沉淀（prescriptive）。
由运行时按需生成/更新：
- `index.md` — 规则来源索引（指向项目内规则文件或 experience.md）
- `experience.md` — 经验规则（可选，consolidate 阶段写入）

#### `knowledge/references/`
人工维护的外部参考索引。
- `index.md` — 共享参考资料入口

### `session/`

承载当前 session 热状态。

说明：
- release 时只要求目录存在
- `current-session.json` 在首次 `/diorama`（Inception）时创建

### `tasks/`

承载所有 task 运行时产物。

说明：
- release 时只要求目录存在
- 不预创建任何具体 task
- 每个 task 在首次创建时生成：
  - `proposal.md`
  - `design.md`
  - `tasks.md`
  - `task.json`

### `manifest.json`

用于标记：
- 该 `.diorama/` 是否由 Diorama runtime 导出/初始化
- layout/version 信息

建议保持最小结构。

---

## 运行时真相原则

`.diorama/` 是 Diorama 在项目中的唯一 runtime truth root。

所有以下状态都应只存在于 `.diorama/`：
- task 状态
- session 状态
- project knowledge
- runtime templates
- manifest

Agent-specific 目录中的 `skills/commands` 不保存这些真相，只负责能力接入。


## `manifest.json` 最小结构

建议保持最小结构：

```json
{
  "diorama_runtime": {
    "source": "diorama-sdd",
    "layout_version": "1",
    "runtime_version": "0.1.0",
    "initialized_at": ""
  }
}
```

### 字段说明

- `source`：标记该 runtime 来源于 `diorama-sdd`
- `layout_version`：`.diorama/` 目录布局版本
- `runtime_version`：当前 runtime 定义版本
- `initialized_at`：runtime 首次初始化时间

### 约束

- `manifest.json` 只描述 runtime layout / version / source
- 它不承载 task、session、knowledge 的运行时真相
- 它在 **Project Runtime Layer 初始化时生成**，而不是在 task Inception 时生成


## 初始化说明

- `.diorama/` 的空目录结构与 `manifest.json` 由一次性脚本创建
- 这一步不单独定义为 workflow / phase
- `current-session.json`、`task.json` 与具体 task 产物仍由 `/diorama` 与后续 workflow 在运行时生成
