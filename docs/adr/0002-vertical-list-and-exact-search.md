# ADR 0002: Vertical list 与精确搜索

- Status: Accepted
- Date: 2026-07-20

## Context

按 DOM 高度逐块贪心装栏不能拆分长段落，也无法在脚注增长或图片漂移时回看先前断点。只做局部
lookahead 会把算法限制固化成宿主难以解释的空白和跳页。

## Decision

正文 lowering 为显式 `Box + Glue + Break` vertical list：

- `Box`：不可拆的行或原子块，可携带脚注引用与浮动锚点；
- `Glue`：自然纵向间距，可在页首或页尾丢弃；
- `Break`：合法、禁止或强制断页点，并携带非负代价。

编排器以正文 cursor、脚注 continuation、等待浮动体和页号为状态，枚举所有合法下一页，以
best-first search 求全局最低代价。相同状态的高代价路径被支配剪枝；达到显式资源上限则失败，
不返回未经声明的近似结果。

## Consequences

- 孤行、标题黏连和作者偏好由 lowering 生成具名 break penalty，而不是写死字符规则。
- 新脚注必须在引用页放入首个合法片段；放不下时，引用行随正文断点一起后移。
- 浮动体按源码顺序放置，并受 `maxPageDrift` 硬约束。
- 每页保留 cost breakdown、断点、浮动和脚注 continuation decision。
