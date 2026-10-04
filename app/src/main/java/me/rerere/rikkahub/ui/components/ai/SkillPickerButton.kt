// Modified by AI Hello World on 2026-10-04.
// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.
// 输入框里的技能选择器：选中后本条消息只允许该 skill，AI 不会在多个技能里乱调。

package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Sparkles

/**
 * 技能选择器。始终显示，列出磁盘上的全部技能（助手已启用的排在前面）。
 * 若还没有任何技能，也会显示并提示如何添加。
 */
@Composable
fun SkillPickerButton(
    skills: List<String>,
    enabledSkills: Set<String>,
    pinnedSkill: String?,
    onUpdate: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val ordered = remember(skills, enabledSkills) {
        (skills.filter { it in enabledSkills } + skills.filterNot { it in enabledSkills }).distinct()
    }

    Box(modifier) {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.size(30.dp),
        ) {
            Icon(
                imageVector = HugeIcons.Sparkles,
                contentDescription = "指定技能",
                tint = if (pinnedSkill != null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("默认（不指定）") },
                onClick = {
                    expanded = false
                    onUpdate(null)
                },
            )
            if (ordered.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("暂无技能，请到 助手 > 扩展 > 技能 添加") },
                    enabled = false,
                    onClick = {},
                )
            } else {
                ordered.forEach { skill ->
                    val enabled = skill in enabledSkills
                    DropdownMenuItem(
                        text = { Text(if (enabled) skill else "$skill（未启用）") },
                        onClick = {
                            expanded = false
                            onUpdate(if (pinnedSkill == skill) null else skill)
                        },
                    )
                }
            }
        }
    }
}
