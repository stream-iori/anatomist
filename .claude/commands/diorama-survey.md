# /diorama survey

调用 diorama skill，action: survey。

显式触发领域知识冷启动或刷新。适用于：

- 项目知识文件不存在时首次建立
- 项目结构发生重大变更后刷新知识
- 手动补充/修正 glossary 和 domain-model

无论知识文件是否存在，显式执行 survey 都会重新扫描并增量更新：
- `.diorama/knowledge/facts/glossary.json`
- `.diorama/knowledge/facts/domain-model.md`

survey 不属于 task phase，不影响 task phase checkpoints。
survey 完成后建议形成独立 knowledge commit。
