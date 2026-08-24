package com.whyy.snapnotes.ui.components

import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring

/** 共享元素键集合，用于屏幕过渡时跨屏迁移的元素。 */
object SharedTransitionKeys {
    /** 主页"选择 JSON"卡片 ↔ 全屏内置文件管理器。 */
    const val FILE_HEADER = "file-header"
    /** 编辑器"加载现有 JSON 文件"按钮 ↔ 全屏内置文件管理器（编辑器入口）。 */
    const val EDITOR_FILE_HEADER = "editor-file-header"
}

/**
 * 容器转场的 bounds 形变曲线：低 stiffness 弹簧，让"卡片放大成全屏 / 收回"
 * 的过程带明显的弹性扩展，而不是瞬间贴齐。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
val ContainerBoundsTransform: BoundsTransform = BoundsTransform { _, _ ->
    spring(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessMediumLow
    )
}
