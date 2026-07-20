package org.tiqian.pageflow

public data class FlowBlockId(public val value: String) {
    init {
        require(value.isNotBlank()) { "FlowBlockId must not be blank." }
    }
}

public data class FlowFragment(
    public val id: FlowNodeId,
    public val size: LogicalSize,
    public val role: BoxRole = BoxRole.Line,
    public val sourceRange: SourceRange? = null,
    public val anchors: List<FlowAnchor> = emptyList(),
)

public data class FlowBlockPolicy(
    public val splittable: Boolean = true,
    public val minFragmentsBeforeBreak: Int = 2,
    public val minFragmentsAfterBreak: Int = 2,
    public val keepWithNextFragments: Int = 0,
    public val internalBreakCost: Double = 0.0,
    public val afterBreakCost: Double = 0.0,
    public val gapAfter: Float = 0f,
) {
    init {
        require(minFragmentsBeforeBreak >= 1) { "minFragmentsBeforeBreak must be positive." }
        require(minFragmentsAfterBreak >= 1) { "minFragmentsAfterBreak must be positive." }
        require(keepWithNextFragments >= 0) { "keepWithNextFragments must be non-negative." }
        require(internalBreakCost.isFinite() && internalBreakCost >= 0.0) {
            "internalBreakCost must be finite and non-negative."
        }
        require(afterBreakCost.isFinite() && afterBreakCost >= 0.0) {
            "afterBreakCost must be finite and non-negative."
        }
        require(gapAfter.isFinite() && gapAfter >= 0f) {
            "gapAfter must be finite and non-negative."
        }
    }

    public companion object {
        public val Atomic: FlowBlockPolicy = FlowBlockPolicy(
            splittable = false,
            minFragmentsBeforeBreak = 1,
            minFragmentsAfterBreak = 1,
        )

        public fun heading(
            keepWithNextFragments: Int = 2,
            gapAfter: Float = 0f,
        ): FlowBlockPolicy = FlowBlockPolicy(
            splittable = false,
            minFragmentsBeforeBreak = 1,
            minFragmentsAfterBreak = 1,
            keepWithNextFragments = keepWithNextFragments,
            gapAfter = gapAfter,
        )
    }
}

public data class FlowBlock(
    public val id: FlowBlockId,
    public val fragments: List<FlowFragment>,
    public val policy: FlowBlockPolicy = FlowBlockPolicy(),
) {
    init {
        require(fragments.isNotEmpty()) { "FlowBlock.fragments must not be empty." }
    }
}

/**
 * Lowers semantic blocks into the explicit `Box + Break + Glue` list consumed by PageFlow.
 * Widow/orphan and keep-with-next rules become hard break constraints rather than renderer hints.
 */
public fun lowerFlowBlocks(blocks: List<FlowBlock>): VerticalFlow {
    val nodes = mutableListOf<FlowNode>()
    var keepRequirementRemaining = 0

    blocks.forEach { block ->
        val fragmentCount = block.fragments.size
        block.fragments.forEachIndexed { fragmentIndex, fragment ->
            nodes += FlowNode.Box(
                id = fragment.id,
                size = fragment.size,
                role = fragment.role,
                sourceRange = fragment.sourceRange,
                anchors = fragment.anchors,
            )
            if (keepRequirementRemaining > 0) keepRequirementRemaining -= 1

            val isLastFragment = fragmentIndex == block.fragments.lastIndex
            val normalBreakAllowed = if (isLastFragment) {
                true
            } else {
                block.policy.splittable &&
                    fragmentIndex + 1 >= block.policy.minFragmentsBeforeBreak &&
                    fragmentCount - fragmentIndex - 1 >= block.policy.minFragmentsAfterBreak
            }
            val startsKeepWithNext = isLastFragment && block.policy.keepWithNextFragments > 0
            val breakAllowed = normalBreakAllowed && keepRequirementRemaining == 0 && !startsKeepWithNext
            val breakCost = if (isLastFragment) {
                block.policy.afterBreakCost
            } else {
                block.policy.internalBreakCost
            }
            nodes += FlowNode.Break(
                id = FlowNodeId("${block.id.value}/break/${fragmentIndex + 1}"),
                rule = BreakRule(
                    requirement = if (breakAllowed) {
                        BreakRequirement.Allowed
                    } else {
                        BreakRequirement.Forbidden
                    },
                    cost = breakCost,
                ),
            )

            if (startsKeepWithNext) {
                keepRequirementRemaining = block.policy.keepWithNextFragments
            }
        }

        if (block.policy.gapAfter > 0f) {
            nodes += FlowNode.Glue(
                id = FlowNodeId("${block.id.value}/gap-after"),
                blockSize = block.policy.gapAfter,
            )
        }
    }
    return VerticalFlow(nodes)
}
