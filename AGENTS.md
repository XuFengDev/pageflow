# AGENTS.md

PageFlow 是提椠之上的窄页面编排器：输入已经完成断行的行盒、原子块、浮动体与脚注，输出
平台无关的页面计划。它不拥有 shaping、字体 fallback、段落断行或平台绘制。

## 事实来源

- `README.md`：项目定位与使用入口。
- `docs/architecture.md`：当前 pipeline、模型与模块边界。
- `docs/adr/README.md`：架构决策索引。改变分页模型前先读相关 ADR。

## 实现约束

1. 页面编排只有一份真值；renderer 不得重新决定断页、浮动位置或脚注归属。
2. 浮动体、脚注与正文断页必须联合求解，不能在分页完成后用视觉偏移修补。
3. 所有 heuristic、代价和搜索上限必须命名并进入结构化 decision。
4. source range、正文顺序、浮动顺序与脚注引用关系不可因视觉放置而改写。
5. 搜索达到上限必须具名失败，不能静默退化成贪心或丢弃内容。
6. 平台 adapter 可以测量与重放，但不得拥有另一套页面规则。

## 验证

```shell
./gradlew build
./gradlew :pageflow-core:jvmTest
./gradlew :pageflow-core:jsNodeTest
```

提交前检查 `git status`、目标 diff 与测试结果。提交标题使用 `type(scope): subject`，单行、无
trailer；不要提交无关改动或生成目录。
