# 1.2 Agent 帮助与场景迁移

| 原入口/行为 | 1.2 |
|---|---|
| `skill branch`、`skill flow` | 删除；改用 `skill source`，旧调用退出 2 并提示替代入口 |
| explore 中的 Git 指南 | 改用 `skill versions` |
| SKILL/core 中的配置、重建、降级说明 | 按需读取 `skill maintenance` |
| 每个场景先读所有命令 help | 已知调用直接执行；仅有信息缺口时补读 |
| 手写 Accepts/Emits | 从实际操作契约生成；source 包含 declaration 输入 |
| operations 全局选择项 | 补充 --ref、--snapshot、--project；record 集合按名称排序；契约仍为 anatomist-operation-catalog/v1 |

无参数 `skill` 仍输出 core。单版本查询和索引格式不变；diff 输出升级见下表。
新版场景为 core、topics、explore、source、trace、relations、spring、versions、maintenance。

常用入口是精确方法 `resolve → source`。调用现场和目标方法体是不同证据；
trace 结果需选择方法后另行 resolve。regions 不能证明全部语法分支已被枚举。

构建和运行验证见 [测试说明](testing.md)。备份进度协议继续维护在
[Git snapshots](git-snapshots.md#cache-and-lifecycle) 的进度说明中。

## Diff v1 → v2

| v1 | v2 |
|---|---|
| `anatomist-diff/v1` | 当前 CLI 仅输出 `anatomist-diff/v2`；v1 schema 保留为历史参考 |
| 签名/内容变化判断 | 删除 `signature_changed`、`content_changed`；文本命中提供导航，不判断语义 |
| 忽略注释和格式 | 包含这些修改命中的声明，具体编辑查看 Git diff |
| 成员变化重复报告外层类型 | 优先最内层声明；类型自身命中或范围不足时保留类型入口 |
| 两端实体与位置 | 锚点补充 snapshot、module、scope、定位精度和来源；缺失位置不伪造 |
| 全局 `negative_conclusion_safe` | 改读 `evidence.capabilities.<能力>`；未索引不等于无变化 |
| 每个调用者只有一个来源 | 每端按调用者/修改点输出一条最短代表路径；`origin`、`caller` 为锚点 |
| impact 继承修改模块过滤 | 默认搜索整个捕获图；`--impact-module/--impact-scope` 独立筛选调用者 |
| snapshot 内嵌 metrics | diff header 精简；构建指标仍通过快照管理入口读取 |

原有 JSON/NDJSON/table 格式和版本选择参数保留。更新消费方契约判断、字段读取和
“注释修改无声明输出”的断言。无需迁移索引数据库，旧快照缺少覆盖信息时报告未知。
