package org.tiqian.pageflow.internal

import org.tiqian.pageflow.FloatArea

internal data class FloatChoiceGeneration(
    val choices: List<FloatPlacementChoice>,
    val deadlineRejections: Int,
)

internal fun generateFloatPlacementChoices(
    queue: List<PendingFloat>,
    pageIndex: Int,
    document: ValidatedDocument,
): FloatChoiceGeneration {
    if (queue.isEmpty()) {
        return FloatChoiceGeneration(
            choices = listOf(FloatPlacementChoice(emptyList(), emptyList(), emptyList(), 0.0)),
            deadlineRejections = 0,
        )
    }

    val choices = mutableListOf<FloatPlacementChoice>()
    var deadlineRejections = 0
    for (placedCount in 0..queue.size) {
        val placed = queue.take(placedCount)
        val remaining = queue.drop(placedCount)
        val deadlineMissed = remaining.any { pending ->
            val definition = document.floatsById.getValue(pending.floatId)
            pageIndex - pending.anchorPageIndex >= definition.maxPageDrift
        }
        if (deadlineMissed) {
            deadlineRejections += 1
            continue
        }

        for (topCount in 0..placedCount) {
            val top = placed.take(topCount)
            val bottom = placed.drop(topCount)
            val areasAllowed = top.all {
                FloatArea.Top in document.floatsById.getValue(it.floatId).allowedAreas
            } && bottom.all {
                FloatArea.Bottom in document.floatsById.getValue(it.floatId).allowedAreas
            }
            if (!areasAllowed) continue

            val cost = top.sumOf { pending ->
                placementCost(pending, FloatArea.Top, pageIndex, document)
            } + bottom.sumOf { pending ->
                placementCost(pending, FloatArea.Bottom, pageIndex, document)
            }
            choices += FloatPlacementChoice(
                top = top,
                bottom = bottom,
                remaining = remaining,
                cost = cost,
            )
        }
    }

    return FloatChoiceGeneration(
        choices = choices,
        deadlineRejections = deadlineRejections,
    )
}

private fun placementCost(
    pending: PendingFloat,
    area: FloatArea,
    pageIndex: Int,
    document: ValidatedDocument,
): Double {
    val definition = document.floatsById.getValue(pending.floatId)
    val drift = pageIndex - pending.anchorPageIndex
    return drift * definition.driftCostPerPage + (definition.areaCosts[area] ?: 0.0)
}
