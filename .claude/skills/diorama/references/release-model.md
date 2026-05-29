# Release Model

## 目标

将 `diorama-sdd/` 中的开发态定义，发布为两类目标：

1. **Agent Integration Layer**：安装到具体 Coding Agent 的 dotfiles 目录（`.qoder/`、`.claude/`、`.codex/`）
2. **Project Runtime Layer**：安装到项目内统一的 `.diorama/` 目录

这样可以将：
- **Agent 能力接入**
- **项目运行时真相与产物**

明确分层，而不是混在同一个目录中。

---

## 一、Agent Integration Layer

这一层面向具体 Coding Agent，负责让 `/diorama` 命令与 `diorama` skill 被 agent 识别和加载。

### 发布目标

- `.qoder/`
- `.claude/`
- `.codex/`

### 发布内容

- `diorama-sdd/skills/diorama/`
- `diorama-sdd/commands/`

### 说明

- `skills/` 与 `commands/` 属于 **Agent 集成资产**
- 它们不承载 task / session / knowledge 的真相
- 不同 Agent 平台可以共享同一个项目下的 `.diorama/` runtime

---

## 二、Project Runtime Layer

这一层面向项目本身，负责承载 Diorama 的运行时过程与产物。

### 发布目标

- 项目根目录下的 `.diorama/`

Project Runtime Layer 的初始化由脚本负责创建，不单独抽象为 workflow phase 或独立 runtime protocol。

当前已提供最小 Project Runtime Layer 初始化脚本：`diorama-sdd/scripts/init_diorama_runtime.py`。

### 发布内容

- `diorama-sdd/templates/` → `.diorama/templates/`
- `diorama-sdd/knowledge/references/index.md` → `.diorama/knowledge/references/index.md`
- 创建目录：
  - `.diorama/knowledge/facts/`
  - `.diorama/knowledge/rules/`
  - `.diorama/knowledge/references/`
  - `.diorama/session/`
  - `.diorama/tasks/`
- 生成：
  - `.diorama/manifest.json`

`manifest.json` 在 **Project Runtime Layer 初始化时生成**，用于标记 runtime 来源、layout version 与初始化时间。

### 不直接预创建的运行时文件

以下内容由运行时按需生成，不在 release 时预置内容：

- `.diorama/session/current-session.json`
- `.diorama/knowledge/facts/glossary.json`
- `.diorama/knowledge/facts/domain-model.md`
- `.diorama/knowledge/rules/index.md`（survey 阶段生成）
- `.diorama/knowledge/rules/experience.md`
- `.diorama/tasks/<task>/...`

---

## 三、Runtime Truth Root

Diorama 的唯一项目运行时真相根为：

- `.diorama/`

其中承载：
- task 状态
- session
- project knowledge
- templates
- manifest

### 关键规则

- `skills` / `commands` 虽然安装在 agent-specific 目录中，但运行时读写始终指向 **项目根下的 `.diorama/`**
- Agent 平台目录只负责 **能力接入**，不负责承载 task runtime 真相
- 不同 Agent 平台应共享同一个 `.diorama/`，避免产生多份 task/session 状态

---

## 四、不应发布到运行态的开发资产

以下内容保留在开发态 `diorama-sdd/`，不导出到目标项目运行时：

- `fixtures/`
- `STATUS.md`
- 开发态说明性 README（如 `diorama-sdd/README.md`）

这些文件用于：
- 设计演进
- helper 验证
- 阶段性总结

而不属于项目运行时必需资产。

---

## 五、当前结论

当前 release 模型不是“单目录导出”，而是：

- **Agent Integration Layer**：`skills/` + `commands/`
- **Project Runtime Layer**：`.diorama/`

这使得 Diorama 既能适配不同 Coding Agent，又能保证项目运行时状态集中、稳定、可共享。


## 六、Manifest 语义

`.diorama/manifest.json` 的职责是：

- 标记该 `.diorama/` 是否由 Diorama runtime 初始化
- 声明当前 runtime 的 `layout_version`
- 记录 runtime 来源与初始化时间

它不负责记录：
- task 状态
- session 状态
- knowledge 内容
- agent integration 信息

也就是说，`manifest.json` 只承载 **runtime identity / layout metadata**，不承载运行时真相。
