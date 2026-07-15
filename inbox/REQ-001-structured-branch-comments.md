# REQ-001：结构化索引 if/else 分支注释

| 字段 | 内容 |
|---|---|
| 状态 | Inbox |
| 类型 | 索引与查询能力增强 |
| 优先级 | 待评估 |

## 背景

当前 `branches-of` 能识别 `if-then`、`if-else` 等分支上下文，并可通过
`--source-window` 返回附近的原始源码。源码窗口可能包含 `//`、`/* */` 注释，
但注释没有作为独立事实写入索引，也没有与具体分支建立关系。

因此目前无法可靠回答：

- 某个 `if/else` 分支对应什么业务说明？
- 哪些分支带有风险、兼容、兜底等注释？
- 按注释内容搜索相关分支和调用链。

## 目标

将 Java 源码注释结构化索引，并把能够明确归属的注释关联到对应分支，使
Agent 不依赖固定大小的源码窗口也能查询和引用分支说明。

```text
COMMENT ── DOCUMENTS ──> BRANCH
                           ├─ if-then@L42
                           └─ if-else@L42
```

## 功能要求

1. 提取行注释、块注释，并保存原文、文件、起止位置和注释类型。
2. 将紧邻 `if`、then body、else body 的注释关联到具体分支上下文。
3. 保留当前轻量分支模型，不要求构建完整 CFG。
4. `branches-of` 返回分支关联的注释；没有注释时保持字段为空。
5. 支持按注释关键词搜索，并返回所属方法、分支、文件和行号。
6. 注释归属不明确时保留为文件级注释，不进行猜测性绑定。

## 建议数据模型

### 节点

新增 `COMMENT`：

| 字段 | 说明 |
|---|---|
| `id` | `<source-file>::COMMENT@L<line>C<column>` |
| `text` | 规范化后的注释正文 |
| `comment_kind` | `LINE`、`BLOCK` |
| `source_file` | 项目相对路径 |
| `source_location` | 起止行列 |

### 关系

新增 `DOCUMENTS`：

```text
COMMENT -> METHOD
COMMENT -> BRANCH_CONTEXT
```

如果不新增独立 `BRANCH_CONTEXT` 节点，关系目标可以使用方法节点，并在关系
metadata 中保存 `context=if-then@L42`。

## 查询接口

建议扩展：

```bash
anatomist branches-of OrderService#create --with-comments
anatomist search-comment "风险" --in-branch
```

`branches-of --with-comments --format json` 示例：

```json
{
  "context": "if-else@L42",
  "comments": [
    {
      "text": "风控失败时直接拒绝",
      "source_file": "src/main/java/com/example/OrderService.java",
      "source_location": "L41"
    }
  ]
}
```

## 验收标准

- then/else 前置注释能够准确归属到对应分支。
- 分支内部注释能够关联到所在分支。
- 方法 Javadoc 不会被重复识别为普通分支注释。
- 注释中包含中文、英文、特殊符号时可正常存储和搜索。
- `branches-of --with-comments` 的 JSON 和 Markdown 输出稳定。
- 增量索引在注释新增、修改、删除后正确更新相关事实。
- 无调用、字段读写的纯判断分支，也能通过注释查询被发现。

## 不在本需求范围

- 根据注释判断业务事实真伪。
- 自动修正过期或错误注释。
- 构建完整控制流图。
- 将普通注释自动提升为领域知识。
