# PageFlow 当前架构

## 范围

PageFlow 解决的是“已知行盒如何进入一系列等宽、等高逻辑页面”。页面在 Web 上可以作为横向
窄栏连续排列；核心不感知滚动方向、DOM、纸张或渲染技术。

核心负责：

- 正文与块级内容的页面断点；
- 孤行、寡行、标题黏连等由输入 penalty 表达的约束；
- 浮动体在显式漂移范围内的顶部或底部放置；
- 脚注在首次引用所在页开始，并从页底向上占据空间；
- 全局代价最小的页面序列与可解释决策。

核心不负责：

- shaping、字体选择、段落断行与两端对齐；
- DOM 测量、图片解码、字体加载与绘制；
- 任意轮廓图文环绕、跨页对象、左右页、出血与印刷标记。

## Pipeline

```text
Tiqian LayoutResult / another paragraph engine / measured atomic blocks
  -> VerticalFlow(Box + Glue + Break)
  -> float and footnote definitions attached to source boxes
  -> exact best-first page composition
  -> PagePlan[] + SearchDebugInfo
  -> platform renderer
```

`Box` 是不可拆的行或块，`Glue` 是显式纵向间距，`Break` 是带代价的合法或强制断页点。
脚注引用与浮动锚点属于承载它们的 `Box`，因此引用行被推出页面时，插入物会与它一起移动。

## 搜索契约

页面状态包含正文 cursor、未完成脚注 cursor、等待放置的浮动体及当前页号。核心枚举合法正文
断点、浮动区域和脚注分段，以非负代价执行 best-first search。相同状态只保留更低代价路径。

这保证在已建模的候选空间内返回全局最优结果。`maxExpandedStates` 是资源保护栏，不是近似模式；
超过上限会返回 `SearchLimitExceeded`，不会偷偷切换为贪心分页。

## 平台边界

平台负责把字体就绪后的段落结果和原子块尺寸 lowering 成 `VerticalFlow`，并按 `PagePlan` 重放。
Web renderer 不读取 CSS multi-column 的碎片位置；resize 只改变页面 block size，并用缓存的行盒重跑
页面编排。若栏宽改变，宿主先让段落引擎重新断行，再把新行盒交给 PageFlow。
