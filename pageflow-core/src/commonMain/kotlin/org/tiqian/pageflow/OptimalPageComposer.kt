package org.tiqian.pageflow

import org.tiqian.pageflow.internal.BreakCandidate
import org.tiqian.pageflow.internal.FloatPlacementChoice
import org.tiqian.pageflow.internal.FootnoteAllocationChoice
import org.tiqian.pageflow.internal.FootnoteCursor
import org.tiqian.pageflow.internal.MeasuredFlowSegment
import org.tiqian.pageflow.internal.MinHeap
import org.tiqian.pageflow.internal.PendingFloat
import org.tiqian.pageflow.internal.QueuedState
import org.tiqian.pageflow.internal.SearchState
import org.tiqian.pageflow.internal.ValidatedDocument
import org.tiqian.pageflow.internal.ValidationResult
import org.tiqian.pageflow.internal.allocateFootnotes
import org.tiqian.pageflow.internal.breakCandidates
import org.tiqian.pageflow.internal.generateFloatPlacementChoices
import org.tiqian.pageflow.internal.key
import org.tiqian.pageflow.internal.measureSegment
import org.tiqian.pageflow.internal.normalizedStartCursor
import org.tiqian.pageflow.internal.validateDocument
import kotlin.math.min

public interface PageComposer {
    public fun compose(
        document: PageFlowDocument,
        constraints: PageConstraints,
        options: PageFlowOptions = PageFlowOptions(),
    ): PageFlowOutcome
}

/**
 * Enumerates every modeled body break, ordered float placement, and legal footnote split, then
 * runs a best-first search over complete page states. The search is exact until the explicit
 * [PageFlowSearchPolicy.maxExpandedStates] protection limit is reached; it never returns a greedy
 * approximation.
 */
public class OptimalPageComposer : PageComposer {
    override fun compose(
        document: PageFlowDocument,
        constraints: PageConstraints,
        options: PageFlowOptions,
    ): PageFlowOutcome {
        val validation = validateDocument(document, constraints)
        if (validation is ValidationResult.Invalid) {
            return PageFlowOutcome.Failure(PageFlowFailure.InvalidInput(validation.issues))
        }
        val validated = (validation as ValidationResult.Valid).document
        val initialCursor = document.body.normalizedStartCursor(0)
        if (initialCursor >= document.body.nodes.size) {
            return PageFlowOutcome.Success(
                PageFlowPlan(
                    pages = emptyList(),
                    totalCost = 0.0,
                    debug = SearchDebugInfo(
                        expandedStates = 0,
                        generatedCandidates = 0,
                        dominatedStatesPruned = 0,
                        rejectedCandidates = emptyMap(),
                        selectedTotalCost = 0.0,
                    ),
                ),
            )
        }

        val debug = MutableSearchDebug()
        val queue = MinHeap<QueuedState>(
            compareBy<QueuedState> { it.state.totalCost }
                .thenBy { it.state.pageIndex }
                .thenBy { it.insertionOrder },
        )
        var insertionOrder = 0L
        val initial = SearchState(
            bodyCursor = initialCursor,
            footnotes = emptyList(),
            floats = emptyList(),
            pageIndex = 0,
            totalCost = 0.0,
            pages = emptyList(),
        )
        val bestCostByState = mutableMapOf(initial.key() to 0.0)
        queue.add(QueuedState(initial, insertionOrder++))

        while (!queue.isEmpty) {
            val state = queue.removeFirst().state
            val knownBest = bestCostByState[state.key()]
            if (knownBest != null && state.totalCost > knownBest + COST_EPSILON) continue

            if (state.isComplete(validated)) {
                val finalDebug = debug.snapshot(selectedTotalCost = state.totalCost)
                return PageFlowOutcome.Success(
                    PageFlowPlan(
                        pages = state.pages,
                        totalCost = state.totalCost,
                        debug = finalDebug,
                    ),
                )
            }
            if (debug.expandedStates >= options.search.maxExpandedStates) {
                return PageFlowOutcome.Failure(
                    PageFlowFailure.SearchLimitExceeded(
                        maxExpandedStates = options.search.maxExpandedStates,
                        debug = debug.snapshot(selectedTotalCost = null),
                    ),
                )
            }
            debug.expandedStates += 1

            val transitions = expandState(
                state = state,
                document = validated,
                constraints = constraints,
                options = options,
                debug = debug,
            )
            transitions.forEach { next ->
                val key = next.key()
                val previous = bestCostByState[key]
                if (previous != null && previous <= next.totalCost + COST_EPSILON) {
                    debug.dominatedStatesPruned += 1
                } else {
                    bestCostByState[key] = next.totalCost
                    queue.add(QueuedState(next, insertionOrder++))
                }
            }
        }

        return PageFlowOutcome.Failure(
            PageFlowFailure.NoFeasiblePlan(debug.snapshot(selectedTotalCost = null)),
        )
    }
}

private fun expandState(
    state: SearchState,
    document: ValidatedDocument,
    constraints: PageConstraints,
    options: PageFlowOptions,
    debug: MutableSearchDebug,
): List<SearchState> {
    val transitions = mutableListOf<SearchState>()
    val body = document.source.body
    val breakCandidates = body.breakCandidates(state.bodyCursor)

    breakCandidates.forEach { breakCandidate ->
        val bodySegment = body.measureSegment(state.bodyCursor, breakCandidate.endCursor)
        val nextBodyCursor = body.normalizedStartCursor(breakCandidate.endCursor)
        val newFloats = bodySegment.newFloatIds(document).map { floatId ->
            PendingFloat(floatId = floatId, anchorPageIndex = state.pageIndex)
        }
        val newFootnoteIds = bodySegment.newFootnoteIds(document)
        val floatQueue = state.floats + newFloats
        val footnoteQueue = state.footnotes + newFootnoteIds.map { footnoteId ->
            FootnoteCursor(footnoteId = footnoteId, nodeCursor = 0, hasStarted = false)
        }

        val floatGeneration = generateFloatPlacementChoices(
            queue = floatQueue,
            pageIndex = state.pageIndex,
            document = document,
        )
        repeat(floatGeneration.deadlineRejections) {
            debug.reject(CandidateRejectionReason.FloatExceededMaximumDrift)
        }

        floatGeneration.choices.forEach { floatChoice ->
            val pageCandidates = composePageCandidates(
                state = state,
                nextBodyCursor = nextBodyCursor,
                breakCandidate = breakCandidate,
                bodySegment = bodySegment,
                floatChoice = floatChoice,
                footnoteQueue = footnoteQueue,
                newFootnoteIds = newFootnoteIds.toSet(),
                document = document,
                constraints = constraints,
                options = options,
                debug = debug,
            )
            transitions += pageCandidates
        }
    }
    return transitions
}

private fun composePageCandidates(
    state: SearchState,
    nextBodyCursor: Int,
    breakCandidate: BreakCandidate,
    bodySegment: MeasuredFlowSegment,
    floatChoice: FloatPlacementChoice,
    footnoteQueue: List<FootnoteCursor>,
    newFootnoteIds: Set<FootnoteId>,
    document: ValidatedDocument,
    constraints: PageConstraints,
    options: PageFlowOptions,
    debug: MutableSearchDebug,
): List<SearchState> {
    val contentBlockSize = constraints.contentBlockSize(state.pageIndex)
    if (contentBlockSize <= 0f) {
        debug.reject(CandidateRejectionReason.BodyDoesNotFit)
        return emptyList()
    }

    val topStackSize = floatStackSize(floatChoice.top, document, constraints)
    val bottomStackSize = floatStackSize(floatChoice.bottom, document, constraints)
    val hasBody = bodySegment.blockSize > 0f || bodySegment.boxes.isNotEmpty()
    val hasFootnotes = footnoteQueue.isNotEmpty()
    val hasTopLowerRegion = hasBody || floatChoice.bottom.isNotEmpty() || hasFootnotes
    val topGap = if (floatChoice.top.isNotEmpty() && hasTopLowerRegion) constraints.floatBodyGap else 0f
    val bodyBottomGap = if (hasBody && floatChoice.bottom.isNotEmpty()) constraints.floatBodyGap else 0f
    val bottomFootnoteGap =
        if (floatChoice.bottom.isNotEmpty() && hasFootnotes) constraints.floatBodyGap else 0f

    val fixedBlockSize = topStackSize + topGap + bodySegment.blockSize + bodyBottomGap +
        bottomStackSize + bottomFootnoteGap
    if (fixedBlockSize > contentBlockSize + GEOMETRY_EPSILON) {
        debug.reject(
            if (topStackSize + bottomStackSize > 0f) {
                CandidateRejectionReason.FloatDoesNotFit
            } else {
                CandidateRejectionReason.BodyDoesNotFit
            },
        )
        return emptyList()
    }

    val footnoteAllocations = if (hasFootnotes) {
        val maximumFootnoteArea = contentBlockSize * constraints.maxFootnoteAreaFraction
        val totalFootnoteCapacity = min(contentBlockSize - fixedBlockSize, maximumFootnoteArea)
        val contentCapacity = totalFootnoteCapacity - constraints.footnoteSeparatorBlockSize
        allocateFootnotes(
            queue = footnoteQueue,
            newFootnoteIds = newFootnoteIds,
            availableBlockSize = contentCapacity,
            constraints = constraints,
            document = document,
        )
    } else {
        listOf(FootnoteAllocationChoice(emptyList(), emptyList(), 0f, 0.0))
    }

    if (footnoteAllocations.isEmpty()) {
        debug.reject(
            if (newFootnoteIds.isNotEmpty()) {
                CandidateRejectionReason.FootnoteDoesNotFitFirstFragment
            } else {
                CandidateRejectionReason.FootnoteContinuationMadeNoProgress
            },
        )
        return emptyList()
    }

    return footnoteAllocations.mapNotNull { footnoteChoice ->
        debug.generatedCandidates += 1
        val footnoteBlockSize = if (footnoteChoice.segments.isEmpty()) {
            0f
        } else {
            constraints.footnoteSeparatorBlockSize + footnoteChoice.contentBlockSize
        }
        val usedBlockSize = fixedBlockSize + footnoteBlockSize
        if (usedBlockSize > contentBlockSize + GEOMETRY_EPSILON) {
            debug.reject(CandidateRejectionReason.BodyDoesNotFit)
            return@mapNotNull null
        }

        val bodyProgress = nextBodyCursor != state.bodyCursor
        val floatProgress = floatChoice.top.isNotEmpty() || floatChoice.bottom.isNotEmpty()
        val footnoteProgress = footnoteChoice.segments.isNotEmpty()
        if (!bodyProgress && !floatProgress && !footnoteProgress) {
            debug.reject(CandidateRejectionReason.PageMadeNoProgress)
            return@mapNotNull null
        }

        val isFinal = nextBodyCursor >= document.source.body.nodes.size &&
            floatChoice.remaining.isEmpty() && footnoteChoice.remaining.isEmpty()
        val unusedBlockSize = (contentBlockSize - usedBlockSize).coerceAtLeast(0f)
        val underfillCost = underfillCost(
            unusedBlockSize = unusedBlockSize,
            contentBlockSize = contentBlockSize,
            isFinal = isFinal,
            options = options,
        )
        val cost = PageCostBreakdown(
            underfill = underfillCost,
            breakCost = breakCandidate.breakCost,
            floatPlacement = floatChoice.cost,
            footnoteContinuation = footnoteChoice.cost,
        )
        val page = buildPagePlan(
            pageIndex = state.pageIndex,
            bodyStartCursor = state.bodyCursor,
            nextBodyCursor = nextBodyCursor,
            breakCandidate = breakCandidate,
            bodySegment = bodySegment,
            floatChoice = floatChoice,
            footnoteChoice = footnoteChoice,
            document = document,
            constraints = constraints,
            usedBlockSize = usedBlockSize,
            unusedBlockSize = unusedBlockSize,
            cost = cost,
        )
        SearchState(
            bodyCursor = nextBodyCursor,
            footnotes = footnoteChoice.remaining,
            floats = floatChoice.remaining,
            pageIndex = state.pageIndex + 1,
            totalCost = state.totalCost + cost.total,
            pages = state.pages + page,
        )
    }
}

private fun buildPagePlan(
    pageIndex: Int,
    bodyStartCursor: Int,
    nextBodyCursor: Int,
    breakCandidate: BreakCandidate,
    bodySegment: MeasuredFlowSegment,
    floatChoice: FloatPlacementChoice,
    footnoteChoice: FootnoteAllocationChoice,
    document: ValidatedDocument,
    constraints: PageConstraints,
    usedBlockSize: Float,
    unusedBlockSize: Float,
    cost: PageCostBreakdown,
): PagePlan {
    val inlineStart = constraints.inlineStartInset
    val contentBlockStart = constraints.contentBlockStart(pageIndex)
    val contentBlockEnd = constraints.contentBlockEnd()
    val topStackSize = floatStackSize(floatChoice.top, document, constraints)
    val bottomStackSize = floatStackSize(floatChoice.bottom, document, constraints)
    val hasBody = bodySegment.blockSize > 0f || bodySegment.boxes.isNotEmpty()
    val hasFootnotes = footnoteChoice.segments.isNotEmpty()
    val topGap = if (
        floatChoice.top.isNotEmpty() &&
        (hasBody || floatChoice.bottom.isNotEmpty() || hasFootnotes)
    ) {
        constraints.floatBodyGap
    } else {
        0f
    }

    val topFloats = placeTopFloats(
        choice = floatChoice,
        pageIndex = pageIndex,
        inlineStart = inlineStart,
        blockStart = contentBlockStart,
        document = document,
        constraints = constraints,
    )
    val bodyBlockStart = contentBlockStart + topStackSize + topGap
    val body = bodySegment.boxes.map { relative ->
        PlacedBodyBox(
            nodeId = relative.box.id,
            role = relative.box.role,
            sourceRange = relative.box.sourceRange,
            offset = LogicalOffset(inlineStart, bodyBlockStart + relative.blockStart),
            size = relative.box.size,
        )
    }

    val footnoteTotalSize = if (hasFootnotes) {
        constraints.footnoteSeparatorBlockSize + footnoteChoice.contentBlockSize
    } else {
        0f
    }
    val footnoteBlockStart = contentBlockEnd - footnoteTotalSize
    val bottomRegionEnd = if (hasFootnotes && floatChoice.bottom.isNotEmpty()) {
        footnoteBlockStart - constraints.floatBodyGap
    } else if (hasFootnotes) {
        footnoteBlockStart
    } else {
        contentBlockEnd
    }
    val bottomBlockStart = bottomRegionEnd - bottomStackSize
    val bottomFloats = placeBottomFloats(
        choice = floatChoice,
        pageIndex = pageIndex,
        inlineStart = inlineStart,
        blockStart = bottomBlockStart,
        document = document,
        constraints = constraints,
    )
    val footnotes = placeFootnotes(
        choice = footnoteChoice,
        inlineStart = inlineStart,
        blockStart = footnoteBlockStart + if (hasFootnotes) {
            constraints.footnoteSeparatorBlockSize
        } else {
            0f
        },
        constraints = constraints,
    )
    val contentInlineSize = constraints.contentInlineSize()
    fun region(blockStart: Float, blockSize: Float): LogicalRect? =
        blockSize.takeIf { it > 0f }?.let {
            LogicalRect(
                offset = LogicalOffset(inlineStart, blockStart),
                size = LogicalSize(contentInlineSize, it),
            )
        }

    return PagePlan(
        pageIndex = pageIndex,
        pageSize = constraints.pageSize,
        body = body,
        topFloats = topFloats,
        bottomFloats = bottomFloats,
        footnotes = footnotes,
        regions = PageRegions(
            content = LogicalRect(
                offset = LogicalOffset(inlineStart, contentBlockStart),
                size = LogicalSize(contentInlineSize, constraints.contentBlockSize(pageIndex)),
            ),
            topFloats = region(contentBlockStart, topStackSize),
            body = region(bodyBlockStart, bodySegment.blockSize),
            bottomFloats = region(bottomBlockStart, bottomStackSize),
            footnoteSeparator = if (hasFootnotes) {
                region(footnoteBlockStart, constraints.footnoteSeparatorBlockSize)
            } else {
                null
            },
            footnotes = region(
                footnoteBlockStart + constraints.footnoteSeparatorBlockSize,
                footnoteChoice.contentBlockSize,
            ),
        ),
        decision = PageDecisionInfo(
            bodyStartCursor = bodyStartCursor,
            bodyEndCursor = nextBodyCursor,
            breakNodeId = breakCandidate.breakNodeId,
            usedBlockSize = usedBlockSize,
            unusedBlockSize = unusedBlockSize,
            cost = cost,
            pendingFloatIds = floatChoice.remaining.map { it.floatId },
            pendingFootnoteIds = footnoteChoice.remaining.map { it.footnoteId },
        ),
    )
}

private fun placeTopFloats(
    choice: FloatPlacementChoice,
    pageIndex: Int,
    inlineStart: Float,
    blockStart: Float,
    document: ValidatedDocument,
    constraints: PageConstraints,
): List<PlacedFloat> {
    var cursor = blockStart
    return choice.top.mapIndexed { index, pending ->
        val definition = document.floatsById.getValue(pending.floatId)
        val placement = PlacedFloat(
            floatId = pending.floatId,
            area = FloatArea.Top,
            anchorPageIndex = pending.anchorPageIndex,
            pageDrift = pageIndex - pending.anchorPageIndex,
            sourceRange = definition.sourceRange,
            offset = LogicalOffset(inlineStart, cursor),
            size = definition.size,
        )
        cursor += definition.size.blockSize
        if (index != choice.top.lastIndex) cursor += constraints.floatStackGap
        placement
    }
}

private fun placeBottomFloats(
    choice: FloatPlacementChoice,
    pageIndex: Int,
    inlineStart: Float,
    blockStart: Float,
    document: ValidatedDocument,
    constraints: PageConstraints,
): List<PlacedFloat> {
    var cursor = blockStart
    return choice.bottom.mapIndexed { index, pending ->
        val definition = document.floatsById.getValue(pending.floatId)
        val placement = PlacedFloat(
            floatId = pending.floatId,
            area = FloatArea.Bottom,
            anchorPageIndex = pending.anchorPageIndex,
            pageDrift = pageIndex - pending.anchorPageIndex,
            sourceRange = definition.sourceRange,
            offset = LogicalOffset(inlineStart, cursor),
            size = definition.size,
        )
        cursor += definition.size.blockSize
        if (index != choice.bottom.lastIndex) cursor += constraints.floatStackGap
        placement
    }
}

private fun placeFootnotes(
    choice: FootnoteAllocationChoice,
    inlineStart: Float,
    blockStart: Float,
    constraints: PageConstraints,
): List<PlacedFootnoteBox> {
    var cursor = blockStart
    val placements = mutableListOf<PlacedFootnoteBox>()
    choice.segments.forEachIndexed { segmentIndex, segment ->
        if (segmentIndex > 0) cursor += constraints.interFootnoteGap
        segment.measured.boxes.forEach { relative ->
            placements += PlacedFootnoteBox(
                footnoteId = segment.footnoteId,
                nodeId = relative.box.id,
                role = relative.box.role,
                sourceRange = relative.box.sourceRange,
                isContinuation = segment.isContinuation,
                offset = LogicalOffset(inlineStart, cursor + relative.blockStart),
                size = relative.box.size,
            )
        }
        cursor += segment.measured.blockSize
    }
    return placements
}

private fun floatStackSize(
    pending: List<PendingFloat>,
    document: ValidatedDocument,
    constraints: PageConstraints,
): Float {
    if (pending.isEmpty()) return 0f
    return pending.sumOf { document.floatsById.getValue(it.floatId).size.blockSize.toDouble() }
        .toFloat() + constraints.floatStackGap * (pending.size - 1)
}

private fun MeasuredFlowSegment.newFloatIds(document: ValidatedDocument): List<FloatId> =
    boxes.flatMap { relative ->
        relative.box.anchors.mapNotNull { anchor ->
            val floatId = (anchor as? FlowAnchor.FloatReference)?.floatId ?: return@mapNotNull null
            floatId.takeIf { document.firstFloatAnchorNodeIndex[it] == relative.nodeIndex }
        }
    }

private fun MeasuredFlowSegment.newFootnoteIds(document: ValidatedDocument): List<FootnoteId> =
    boxes.flatMap { relative ->
        relative.box.anchors.mapNotNull { anchor ->
            val footnoteId =
                (anchor as? FlowAnchor.FootnoteReference)?.footnoteId ?: return@mapNotNull null
            footnoteId.takeIf { document.firstFootnoteAnchorNodeIndex[it] == relative.nodeIndex }
        }
    }

private fun underfillCost(
    unusedBlockSize: Float,
    contentBlockSize: Float,
    isFinal: Boolean,
    options: PageFlowOptions,
): Double {
    if (contentBlockSize <= 0f) return 0.0
    val ratio = unusedBlockSize / contentBlockSize
    val weight = if (isFinal) {
        options.costs.finalPageUnderfillWeight
    } else {
        options.costs.nonFinalPageUnderfillWeight
    }
    return ratio * ratio * weight
}

private fun SearchState.isComplete(document: ValidatedDocument): Boolean =
    bodyCursor >= document.source.body.nodes.size && footnotes.isEmpty() && floats.isEmpty()

private class MutableSearchDebug {
    var expandedStates: Int = 0
    var generatedCandidates: Int = 0
    var dominatedStatesPruned: Int = 0
    private val rejected = linkedMapOf<CandidateRejectionReason, Int>()

    fun reject(reason: CandidateRejectionReason) {
        rejected[reason] = (rejected[reason] ?: 0) + 1
    }

    fun snapshot(selectedTotalCost: Double?): SearchDebugInfo = SearchDebugInfo(
        expandedStates = expandedStates,
        generatedCandidates = generatedCandidates,
        dominatedStatesPruned = dominatedStatesPruned,
        rejectedCandidates = rejected.toMap(),
        selectedTotalCost = selectedTotalCost,
    )
}

private const val COST_EPSILON: Double = 1e-9
private const val GEOMETRY_EPSILON: Float = 1e-4f
