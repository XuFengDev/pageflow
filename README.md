# PageFlow

PageFlow 是提椠之上的窄页面编排器。它把已经完成断行的正文、原子块、浮动体和脚注编排成
一系列确定的逻辑页面；页面可以表现为纸页，也可以像 NOTHIN-HERE 一样横向连续排列成窄栏。

PageFlow 不负责 shaping、字体 fallback、段落断行或绘制。它只消费平台无关的行盒与块几何，
输出可由宿主重放的 `PagePlan`。当前项目处于实验阶段，尚未发布稳定 API。

```text
paragraph layout / measured blocks
  -> vertical flow + floats + footnote insertions
  -> optimal page composition
  -> PageFlowPlan + structured decisions
  -> Web / Compose / PDF renderer
```

## 设计目标

- 分页结果由核心模型决定，不反查 CSS 多栏里的碎片位置。
- 浮动体在锚点附近的有限页面范围内参与同一轮最优化。
- 脚注占据引用所在页底部，并能反向推动正文断页。
- source range、浮动顺序和脚注顺序保持稳定。
- 输出正文、浮动、脚注分隔区和脚注区的完整逻辑几何，renderer 不重复推导布局。
- 搜索空间超过显式上限时具名失败，不静默退化为贪心分页。
- 核心使用 Kotlin Multiplatform；平台层只负责测量和重放。

## 当前模块

- `pageflow-core`：平台无关的 vertical-list 模型、页面约束、最优编排与结构化 decision。

提椠适配器与 Web renderer 会在核心输入输出契约通过真实文章 fixture 后加入；它们不会在核心
稳定前复制一套临时模型。

## 构建

项目使用 JDK 25 与 Kotlin Multiplatform：

```shell
./gradlew build
./gradlew :pageflow-core:jvmTest
./gradlew :pageflow-core:jsNodeTest
```

## 许可证

PageFlow 以 [Mozilla Public License 2.0](LICENSE) 发布。
