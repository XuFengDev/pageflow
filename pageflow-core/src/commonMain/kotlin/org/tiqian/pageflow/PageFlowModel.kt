package org.tiqian.pageflow

import kotlin.jvm.JvmInline

@JvmInline
public value class FlowNodeId(public val value: String) {
    init {
        require(value.isNotBlank()) { "FlowNodeId must not be blank." }
    }

    override fun toString(): String = value
}

@JvmInline
public value class FloatId(public val value: String) {
    init {
        require(value.isNotBlank()) { "FloatId must not be blank." }
    }

    override fun toString(): String = value
}

@JvmInline
public value class FootnoteId(public val value: String) {
    init {
        require(value.isNotBlank()) { "FootnoteId must not be blank." }
    }

    override fun toString(): String = value
}

public data class SourceRange(
    public val start: Int,
    public val endExclusive: Int,
) {
    init {
        require(start >= 0) { "SourceRange.start must be non-negative." }
        require(endExclusive >= start) { "SourceRange.endExclusive must not precede start." }
    }
}

public data class LogicalSize(
    public val inlineSize: Float,
    public val blockSize: Float,
) {
    init {
        require(inlineSize.isFinite() && inlineSize >= 0f) {
            "LogicalSize.inlineSize must be finite and non-negative."
        }
        require(blockSize.isFinite() && blockSize >= 0f) {
            "LogicalSize.blockSize must be finite and non-negative."
        }
    }
}

public data class LogicalOffset(
    public val inlineStart: Float,
    public val blockStart: Float,
) {
    init {
        require(inlineStart.isFinite()) { "LogicalOffset.inlineStart must be finite." }
        require(blockStart.isFinite()) { "LogicalOffset.blockStart must be finite." }
    }
}

public data class LogicalRect(
    public val offset: LogicalOffset,
    public val size: LogicalSize,
)

public enum class BoxRole {
    Line,
    Heading,
    AtomicBlock,
    Decoration,
}

public sealed interface FlowAnchor {
    public data class FloatReference(public val floatId: FloatId) : FlowAnchor

    public data class FootnoteReference(public val footnoteId: FootnoteId) : FlowAnchor
}

public enum class BreakRequirement {
    Allowed,
    Forbidden,
    Forced,
}

public data class BreakRule(
    public val requirement: BreakRequirement = BreakRequirement.Allowed,
    public val cost: Double = 0.0,
) {
    init {
        require(cost.isFinite() && cost >= 0.0) {
            "BreakRule.cost must be finite and non-negative."
        }
    }
}

public sealed interface FlowNode {
    public val id: FlowNodeId

    public data class Box(
        override val id: FlowNodeId,
        public val size: LogicalSize,
        public val role: BoxRole = BoxRole.Line,
        public val sourceRange: SourceRange? = null,
        public val anchors: List<FlowAnchor> = emptyList(),
    ) : FlowNode

    public data class Glue(
        override val id: FlowNodeId,
        public val blockSize: Float,
        public val discardAtPageStart: Boolean = true,
        public val discardAtPageEnd: Boolean = true,
    ) : FlowNode {
        init {
            require(blockSize.isFinite() && blockSize >= 0f) {
                "Glue.blockSize must be finite and non-negative."
            }
        }
    }

    public data class Break(
        override val id: FlowNodeId,
        public val rule: BreakRule = BreakRule(),
    ) : FlowNode
}

public data class VerticalFlow(public val nodes: List<FlowNode>)

public enum class FloatArea {
    Top,
    Bottom,
}

public data class FloatObject(
    public val id: FloatId,
    public val size: LogicalSize,
    public val allowedAreas: Set<FloatArea>,
    public val maxPageDrift: Int,
    public val driftCostPerPage: Double,
    public val areaCosts: Map<FloatArea, Double> = emptyMap(),
    public val sourceRange: SourceRange? = null,
) {
    init {
        require(allowedAreas.isNotEmpty()) { "FloatObject.allowedAreas must not be empty." }
        require(maxPageDrift >= 0) { "FloatObject.maxPageDrift must be non-negative." }
        require(driftCostPerPage.isFinite() && driftCostPerPage >= 0.0) {
            "FloatObject.driftCostPerPage must be finite and non-negative."
        }
        require(areaCosts.keys.all { it in allowedAreas }) {
            "FloatObject.areaCosts must only name allowed areas."
        }
        require(areaCosts.values.all { it.isFinite() && it >= 0.0 }) {
            "FloatObject.areaCosts must be finite and non-negative."
        }
    }
}

public data class Footnote(
    public val id: FootnoteId,
    public val content: VerticalFlow,
    public val allowContinuation: Boolean = true,
    public val minFirstPageBoxes: Int = 1,
    public val minContinuationPageBoxes: Int = 1,
    public val continuationCost: Double = 0.0,
) {
    init {
        require(minFirstPageBoxes >= 1) { "Footnote.minFirstPageBoxes must be at least one." }
        require(minContinuationPageBoxes >= 1) {
            "Footnote.minContinuationPageBoxes must be at least one."
        }
        require(continuationCost.isFinite() && continuationCost >= 0.0) {
            "Footnote.continuationCost must be finite and non-negative."
        }
    }
}

public data class PageFlowDocument(
    public val body: VerticalFlow,
    public val floats: List<FloatObject> = emptyList(),
    public val footnotes: List<Footnote> = emptyList(),
)

public data class PageConstraints(
    public val pageSize: LogicalSize,
    public val blockStartInset: Float = 0f,
    public val blockEndInset: Float = 0f,
    public val inlineStartInset: Float = 0f,
    public val inlineEndInset: Float = 0f,
    public val firstPageAdditionalBlockStartInset: Float = 0f,
    public val floatBodyGap: Float = 0f,
    public val floatStackGap: Float = 0f,
    public val footnoteSeparatorBlockSize: Float = 0f,
    public val interFootnoteGap: Float = 0f,
    public val maxFootnoteAreaFraction: Float = 1f,
) {
    init {
        val nonNegativeValues = listOf(
            blockStartInset,
            blockEndInset,
            inlineStartInset,
            inlineEndInset,
            firstPageAdditionalBlockStartInset,
            floatBodyGap,
            floatStackGap,
            footnoteSeparatorBlockSize,
            interFootnoteGap,
        )
        require(nonNegativeValues.all { it.isFinite() && it >= 0f }) {
            "PageConstraints insets and gaps must be finite and non-negative."
        }
        require(maxFootnoteAreaFraction.isFinite() && maxFootnoteAreaFraction > 0f) {
            "PageConstraints.maxFootnoteAreaFraction must be finite and positive."
        }
        require(maxFootnoteAreaFraction <= 1f) {
            "PageConstraints.maxFootnoteAreaFraction must not exceed one."
        }
        require(inlineStartInset + inlineEndInset < pageSize.inlineSize) {
            "Inline insets must leave a positive content inline size."
        }
        require(blockStartInset + blockEndInset < pageSize.blockSize) {
            "Block insets must leave a positive content block size."
        }
        require(
            blockStartInset + firstPageAdditionalBlockStartInset + blockEndInset <
                pageSize.blockSize,
        ) {
            "First-page block insets must leave a positive content block size."
        }
    }

    public fun contentInlineSize(): Float = pageSize.inlineSize - inlineStartInset - inlineEndInset

    public fun contentBlockStart(pageIndex: Int): Float =
        blockStartInset + if (pageIndex == 0) firstPageAdditionalBlockStartInset else 0f

    public fun contentBlockEnd(): Float = pageSize.blockSize - blockEndInset

    public fun contentBlockSize(pageIndex: Int): Float = contentBlockEnd() - contentBlockStart(pageIndex)
}

public data class PageFlowCostPolicy(
    public val nonFinalPageUnderfillWeight: Double = 100.0,
    public val finalPageUnderfillWeight: Double = 0.0,
) {
    init {
        require(nonFinalPageUnderfillWeight.isFinite() && nonFinalPageUnderfillWeight >= 0.0) {
            "nonFinalPageUnderfillWeight must be finite and non-negative."
        }
        require(finalPageUnderfillWeight.isFinite() && finalPageUnderfillWeight >= 0.0) {
            "finalPageUnderfillWeight must be finite and non-negative."
        }
    }
}

public data class PageFlowSearchPolicy(
    public val maxExpandedStates: Int = 100_000,
) {
    init {
        require(maxExpandedStates > 0) { "maxExpandedStates must be positive." }
    }
}

public data class PageFlowOptions(
    public val costs: PageFlowCostPolicy = PageFlowCostPolicy(),
    public val search: PageFlowSearchPolicy = PageFlowSearchPolicy(),
)
