package org.tiqian.pageflow.internal

import org.tiqian.pageflow.FloatId
import org.tiqian.pageflow.FloatObject
import org.tiqian.pageflow.FlowAnchor
import org.tiqian.pageflow.FlowNode
import org.tiqian.pageflow.FlowNodeId
import org.tiqian.pageflow.Footnote
import org.tiqian.pageflow.FootnoteId
import org.tiqian.pageflow.PageConstraints
import org.tiqian.pageflow.PageFlowDocument
import org.tiqian.pageflow.ValidationIssue
import org.tiqian.pageflow.ValidationIssueCode
import org.tiqian.pageflow.VerticalFlow

internal data class ValidatedDocument(
    val source: PageFlowDocument,
    val floatsById: Map<FloatId, FloatObject>,
    val footnotesById: Map<FootnoteId, Footnote>,
    val firstFloatAnchorNodeIndex: Map<FloatId, Int>,
    val firstFootnoteAnchorNodeIndex: Map<FootnoteId, Int>,
)

internal sealed interface ValidationResult {
    data class Valid(val document: ValidatedDocument) : ValidationResult

    data class Invalid(val issues: List<ValidationIssue>) : ValidationResult
}

internal fun validateDocument(
    document: PageFlowDocument,
    constraints: PageConstraints,
): ValidationResult {
    val issues = mutableListOf<ValidationIssue>()
    val floatsById = uniqueDefinitions(
        definitions = document.floats,
        id = { it.id },
        duplicateCode = ValidationIssueCode.DuplicateFloatId,
        label = "float",
        issues = issues,
    )
    val footnotesById = uniqueDefinitions(
        definitions = document.footnotes,
        id = { it.id },
        duplicateCode = ValidationIssueCode.DuplicateFootnoteId,
        label = "footnote",
        issues = issues,
    )

    val allNodeIds = mutableSetOf<FlowNodeId>()
    validateFlowNodes(
        flow = document.body,
        flowName = "body",
        allowAnchors = true,
        contentInlineSize = constraints.contentInlineSize(),
        contentBlockSize = constraints.contentBlockSize(pageIndex = 1),
        allNodeIds = allNodeIds,
        issues = issues,
    )
    document.footnotes.forEach { footnote ->
        if (footnote.content.nodes.none { it is FlowNode.Box }) {
            issues += ValidationIssue(
                code = ValidationIssueCode.FootnoteHasNoBoxes,
                subject = "footnote:${footnote.id.value}",
                detail = "Footnote content must contain at least one box.",
            )
        }
        validateFlowNodes(
            flow = footnote.content,
            flowName = "footnote:${footnote.id.value}",
            allowAnchors = false,
            contentInlineSize = constraints.contentInlineSize(),
            contentBlockSize = constraints.contentBlockSize(pageIndex = 1),
            allNodeIds = allNodeIds,
            issues = issues,
        )
    }

    document.floats.forEach { float ->
        if (float.size.inlineSize > constraints.contentInlineSize()) {
            issues += ValidationIssue(
                code = ValidationIssueCode.InlineSizeExceedsPage,
                subject = "float:${float.id.value}",
                detail = "${float.size.inlineSize} exceeds ${constraints.contentInlineSize()}",
            )
        }
        if (float.size.blockSize > constraints.contentBlockSize(pageIndex = 1)) {
            issues += ValidationIssue(
                code = ValidationIssueCode.BlockSizeExceedsPage,
                subject = "float:${float.id.value}",
                detail = "${float.size.blockSize} exceeds ${constraints.contentBlockSize(pageIndex = 1)}",
            )
        }
    }

    val firstFloatAnchorNodeIndex = linkedMapOf<FloatId, Int>()
    val firstFootnoteAnchorNodeIndex = linkedMapOf<FootnoteId, Int>()
    document.body.nodes.forEachIndexed { nodeIndex, node ->
        if (node !is FlowNode.Box) return@forEachIndexed
        node.anchors.forEach { anchor ->
            when (anchor) {
                is FlowAnchor.FloatReference -> {
                    if (anchor.floatId !in floatsById) {
                        issues += ValidationIssue(
                            code = ValidationIssueCode.MissingFloatDefinition,
                            subject = node.id.value,
                            detail = "Missing float ${anchor.floatId.value}",
                        )
                    } else {
                        if (anchor.floatId !in firstFloatAnchorNodeIndex) {
                            firstFloatAnchorNodeIndex[anchor.floatId] = nodeIndex
                        }
                    }
                }

                is FlowAnchor.FootnoteReference -> {
                    if (anchor.footnoteId !in footnotesById) {
                        issues += ValidationIssue(
                            code = ValidationIssueCode.MissingFootnoteDefinition,
                            subject = node.id.value,
                            detail = "Missing footnote ${anchor.footnoteId.value}",
                        )
                    } else {
                        if (anchor.footnoteId !in firstFootnoteAnchorNodeIndex) {
                            firstFootnoteAnchorNodeIndex[anchor.footnoteId] = nodeIndex
                        }
                    }
                }
            }
        }
    }

    floatsById.keys.filterNot { it in firstFloatAnchorNodeIndex }.forEach { floatId ->
        issues += ValidationIssue(
            code = ValidationIssueCode.UnreferencedFloat,
            subject = "float:${floatId.value}",
            detail = "Float has no body anchor.",
        )
    }
    footnotesById.keys.filterNot { it in firstFootnoteAnchorNodeIndex }.forEach { footnoteId ->
        issues += ValidationIssue(
            code = ValidationIssueCode.UnreferencedFootnote,
            subject = "footnote:${footnoteId.value}",
            detail = "Footnote has no body reference.",
        )
    }

    return if (issues.isEmpty()) {
        ValidationResult.Valid(
            ValidatedDocument(
                source = document,
                floatsById = floatsById,
                footnotesById = footnotesById,
                firstFloatAnchorNodeIndex = firstFloatAnchorNodeIndex,
                firstFootnoteAnchorNodeIndex = firstFootnoteAnchorNodeIndex,
            ),
        )
    } else {
        ValidationResult.Invalid(issues)
    }
}

private fun validateFlowNodes(
    flow: VerticalFlow,
    flowName: String,
    allowAnchors: Boolean,
    contentInlineSize: Float,
    contentBlockSize: Float,
    allNodeIds: MutableSet<FlowNodeId>,
    issues: MutableList<ValidationIssue>,
) {
    flow.nodes.forEach { node ->
        if (!allNodeIds.add(node.id)) {
            issues += ValidationIssue(
                code = ValidationIssueCode.DuplicateNodeId,
                subject = node.id.value,
                detail = "Node id is duplicated across the document.",
            )
        }
        if (node is FlowNode.Box) {
            if (!allowAnchors && node.anchors.isNotEmpty()) {
                issues += ValidationIssue(
                    code = ValidationIssueCode.NestedInsertionInFootnote,
                    subject = node.id.value,
                    detail = "$flowName contains a nested float or footnote anchor.",
                )
            }
            if (node.size.inlineSize > contentInlineSize) {
                issues += ValidationIssue(
                    code = ValidationIssueCode.InlineSizeExceedsPage,
                    subject = node.id.value,
                    detail = "${node.size.inlineSize} exceeds $contentInlineSize",
                )
            }
            if (node.size.blockSize > contentBlockSize) {
                issues += ValidationIssue(
                    code = ValidationIssueCode.BlockSizeExceedsPage,
                    subject = node.id.value,
                    detail = "${node.size.blockSize} exceeds $contentBlockSize",
                )
            }
        }
    }
}

private fun <Id, Definition> uniqueDefinitions(
    definitions: List<Definition>,
    id: (Definition) -> Id,
    duplicateCode: ValidationIssueCode,
    label: String,
    issues: MutableList<ValidationIssue>,
): Map<Id, Definition> {
    val result = linkedMapOf<Id, Definition>()
    definitions.forEach { definition ->
        val definitionId = id(definition)
        if (result.put(definitionId, definition) != null) {
            issues += ValidationIssue(
                code = duplicateCode,
                subject = "$label:$definitionId",
                detail = "$label id is duplicated.",
            )
        }
    }
    return result
}
