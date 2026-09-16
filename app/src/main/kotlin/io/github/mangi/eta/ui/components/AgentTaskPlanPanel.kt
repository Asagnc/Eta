package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.model.AgentTaskPlanItemUi
import io.github.mangi.eta.ui.model.AgentTaskPlanStatus
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * 会话顶部的任务进度面板，点击整块展开或折叠。
 *
 * 只展示 task_plan 工具提交的清单，不提供手动勾选：清单由模型按完整快照覆盖更新，
 * 界面侧勾上的项会在下一次快照里被改回去，反而让人误以为进度已变。
 */
@Composable
internal fun AgentTaskPlanPanel(
    items: List<AgentTaskPlanItemUi>,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(true) }
    val completed = items.count { it.status == AgentTaskPlanStatus.COMPLETED }
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        insideMargin = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        colors = CardDefaults.defaultColors(
            color = MiuixTheme.colorScheme.surfaceContainer,
            contentColor = MiuixTheme.colorScheme.onSurfaceContainer,
        ),
        pressFeedbackType = PressFeedbackType.Sink,
        showIndication = true,
        onClick = { expanded = !expanded },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "任务进度 $completed/${items.size}",
                style = MiuixTheme.textStyles.body2,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurfaceContainer,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (expanded) "收起" else "展开",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        if (expanded) {
            Spacer(modifier = Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items.forEach { item -> AgentTaskPlanRow(item) }
            }
        }
    }
}

@Composable
private fun AgentTaskPlanRow(item: AgentTaskPlanItemUi) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (item.status) {
            AgentTaskPlanStatus.COMPLETED -> Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )

            AgentTaskPlanStatus.IN_PROGRESS -> Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MiuixTheme.colorScheme.primary,
            )

            // 运行中止时仍留在进行中的项：不带图标，文字行保留原文，只是不再显示「进行中」。
            AgentTaskPlanStatus.INTERRUPTED -> Spacer(modifier = Modifier.size(14.dp))

            // 未开始的一项不配图标，留出同宽空位保持各行文字对齐。
            AgentTaskPlanStatus.PENDING -> Spacer(modifier = Modifier.size(14.dp))
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = item.content,
            style = MiuixTheme.textStyles.footnote1,
            color = if (item.status == AgentTaskPlanStatus.IN_PROGRESS) {
                MiuixTheme.colorScheme.onSurfaceContainer
            } else {
                MiuixTheme.colorScheme.onSurfaceVariantSummary
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
