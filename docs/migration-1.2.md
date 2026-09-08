# 1.2 Agent 帮助与场景迁移

| 原入口/行为 | 1.2 |
|---|---|
| `skill branch`、`skill flow` | 删除；改用 `skill source`，旧调用退出 2 并提示替代入口 |
| explore 中的 Git 指南 | 改用 `skill versions` |
| SKILL/core 中的配置、重建、降级说明 | 按需读取 `skill maintenance` |
| 每个场景先读所有命令 help | 已知调用直接执行；仅有信息缺口时补读 |
| 手写 Accepts/Emits | 从实际操作契约生成；source 包含 declaration 输入 |
| operations 全局选择项 | 补充 --ref、--snapshot、--project；record 集合按名称排序；契约仍为 anatomist-operation-catalog/v1 |

无参数 `skill` 仍输出 core。查询命令名称、参数、数据输出和索引格式不因这次帮助重整而改变。
新版场景为 core、topics、explore、source、trace、relations、spring、versions、maintenance。

常用入口是精确方法 `resolve → source`。调用现场和目标方法体是不同证据；
trace 结果需选择方法后另行 resolve。regions 不能证明全部语法分支已被枚举。

构建和运行验证见 [测试说明](testing.md)。备份进度协议继续维护在
[Git snapshots](git-snapshots.md#cache-and-lifecycle) 的进度说明中。
