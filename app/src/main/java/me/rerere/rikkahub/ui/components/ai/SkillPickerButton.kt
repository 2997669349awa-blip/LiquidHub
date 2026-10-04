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

@Composable
fun SkillPickerButton(
    enabledSkills: List<String>,
    pinnedSkill: String?,
    onUpdate: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (enabledSkills.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
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
            enabledSkills.forEach { skill ->
                DropdownMenuItem(
                    text = { Text(skill) },
                    onClick = {
                        expanded = false
                        onUpdate(if (pinnedSkill == skill) null else skill)
                    },
                )
            }
        }
    }
}
