package org.tiqian.pageflow.internal

import org.tiqian.pageflow.BreakRequirement
import org.tiqian.pageflow.FlowNode
import org.tiqian.pageflow.FlowNodeId
import org.tiqian.pageflow.VerticalFlow

internal data class BreakCandidate(
    val endCursor: Int,
    val breakNodeId: FlowNodeId?,
    val breakCost: Double,
)

internal data class RelativeBoxPlacement(
    val nodeIndex: Int,
    val box: FlowNode.Box,
    val blockStart: Float,
)

internal data class MeasuredFlowSegment(
    val startCursor: Int,
    val endCursor: Int,
    val blockSize: Float,
    val boxes: List<RelativeBoxPlacement>,
) {
    val boxCount: Int
        get() = boxes.size
}

internal fun VerticalFlow.normalizedStartCursor(cursor: Int): Int {
    var next = cursor.coerceIn(0, nodes.size)
    while (next < nodes.size) {
        when (val node = nodes[next]) {
            is FlowNode.Break -> next += 1
            is FlowNode.Glue -> {
                if (!node.discardAtPageStart) return next
                next += 1
            }

            is FlowNode.Box -> return next
        }
    }
    return next
}

internal fun VerticalFlow.breakCandidates(startCursor: Int): List<BreakCandidate> {
    if (startCursor >= nodes.size) {
        return listOf(BreakCandidate(nodes.size, breakNodeId = null, breakCost = 0.0))
    }

    val candidates = mutableListOf<BreakCandidate>()
    var sawForcedBreak = false
    for (index in startCursor until nodes.size) {
        val node = nodes[index]
        if (node !is FlowNode.Break) continue
        when (node.rule.requirement) {
            BreakRequirement.Forbidden -> Unit
            BreakRequirement.Allowed -> candidates += BreakCandidate(
                endCursor = index + 1,
                breakNodeId = node.id,
                breakCost = node.rule.cost,
            )

            BreakRequirement.Forced -> {
                candidates += BreakCandidate(
                    endCursor = index + 1,
                    breakNodeId = node.id,
                    breakCost = node.rule.cost,
                )
                sawForcedBreak = true
            }
        }
        if (sawForcedBreak) break
    }

    if (!sawForcedBreak) {
        candidates += BreakCandidate(
            endCursor = nodes.size,
            breakNodeId = null,
            breakCost = 0.0,
        )
    }
    return candidates.distinctBy { it.endCursor }
}

internal fun VerticalFlow.measureSegment(startCursor: Int, endCursor: Int): MeasuredFlowSegment {
    require(startCursor in 0..nodes.size)
    require(endCursor in startCursor..nodes.size)

    var effectiveEnd = endCursor
    while (effectiveEnd > startCursor) {
        when (val node = nodes[effectiveEnd - 1]) {
            is FlowNode.Break -> effectiveEnd -= 1
            is FlowNode.Glue -> {
                if (!node.discardAtPageEnd) break
                effectiveEnd -= 1
            }

            is FlowNode.Box -> break
        }
    }

    var cursor = 0f
    val boxes = mutableListOf<RelativeBoxPlacement>()
    for (index in startCursor until effectiveEnd) {
        when (val node = nodes[index]) {
            is FlowNode.Box -> {
                boxes += RelativeBoxPlacement(index, node, cursor)
                cursor += node.size.blockSize
            }

            is FlowNode.Glue -> cursor += node.blockSize
            is FlowNode.Break -> Unit
        }
    }
    return MeasuredFlowSegment(
        startCursor = startCursor,
        endCursor = endCursor,
        blockSize = cursor,
        boxes = boxes,
    )
}
