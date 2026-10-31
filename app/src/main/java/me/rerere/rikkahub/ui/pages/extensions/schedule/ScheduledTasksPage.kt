package me.rerere.rikkahub.ui.pages.extensions.schedule

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.data.model.ScheduledTask
import me.rerere.rikkahub.data.model.ScheduledTaskTrigger
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun ScheduledTasksPage() {
    val vm: ScheduledTasksVM = koinViewModel()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showAdd by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<ScheduledTask?>(null) }

    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("主动任务") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("主动任务") },
                ) {
                    item(
                        headlineContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("按时间或手机事件触发，自动用当前助手在新会话里执行提示词。")
                                Button(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth()) {
                                    Text("新建任务")
                                }
                            }
                        },
                    )
                    if (tasks.isEmpty()) {
                        item(
                            headlineContent = { Text("暂无任务") },
                            supportingContent = { Text("例如：每天 9 点总结昨天的未读通知") },
                        )
                    }
                    tasks.forEach { task ->
                        item(
                            headlineContent = {
                                Text(task.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = {
                                Text(
                                    buildString {
                                        append(
                                            when (task.trigger) {
                                                ScheduledTaskTrigger.TIME ->
                                                    "每天 %02d:%02d".format(task.hour, task.minute)

                                                ScheduledTaskTrigger.CHARGING -> "充电时"
                                                ScheduledTaskTrigger.BATTERY_LOW -> "低电量时"
                                            }
                                        )
                                        append(" · ")
                                        append(task.prompt.take(40))
                                    },
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = { vm.runNow(task) }) { Text("立即") }
                                    Switch(
                                        checked = task.enabled,
                                        onCheckedChange = { vm.toggleTask(task.id, it) },
                                    )
                                }
                            },
                            onClick = { deleteTarget = task },
                        )
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddScheduledTaskDialog(
            onDismiss = { showAdd = false },
            onConfirm = { name, prompt, trigger, hour, minute ->
                vm.addTask(name, prompt, trigger, hour, minute)
                showAdd = false
            },
        )
    }

    deleteTarget?.let { task ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除任务") },
            text = { Text("确定删除「${task.name}」？") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteTask(task.id)
                    deleteTarget = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun AddScheduledTaskDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, prompt: String, trigger: ScheduledTaskTrigger, hour: Int, minute: Int) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var trigger by remember { mutableStateOf(ScheduledTaskTrigger.TIME) }
    var hour by remember { mutableStateOf("9") }
    var minute by remember { mutableStateOf("0") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建主动任务") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("名称") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("提示词") },
                    minLines = 3,
                    maxLines = 8,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = trigger == ScheduledTaskTrigger.TIME,
                        onClick = { trigger = ScheduledTaskTrigger.TIME },
                        label = { Text("定时") },
                    )
                    FilterChip(
                        selected = trigger == ScheduledTaskTrigger.CHARGING,
                        onClick = { trigger = ScheduledTaskTrigger.CHARGING },
                        label = { Text("充电时") },
                    )
                    FilterChip(
                        selected = trigger == ScheduledTaskTrigger.BATTERY_LOW,
                        onClick = { trigger = ScheduledTaskTrigger.BATTERY_LOW },
                        label = { Text("低电量") },
                    )
                }
                if (trigger == ScheduledTaskTrigger.TIME) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = hour,
                            onValueChange = { hour = it.filter(Char::isDigit).take(2) },
                            modifier = Modifier.weight(1f),
                            label = { Text("小时") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = minute,
                            onValueChange = { minute = it.filter(Char::isDigit).take(2) },
                            modifier = Modifier.weight(1f),
                            label = { Text("分钟") },
                            singleLine = true,
                        )
                    }
                    Text(
                        text = "按当前时区每天执行",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        name,
                        prompt,
                        trigger,
                        hour.toIntOrNull() ?: 9,
                        minute.toIntOrNull() ?: 0,
                    )
                },
                enabled = prompt.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
