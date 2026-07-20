package org.tiqian.pageflow.internal

import org.tiqian.pageflow.FloatId
import org.tiqian.pageflow.FlowNodeId
import org.tiqian.pageflow.FootnoteId
import org.tiqian.pageflow.PagePlan

internal data class FootnoteCursor(
    val footnoteId: FootnoteId,
    val nodeCursor: Int,
    val hasStarted: Boolean,
)

internal data class PendingFloat(
    val floatId: FloatId,
    val anchorPageIndex: Int,
)

internal data class SearchState(
    val bodyCursor: Int,
    val footnotes: List<FootnoteCursor>,
    val floats: List<PendingFloat>,
    val pageIndex: Int,
    val totalCost: Double,
    val pages: List<PagePlan>,
)

internal data class SearchStateKey(
    val bodyCursor: Int,
    val footnotes: List<FootnoteCursor>,
    val floats: List<PendingFloat>,
    val pageIndex: Int,
)

internal fun SearchState.key(): SearchStateKey = SearchStateKey(
    bodyCursor = bodyCursor,
    footnotes = footnotes,
    floats = floats,
    pageIndex = pageIndex,
)

internal data class QueuedState(
    val state: SearchState,
    val insertionOrder: Long,
)

internal data class FloatPlacementChoice(
    val top: List<PendingFloat>,
    val bottom: List<PendingFloat>,
    val remaining: List<PendingFloat>,
    val cost: Double,
)

internal data class FootnoteSegmentChoice(
    val footnoteId: FootnoteId,
    val startCursor: Int,
    val endCursor: Int,
    val measured: MeasuredFlowSegment,
    val isContinuation: Boolean,
    val breakNodeId: FlowNodeId?,
)

internal data class FootnoteAllocationChoice(
    val segments: List<FootnoteSegmentChoice>,
    val remaining: List<FootnoteCursor>,
    val contentBlockSize: Float,
    val cost: Double,
)
