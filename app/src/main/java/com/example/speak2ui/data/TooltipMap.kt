package com.example.speak2ui.data

import android.graphics.Rect
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * A data class that represents a mapping between a numerical tooltip and a UI element.
 *
 * This class is used to share information about UI elements that have been assigned a tooltip
 * between the [com.example.speak2ui.control.TooltipService] (which creates the tooltips) and the [com.example.speak2ui.control.Accessibility]
 * (which uses the tooltips to identify targets for actions).
 *
 * It is [android.os.Parcelable] to allow it to be easily passed between components via [Intent] extras.
 *
 * @property number The integer number displayed in the tooltip badge.
 * @property description A description of the UI element, typically from its `contentDescription` or text.
 * @property bounds The screen coordinates [android.graphics.Rect] of the UI element.
 */
@Parcelize
data class TooltipMap(
    var number: Int,
    var description: String,
    var bounds: Rect
) : Parcelable

data class TipCandidate(
    val bounds: Rect,
    val displayLabel: String,    // 내부 로그용(화면표시 텍스트)
    val matchKey: String,        // 매칭용 키: actionable.contentDescription
    val id: String,              // viewIdResourceName
    val cls: String,             // className
    val depth: Int               // DFS depth
)

// 레이아웃 변경 감지용 스냅샷(좌표 + matchKey)
data class TipSnapshot(
    val l: Int,
    val t: Int,
    val r: Int,
    val b: Int,
    val key: String
)