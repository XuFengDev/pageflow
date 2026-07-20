package org.tiqian.pageflow

public data class PlacedBodyBox(
    public val nodeId: FlowNodeId,
    public val role: BoxRole,
    public val sourceRange: SourceRange?,
    public val offset: LogicalOffset,
    public val size: LogicalSize,
)

public data class PlacedFloat(
    public val floatId: FloatId,
    public val area: FloatArea,
    public val anchorPageIndex: Int,
    public val pageDrift: Int,
    public val sourceRange: SourceRange?,
    public val offset: LogicalOffset,
    public val size: LogicalSize,
)

public data class PlacedFootnoteBox(
    public val footnoteId: FootnoteId,
    public val nodeId: FlowNodeId,
    public val role: BoxRole,
    public val sourceRange: SourceRange?,
    public val isContinuation: Boolean,
    public val offset: LogicalOffset,
    public val size: LogicalSize,
)

/** Logical page geometry. The child bands do not overlap; gaps remain explicit between them. */
public data class PageRegions(
    public val content: LogicalRect,
    public val topFloats: LogicalRect?,
    public val body: LogicalRect?,
    public val bottomFloats: LogicalRect?,
    public val footnoteSeparator: LogicalRect?,
    public val footnotes: LogicalRect?,
)

public data class PageCostBreakdown(
    public val underfill: Double,
    public val breakCost: Double,
    public val floatPlacement: Double,
    public val footnoteContinuation: Double,
) {
    public val total: Double
        get() = underfill + breakCost + floatPlacement + footnoteContinuation
}

public data class PageDecisionInfo(
    public val bodyStartCursor: Int,
    public val bodyEndCursor: Int,
    public val breakNodeId: FlowNodeId?,
    public val usedBlockSize: Float,
    public val unusedBlockSize: Float,
    public val cost: PageCostBreakdown,
    public val pendingFloatIds: List<FloatId>,
    public val pendingFootnoteIds: List<FootnoteId>,
)

public data class PagePlan(
    public val pageIndex: Int,
    public val pageSize: LogicalSize,
    public val body: List<PlacedBodyBox>,
    public val topFloats: List<PlacedFloat>,
    public val bottomFloats: List<PlacedFloat>,
    public val footnotes: List<PlacedFootnoteBox>,
    public val regions: PageRegions,
    public val decision: PageDecisionInfo,
)

public enum class CandidateRejectionReason {
    BodyDoesNotFit,
    FloatDoesNotFit,
    FloatExceededMaximumDrift,
    FootnoteDoesNotFitFirstFragment,
    FootnoteContinuationMadeNoProgress,
    PageMadeNoProgress,
}

public data class SearchDebugInfo(
    public val expandedStates: Int,
    public val generatedCandidates: Int,
    public val dominatedStatesPruned: Int,
    public val rejectedCandidates: Map<CandidateRejectionReason, Int>,
    public val selectedTotalCost: Double?,
)

public data class PageFlowPlan(
    public val pages: List<PagePlan>,
    public val totalCost: Double,
    public val debug: SearchDebugInfo,
)

public sealed interface PageFlowFailure {
    public data class InvalidInput(public val issues: List<ValidationIssue>) : PageFlowFailure

    public data class SearchLimitExceeded(
        public val maxExpandedStates: Int,
        public val debug: SearchDebugInfo,
    ) : PageFlowFailure

    public data class NoFeasiblePlan(public val debug: SearchDebugInfo) : PageFlowFailure
}

public sealed interface PageFlowOutcome {
    public data class Success(public val plan: PageFlowPlan) : PageFlowOutcome

    public data class Failure(public val reason: PageFlowFailure) : PageFlowOutcome
}

public enum class ValidationIssueCode {
    DuplicateNodeId,
    DuplicateFloatId,
    DuplicateFootnoteId,
    MissingFloatDefinition,
    MissingFootnoteDefinition,
    UnreferencedFloat,
    UnreferencedFootnote,
    FootnoteHasNoBoxes,
    NestedInsertionInFootnote,
    InlineSizeExceedsPage,
    BlockSizeExceedsPage,
}

public data class ValidationIssue(
    public val code: ValidationIssueCode,
    public val subject: String,
    public val detail: String,
)
