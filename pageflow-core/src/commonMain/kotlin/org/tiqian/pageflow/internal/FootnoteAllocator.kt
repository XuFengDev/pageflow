package org.tiqian.pageflow.internal

import org.tiqian.pageflow.Footnote
import org.tiqian.pageflow.FootnoteId
import org.tiqian.pageflow.PageConstraints

internal fun allocateFootnotes(
    queue: List<FootnoteCursor>,
    newFootnoteIds: Set<FootnoteId>,
    availableBlockSize: Float,
    constraints: PageConstraints,
    document: ValidatedDocument,
): List<FootnoteAllocationChoice> {
    if (queue.isEmpty()) {
        return listOf(
            FootnoteAllocationChoice(
                segments = emptyList(),
                remaining = emptyList(),
                contentBlockSize = 0f,
                cost = 0.0,
            ),
        )
    }
    if (availableBlockSize <= 0f) return emptyList()

    val results = mutableListOf<FootnoteAllocationChoice>()

    fun recurse(
        queueIndex: Int,
        remainingCapacity: Float,
        segments: List<FootnoteSegmentChoice>,
        consumedBlockSize: Float,
        cost: Double,
    ) {
        if (queueIndex >= queue.size) {
            results += FootnoteAllocationChoice(
                segments = segments,
                remaining = emptyList(),
                contentBlockSize = consumedBlockSize,
                cost = cost,
            )
            return
        }

        val cursor = queue[queueIndex]
        val footnote = document.footnotesById.getValue(cursor.footnoteId)
        val startCursor = footnote.content.normalizedStartCursor(cursor.nodeCursor)
        val gap = if (segments.isEmpty()) 0f else constraints.interFootnoteGap
        if (gap > remainingCapacity) return

        val segmentCandidates = footnote.content.breakCandidates(startCursor)
            .map { breakCandidate ->
                breakCandidate to footnote.content.measureSegment(startCursor, breakCandidate.endCursor)
            }
            .filter { (_, measured) ->
                measured.boxCount >= minimumBoxCount(footnote, cursor) &&
                    measured.blockSize + gap <= remainingCapacity
            }
            .filter { (breakCandidate, _) ->
                val complete = footnote.content.normalizedStartCursor(breakCandidate.endCursor) >=
                    footnote.content.nodes.size
                footnote.allowContinuation || complete
            }

        segmentCandidates.forEach { (breakCandidate, measured) ->
            val nextCursor = footnote.content.normalizedStartCursor(breakCandidate.endCursor)
            val complete = nextCursor >= footnote.content.nodes.size
            val nextSegment = FootnoteSegmentChoice(
                footnoteId = cursor.footnoteId,
                startCursor = startCursor,
                endCursor = breakCandidate.endCursor,
                measured = measured,
                isContinuation = cursor.hasStarted,
                breakNodeId = breakCandidate.breakNodeId,
            )
            val nextSegments = segments + nextSegment
            val nextConsumed = consumedBlockSize + gap + measured.blockSize
            val nextCost = cost + breakCandidate.breakCost +
                if (complete) 0.0 else footnote.continuationCost

            if (complete) {
                recurse(
                    queueIndex = queueIndex + 1,
                    remainingCapacity = remainingCapacity - gap - measured.blockSize,
                    segments = nextSegments,
                    consumedBlockSize = nextConsumed,
                    cost = nextCost,
                )
            } else {
                val unstartedNewFootnoteRemains = queue.drop(queueIndex + 1).any {
                    it.footnoteId in newFootnoteIds
                }
                if (!unstartedNewFootnoteRemains) {
                    results += FootnoteAllocationChoice(
                        segments = nextSegments,
                        remaining = listOf(
                            FootnoteCursor(
                                footnoteId = cursor.footnoteId,
                                nodeCursor = nextCursor,
                                hasStarted = true,
                            ),
                        ) + queue.drop(queueIndex + 1),
                        contentBlockSize = nextConsumed,
                        cost = nextCost,
                    )
                }
            }
        }
    }

    recurse(
        queueIndex = 0,
        remainingCapacity = availableBlockSize,
        segments = emptyList(),
        consumedBlockSize = 0f,
        cost = 0.0,
    )
    return results.distinctBy { choice ->
        choice.segments.map { Triple(it.footnoteId, it.startCursor, it.endCursor) } to choice.remaining
    }
}

private fun minimumBoxCount(footnote: Footnote, cursor: FootnoteCursor): Int =
    if (cursor.hasStarted) footnote.minContinuationPageBoxes else footnote.minFirstPageBoxes
