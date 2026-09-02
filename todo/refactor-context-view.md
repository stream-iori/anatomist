# Method Source Context View

> 状态：已交付。`context --source` 是已定位方法的默认阅读路径；不建设
> `control_regions`。

## 结论

```text
定位 class / method
        │
        ▼
context --source
        │
        ├─ 信息足够 ───────────────► Agent 推理
        ├─ 需要关系 ───────────────► callers / callees / field-access
        └─ 需要证明 ───────────────► branches / guards / flow
```

图索引用于定位和导航；源码用于理解条件、局部变量、顺序和返回值。紧凑
`context` 字符串只保留为位置提示，不替代源码。

## 已交付契约

| 能力 | 行为 |
|---|---|
| 精确范围 | `declarations` 保存类型、方法、构造器的 begin/end range。 |
| 源码视图 | `context <exact-method> --source` 返回声明范围内源码。 |
| 分页 | `--source-limit` / `--source-offset`；截断时给出可执行的 `next_queries`。 |
| 安全性 | 校验 source root、snapshot、路径和 symlink；不可信时 fail-closed。 |
| 输出瘦身 | JSON v2 不重复节点/边元数据；关系按需开启，成员支持分页。 |
| 兼容性 | 旧查询仍可用；schema 或图语义不兼容时索引安全重建。 |

示例：

```bash
anatomist context 'com.example.OrderService#createOrder(java.lang.String)' \
  --source --source-limit 120
```

## 为什么不建 `control_regions`

方法源码已经同时保留名称、条件、顺序和异常处理；额外 block 图会增加索引和
JSON 体积，却不能替代源码。当前没有证据表明它能提升 Agent 结果。

仅当真实任务持续证明以下任一问题，才以新的 RFC 重新评估：

- 分页源码无法定位需要的分支；
- 大量任务需要跨方法聚合精确 block 身份；
- 受限 source view 的正确率或工具调用次数持续劣于现有关系/flow 查询。

在此之前，`control_regions` 不是 backlog。

## 验证

- `ContextSourceIT` 覆盖精确范围、分页、快照警告和 fail-closed；
- C3 golden 场景锁定 CLI JSON 契约；
- JVM/native smoke 对拍 `context --source`；
- `mvn verify` 覆盖完整回归与 JaCoCo 门禁。

相关实现：`SourceContextService`、`IndexedSourceVerifier`、`ContextCommand`、
`QueryJsonContract`。
