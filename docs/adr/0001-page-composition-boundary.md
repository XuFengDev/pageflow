# ADR 0001: 页面编排边界

- Status: Accepted
- Date: 2026-07-20

## Context

CSS multi-column 能完成视觉分栏，却不稳定暴露元素所属 fragmentainer 与精确位置。图片浮动和
栏底脚注又会改变正文可用高度；在浏览器完成分栏后补偏移无法保持正文、脚注和浮动体一致。

## Decision

PageFlow 在段落布局之后拥有页面编排真值。它消费行盒和原子块几何，联合决定正文断页、浮动体
位置与脚注区域，再输出平台无关的 `PagePlan`。平台只测量和重放，不回读 CSS 分栏结果。

PageFlow 是独立项目，不进入提椠段落核心。两者通过中立的行盒与 source range contract 连接。

## Consequences

- NOTHIN-HERE 可以把每个窄栏视为逻辑页面并横向排列。
- 视口高度变化只重跑页面编排；栏宽变化先让提椠重排段落。
- Web、Compose 或 PDF 可以共享页面决策。
- renderer 中任何重新断页、移动脚注或越过漂移上限的逻辑都属于 bug。
