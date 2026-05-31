# 意图: Phase 4 Watch + 增量更新

## 你想做什么

按 `docs/scenario-3-watch.md` 推进，为 anatomist 新增文件监控和增量索引更新能力，使索引数据能与源码保持同步，无需每次全量重建。

### 范围内

1. **`file_cache` 表** — 记录已索引文件的 SHA-256 hash、node/edge 计数、stale 标记
   - 列: source_file(PK) / hash / schema_version / last_indexed / node_count / edge_count / stale / stale_reason

2. **`project_meta` 表** — 存储项目级元数据（java_version / classpath_hash / project_type / index_version 等）
   - 列: key(PK) / value

3. **`file_dependencies` 表** — 记录文件间符号解析依赖关系，用于级联 stale 标记
   - 列: source_file / depends_on_file (复合 PK)
   - 索引: idx_file_deps_target(depends_on_file)

4. **增量索引逻辑** — `anatomist index <path> --incremental`
   - 比较 file_cache hash vs 磁盘 SHA-256，只重解析变更文件
   - 删除旧数据(DELETE WHERE source_file = ?) → 重新提取 → 写入新数据，单事务完成
   - schema_version 变化时整库失效，回退全量索引

5. **WatchService 监控** — `anatomist watch <path>` 和 `anatomist watch <path> --auto-index`
   - Java NIO WatchService 监听源码目录
   - 500ms 防抖合并 IDE 连续保存事件
   - `--auto-index` 模式自动触发增量索引
   - `--extensions` 参数指定监控的文件类型

6. **级联 stale 标记** — 修改被依赖的文件时，反向标记依赖方文件为 stale
   - 输出 stale 文件清单，提示用户运行 `anatomist index --full`
   - 可选 `--cascade` 模式自动重解析 1 跳受影响文件

7. **pom.xml / build.gradle 变更检测** — classpath hash 变化时触发全量重解析

### 范围外(明确推迟)

- Scenario-4（Skills 对接 Agent LLM）
- Scenario-5（导出/可视化）
- 向量相似度搜索
- LLM enrich/annotate 工作流
- 多进程/远程 watch 协议

## 为什么做

- **现状**: `anatomist index` 每次全量删除并重建 index.db，对中大型项目耗时可达分钟级。开发过程中频繁修改 1-2 个文件后需要重跑全量索引，体验极差。
- **价值**:
  - 增量索引只重解析变更文件，响应时间从分钟级降至秒级
  - watch 模式实现"保存即索引"的开发工作流，Agent 可实时获取最新代码结构
  - stale 标记机制显式暴露跨文件依赖失效，避免沉默的数据不一致
  - 为 Scenario-4 Skills 对接提供"索引始终最新"的基础保障
- **风险控制**:
  - 增量索引与全量索引共享 Extractor 逻辑，提取逻辑零改动
  - schema 新增 3 张表(file_cache / project_meta / file_dependencies)，既有表零改动
  - `--incremental` 是可选参数，默认行为仍为全量索引，向后兼容
  - stale 标记不自动重解析，只提示，用户可控

## 需求类型

feature(Phase 4 首切片)

## 约束条件

- **不引入新生产依赖**（4 直接依赖预算继续生效；WatchService 是 JDK 内置）
- **schema 兼容向前**: file_cache / project_meta / file_dependencies 三张新表追加到 schema.sql，既有表零改动
- **索引器零回归**: Phase 1+2 fixture baseline 必须保持
- **CLI 兼容**: `anatomist index` 默认行为不变(全量)；`--incremental` 和 `--full` 是新参数；`watch` 是新子命令
- **测试覆盖**: 增量索引 ≥ 3 用例(新增/修改/删除文件)；watch 基本生命周期测试；stale 标记 ≥ 2 用例；集成测试验证 fixture 增量后数据一致性
- **事务安全**: 增量更新 SQLite 必须在单事务内完成(删除旧数据 + 插入新数据)
- **防抖窗口**: 500ms(IDE 保存场景)
