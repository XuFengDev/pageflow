# ADR 0003: Apple (Kotlin/Native) 目标

- Status: Accepted
- Date: 2026-08-08
- Refines: [ADR 0001](0001-page-composition-boundary.md)（page composition 边界）

## Context

pageflow 原有 JVM 与 Kotlin/JS 目标。一个原生 macOS/iOS 阅读器要在 Apple 上做整页编排(页切分、浮动、脚注),需要 `:pageflow-core` 跑在 Kotlin/Native 的 Apple 目标上。

## Decision

`AppleNativePaginationTarget`:给 `:pageflow-core` 加 `macosArm64` 目标。

`:pageflow-core` 为纯 `commonMain`——无 `expect/actual`、无平台依赖、未应用 Android 插件——因此移植是「加一个 target」即可,无需任何逻辑改动。分页模型(`PageFlowDocument` / `OptimalPageComposer`,见 [ADR 0001](0001-page-composition-boundary.md) / [ADR 0002](0002-vertical-list-and-exact-search.md))保持不变。

## Consequences

- Apple 原生目标可用;既有分页能力——页切分、有限漂移浮动、栏底脚注(含跨页续接)、页级孤行/寡行——在 Native 上经**既有测试套件**验证通过。
- 零逻辑改动;JVM/JS 零影响。代价:多一个构建目标的维护面。

## Alternatives considered

- **在 Swift 重写分页器。** 否决:重复实现最优页面搜索 / 浮动 / 脚注逻辑,丢失单一真源。

## Verification

`:pageflow-core:macosArm64Test`:既有测试套件在 `macosArm64` 全过——包括 `respectsWidowAndOrphanConstraints…`(避头尾/孤寡行)、`footnoteReserves…` 与 `longFootnoteContinues…`(栏底脚注+续接)、`floatDeadlineForces…` 与 `floatMayDrift…`(有限漂移浮动)。
