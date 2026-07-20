package org.tiqian.pageflow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OptimalPageComposerTest {
    private val composer = OptimalPageComposer()

    @Test
    fun respectsWidowAndOrphanConstraintsAcrossTheGloballyBestBreak() {
        val body = lowerFlowBlocks(
            listOf(paragraph("p", lineCount = 5, lineHeight = 30f)),
        )

        val plan = composeSuccessfully(body = body, pageHeight = 100f)

        assertEquals(listOf(3, 2), plan.pages.map { it.body.size })
        assertEquals(listOf("p-line-1", "p-line-2", "p-line-3"), plan.pages[0].body.map { it.nodeId.value })
        assertEquals(listOf("p-line-4", "p-line-5"), plan.pages[1].body.map { it.nodeId.value })
    }

    @Test
    fun firstPageInsetRepaginatesWithoutChangingLaterPageGeometry() {
        val body = lowerFlowBlocks(
            listOf(
                paragraph(
                    id = "p",
                    lineCount = 5,
                    lineHeight = 30f,
                    policy = FlowBlockPolicy(
                        minFragmentsBeforeBreak = 1,
                        minFragmentsAfterBreak = 1,
                    ),
                ),
            ),
        )

        val plan = composeSuccessfully(
            body = body,
            pageHeight = 100f,
            constraints = constraints(
                pageHeight = 100f,
                firstPageAdditionalBlockStartInset = 40f,
            ),
        )

        assertEquals(listOf(2, 3), plan.pages.map { it.body.size })
        assertEquals(40f, plan.pages[0].body.first().offset.blockStart)
        assertEquals(0f, plan.pages[1].body.first().offset.blockStart)
    }

    @Test
    fun footnoteReservesTheReferencePageBottomAndPushesFollowingBodyLines() {
        val footnoteId = FootnoteId("fn-1")
        val block = paragraph(
            id = "p",
            lineCount = 5,
            lineHeight = 20f,
            policy = FlowBlockPolicy(
                minFragmentsBeforeBreak = 1,
                minFragmentsAfterBreak = 1,
            ),
            anchorsByLine = mapOf(
                3 to listOf(FlowAnchor.FootnoteReference(footnoteId)),
            ),
        )
        val footnote = Footnote(
            id = footnoteId,
            content = lowerFlowBlocks(
                listOf(atomicBlock("fn-1-body", blockHeight = 30f, role = BoxRole.Line)),
            ),
        )

        val plan = composeSuccessfully(
            body = lowerFlowBlocks(listOf(block)),
            pageHeight = 100f,
            footnotes = listOf(footnote),
            constraints = constraints(pageHeight = 100f, footnoteSeparatorBlockSize = 5f),
        )

        assertEquals(listOf(3, 2), plan.pages.map { it.body.size })
        assertEquals(listOf("fn-1-body-fragment-1"), plan.pages[0].footnotes.map { it.nodeId.value })
        assertTrue(plan.pages[1].footnotes.isEmpty())
        assertEquals(70f, plan.pages[0].footnotes.single().offset.blockStart)
        assertEquals(65f, plan.pages[0].regions.footnoteSeparator?.offset?.blockStart)
        assertEquals(5f, plan.pages[0].regions.footnoteSeparator?.size?.blockSize)
        assertEquals(70f, plan.pages[0].regions.footnotes?.offset?.blockStart)
    }

    @Test
    fun longFootnoteContinuesAtTheNextPageBottomWithoutDetachingItsFirstFragment() {
        val footnoteId = FootnoteId("fn-long")
        val body = lowerFlowBlocks(
            listOf(
                paragraph(
                    id = "p",
                    lineCount = 1,
                    lineHeight = 20f,
                    policy = FlowBlockPolicy(
                        minFragmentsBeforeBreak = 1,
                        minFragmentsAfterBreak = 1,
                    ),
                    anchorsByLine = mapOf(
                        1 to listOf(FlowAnchor.FootnoteReference(footnoteId)),
                    ),
                ),
            ),
        )
        val noteLines = paragraph(
            id = "fn-long-body",
            lineCount = 3,
            lineHeight = 30f,
            policy = FlowBlockPolicy(
                minFragmentsBeforeBreak = 1,
                minFragmentsAfterBreak = 1,
            ),
        )
        val footnote = Footnote(
            id = footnoteId,
            content = lowerFlowBlocks(listOf(noteLines)),
            continuationCost = 5.0,
        )

        val plan = composeSuccessfully(
            body = body,
            pageHeight = 100f,
            footnotes = listOf(footnote),
            constraints = constraints(pageHeight = 100f, footnoteSeparatorBlockSize = 5f),
        )

        assertEquals(2, plan.pages.size)
        assertEquals(
            listOf("fn-long-body-line-1", "fn-long-body-line-2"),
            plan.pages[0].footnotes.map { it.nodeId.value },
        )
        assertEquals(listOf("fn-long-body-line-3"), plan.pages[1].footnotes.map { it.nodeId.value })
        assertTrue(plan.pages[1].footnotes.single().isContinuation)
        assertEquals(70f, plan.pages[1].footnotes.single().offset.blockStart)
    }

    @Test
    fun floatDeadlineForcesTheAnchoredPageAndPreservesAreaPreference() {
        val floatId = FloatId("figure-1")
        val body = lowerFlowBlocks(
            listOf(
                paragraph(
                    id = "p",
                    lineCount = 4,
                    lineHeight = 25f,
                    policy = FlowBlockPolicy(
                        minFragmentsBeforeBreak = 1,
                        minFragmentsAfterBreak = 1,
                    ),
                    anchorsByLine = mapOf(
                        2 to listOf(FlowAnchor.FloatReference(floatId)),
                    ),
                ),
            ),
        )
        val figure = FloatObject(
            id = floatId,
            size = LogicalSize(inlineSize = 100f, blockSize = 40f),
            allowedAreas = setOf(FloatArea.Top, FloatArea.Bottom),
            maxPageDrift = 0,
            driftCostPerPage = 10.0,
            areaCosts = mapOf(FloatArea.Top to 3.0, FloatArea.Bottom to 0.0),
        )

        val plan = composeSuccessfully(
            body = body,
            pageHeight = 100f,
            floats = listOf(figure),
        )

        assertEquals(listOf(2, 2), plan.pages.map { it.body.size })
        assertTrue(plan.pages[0].topFloats.isEmpty())
        assertEquals(floatId, plan.pages[0].bottomFloats.single().floatId)
        assertEquals(0, plan.pages[0].bottomFloats.single().pageDrift)
        assertEquals(60f, plan.pages[0].bottomFloats.single().offset.blockStart)
    }

    @Test
    fun floatMayDriftWithinItsBoundWhenTheAnchorPageHasNoRoom() {
        val floatId = FloatId("figure-drift")
        val body = lowerFlowBlocks(
            listOf(
                FlowBlock(
                    id = FlowBlockId("anchored-block"),
                    fragments = listOf(
                        FlowFragment(
                            id = FlowNodeId("anchor-box"),
                            size = LogicalSize(inlineSize = 100f, blockSize = 80f),
                            anchors = listOf(FlowAnchor.FloatReference(floatId)),
                        ),
                    ),
                    policy = FlowBlockPolicy.Atomic,
                ),
            ),
        )
        val figure = FloatObject(
            id = floatId,
            size = LogicalSize(inlineSize = 100f, blockSize = 40f),
            allowedAreas = setOf(FloatArea.Top),
            maxPageDrift = 1,
            driftCostPerPage = 2.0,
        )

        val plan = composeSuccessfully(
            body = body,
            pageHeight = 100f,
            floats = listOf(figure),
        )

        assertEquals(2, plan.pages.size)
        assertEquals(listOf("anchor-box"), plan.pages[0].body.map { it.nodeId.value })
        assertEquals(1, plan.pages[1].topFloats.single().pageDrift)
        assertEquals(floatId, plan.pages[1].topFloats.single().floatId)
    }

    @Test
    fun headingMovesAsAUnitWithTheRequestedFollowingLines() {
        val previous = atomicBlock("previous", blockHeight = 50f)
        val heading = FlowBlock(
            id = FlowBlockId("heading"),
            fragments = listOf(
                FlowFragment(
                    id = FlowNodeId("heading-fragment"),
                    size = LogicalSize(inlineSize = 100f, blockSize = 20f),
                    role = BoxRole.Heading,
                ),
            ),
            policy = FlowBlockPolicy.heading(keepWithNextFragments = 2),
        )
        val following = paragraph(
            id = "following",
            lineCount = 2,
            lineHeight = 20f,
            policy = FlowBlockPolicy(
                minFragmentsBeforeBreak = 1,
                minFragmentsAfterBreak = 1,
            ),
        )

        val plan = composeSuccessfully(
            body = lowerFlowBlocks(listOf(previous, heading, following)),
            pageHeight = 70f,
        )

        assertEquals(listOf("previous-fragment-1"), plan.pages[0].body.map { it.nodeId.value })
        assertEquals(
            listOf("heading-fragment", "following-line-1", "following-line-2"),
            plan.pages[1].body.map { it.nodeId.value },
        )
    }

    @Test
    fun reportsInvalidInsertionDefinitionsInsteadOfDroppingThem() {
        val floatId = FloatId("missing-anchor")
        val outcome = composer.compose(
            document = PageFlowDocument(
                body = lowerFlowBlocks(listOf(atomicBlock("body", blockHeight = 20f))),
                floats = listOf(
                    FloatObject(
                        id = floatId,
                        size = LogicalSize(100f, 20f),
                        allowedAreas = setOf(FloatArea.Top),
                        maxPageDrift = 0,
                        driftCostPerPage = 0.0,
                    ),
                ),
            ),
            constraints = constraints(pageHeight = 100f),
        )

        val failure = assertIs<PageFlowOutcome.Failure>(outcome)
        val invalid = assertIs<PageFlowFailure.InvalidInput>(failure.reason)
        assertTrue(invalid.issues.any { it.code == ValidationIssueCode.UnreferencedFloat })
    }

    @Test
    fun searchLimitFailsClosedInsteadOfReturningAGreedyApproximation() {
        val outcome = composer.compose(
            document = PageFlowDocument(
                body = lowerFlowBlocks(
                    listOf(
                        paragraph(
                            id = "p",
                            lineCount = 8,
                            lineHeight = 20f,
                            policy = FlowBlockPolicy(
                                minFragmentsBeforeBreak = 1,
                                minFragmentsAfterBreak = 1,
                            ),
                        ),
                    ),
                ),
            ),
            constraints = constraints(pageHeight = 60f),
            options = PageFlowOptions(
                search = PageFlowSearchPolicy(maxExpandedStates = 1),
            ),
        )

        val failure = assertIs<PageFlowOutcome.Failure>(outcome)
        assertIs<PageFlowFailure.SearchLimitExceeded>(failure.reason)
    }

    @Test
    fun choosesTheGlobalMinimumInsteadOfTheFullestImmediatePage() {
        val body = VerticalFlow(
            listOf(
                FlowNode.Box(FlowNodeId("box-1"), LogicalSize(100f, 60f)),
                FlowNode.Break(FlowNodeId("break-1")),
                FlowNode.Box(FlowNodeId("box-2"), LogicalSize(100f, 40f)),
                FlowNode.Break(FlowNodeId("break-2"), BreakRule(cost = 50.0)),
                FlowNode.Box(FlowNodeId("box-3"), LogicalSize(100f, 60f)),
            ),
        )

        val plan = composeSuccessfully(body = body, pageHeight = 100f)

        assertEquals(listOf(listOf("box-1"), listOf("box-2", "box-3")), plan.pages.map { page ->
            page.body.map { it.nodeId.value }
        })
        assertEquals(16.0, plan.totalCost, absoluteTolerance = 0.00001)
        assertEquals(16.0, plan.pages.first().decision.cost.underfill, absoluteTolerance = 0.00001)
        assertEquals(0.0, plan.pages.first().decision.cost.breakCost)
    }

    @Test
    fun equalCostPlansAreDeterministicAcrossRuns() {
        val body = lowerFlowBlocks(
            listOf(
                paragraph(
                    id = "p",
                    lineCount = 6,
                    lineHeight = 20f,
                    policy = FlowBlockPolicy(
                        minFragmentsBeforeBreak = 1,
                        minFragmentsAfterBreak = 1,
                    ),
                ),
            ),
        )

        val plans = List(20) { composeSuccessfully(body = body, pageHeight = 60f) }

        assertTrue(plans.drop(1).all { it.pages == plans.first().pages })
        assertTrue(plans.drop(1).all { it.debug == plans.first().debug })
    }

    private fun composeSuccessfully(
        body: VerticalFlow,
        pageHeight: Float,
        floats: List<FloatObject> = emptyList(),
        footnotes: List<Footnote> = emptyList(),
        constraints: PageConstraints = constraints(pageHeight),
    ): PageFlowPlan {
        val outcome = composer.compose(
            document = PageFlowDocument(body = body, floats = floats, footnotes = footnotes),
            constraints = constraints,
        )
        return assertIs<PageFlowOutcome.Success>(outcome).plan
    }
}

private fun paragraph(
    id: String,
    lineCount: Int,
    lineHeight: Float,
    policy: FlowBlockPolicy = FlowBlockPolicy(),
    anchorsByLine: Map<Int, List<FlowAnchor>> = emptyMap(),
): FlowBlock = FlowBlock(
    id = FlowBlockId(id),
    fragments = (1..lineCount).map { lineNumber ->
        FlowFragment(
            id = FlowNodeId("$id-line-$lineNumber"),
            size = LogicalSize(inlineSize = 100f, blockSize = lineHeight),
            sourceRange = SourceRange(lineNumber - 1, lineNumber),
            anchors = anchorsByLine[lineNumber].orEmpty(),
        )
    },
    policy = policy,
)

private fun atomicBlock(
    id: String,
    blockHeight: Float,
    role: BoxRole = BoxRole.AtomicBlock,
): FlowBlock = FlowBlock(
    id = FlowBlockId(id),
    fragments = listOf(
        FlowFragment(
            id = FlowNodeId("$id-fragment-1"),
            size = LogicalSize(inlineSize = 100f, blockSize = blockHeight),
            role = role,
        ),
    ),
    policy = FlowBlockPolicy.Atomic,
)

private fun constraints(
    pageHeight: Float,
    firstPageAdditionalBlockStartInset: Float = 0f,
    footnoteSeparatorBlockSize: Float = 0f,
): PageConstraints = PageConstraints(
    pageSize = LogicalSize(inlineSize = 100f, blockSize = pageHeight),
    firstPageAdditionalBlockStartInset = firstPageAdditionalBlockStartInset,
    footnoteSeparatorBlockSize = footnoteSeparatorBlockSize,
)
