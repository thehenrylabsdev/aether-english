/*
 * Copyright 2026 Henry Nguyen
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.thehenrylabs.aetherenglish

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

@Composable
fun AetherApp(model: AetherModel) {
    if (!model.loaded) {
        Box(Modifier.fillMaxSize().background(Paper), contentAlignment = Alignment.Center) {
            Text(model.copy.appName, style = MaterialTheme.typography.headlineMedium, color = Ink)
        }
        return
    }
    val nav = rememberNavController()
    val start = if (model.profile == null) "onboard" else "home"
    Scaffold(
        containerColor = Paper,
        bottomBar = { if (model.profile != null) AetherBar(nav) },
    ) { padding ->
        NavHost(nav, startDestination = start, modifier = Modifier.padding(padding)) {
            composable("onboard") { OnboardScreen(model) { nav.navigate("home") { popUpTo("onboard") { inclusive = true } } } }
            composable("home") { HomeScreen(model, nav) }
            composable("review") { ReviewScreen(model) }
            composable("chat") { ChatScreen(model, ConversationMode.FREE) }
            composable("read") { ReadScreen(model) }
            composable("speak") { SpeakScreen(model) }
            composable("write") { ChatScreen(model, ConversationMode.WRITING) }
            composable("progress") { ProgressScreen(model) }
            composable("library") { LibraryScreen(model) }
            composable("settings") { SettingsScreen(model) }
            composable("more") { MoreScreen(nav, model.copy) }
        }
    }
}

@Composable
private fun AetherBar(nav: NavHostController) {
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    NavigationBar(containerColor = CardWhite) {
        barItem(nav, route, "home", Icons.Filled.Home, "Home")
        barItem(nav, route, "review", Icons.Filled.MenuBook, "Review")
        barItem(nav, route, "chat", Icons.Filled.Chat, "Chat")
        barItem(nav, route, "read", Icons.Filled.Edit, "Read")
        barItem(nav, route, "more", Icons.Filled.MoreHoriz, "More")
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.barItem(
    nav: NavHostController,
    route: String?,
    target: String,
    icon: ImageVector,
    label: String,
) {
    NavigationBarItem(
        selected = route == target,
        onClick = { nav.navigate(target) },
        icon = { Icon(icon, contentDescription = label) },
        label = { Text(label) },
    )
}

@Composable
private fun Screen(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        content()
    }
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().border(1.dp, Line, cardShape()).background(CardWhite, cardShape()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
    }
}

@Composable
private fun OnboardScreen(model: AetherModel, onDone: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var language by remember { mutableStateOf("vi") }
    var minutes by remember { mutableStateOf(10f) }
    var goals by remember { mutableStateOf(setOf(LearningGoal.DAILY)) }
    var cefr by remember { mutableStateOf<CefrLevel?>(null) }
    LaunchedEffect(model.profile) {
        if (model.profile != null) onDone()
    }
    Screen {
        Text(model.copy.appName, style = MaterialTheme.typography.headlineMedium, color = Ink)
        Text(model.copy.cefrDisclaimer, color = BodyInk)
        OutlinedTextField(name, { name = it }, label = { Text(model.copy.appName) }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(language, { language = it }, label = { Text(model.copy.library) }, modifier = Modifier.fillMaxWidth())
        Text("${minutes.toInt()}", color = Ink)
        androidx.compose.material3.Slider(
            value = minutes,
            onValueChange = { minutes = it },
            valueRange = Limits.DAILY_MIN_MINUTES.toFloat()..Limits.DAILY_MAX_MINUTES.toFloat(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LearningGoal.entries.forEach { goal ->
                FilterChip(
                    selected = goal in goals,
                    onClick = { goals = if (goal in goals) goals - goal else goals + goal },
                    label = { Text(goal.name) },
                    modifier = Modifier.height(48.dp),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CefrLevel.entries.forEach { level ->
                FilterChip(
                    selected = cefr == level,
                    onClick = { cefr = level },
                    label = { Text(level.name) },
                    modifier = Modifier.height(48.dp),
                )
            }
        }
        PrimaryButton(model.copy.continueLabel, Icons.Filled.Save, {
            if (name.isNotBlank()) model.saveProfile(name, language, goals.toList(), minutes.toInt(), cefr)
        }, enabled = name.isNotBlank())
    }
}

@Composable
private fun HomeScreen(model: AetherModel, nav: NavHostController) {
    var placement by remember { mutableStateOf(CefrLevel.A2) }
    Screen {
        Text(model.copy.home, style = MaterialTheme.typography.headlineMedium, color = Ink)
        Text(model.copy.cefrDisclaimer, color = BodyInk)
        StatusRow(model)
        Panel {
            Text(model.path.minutes.toString(), color = Ink)
            model.path.steps.forEach { step ->
                Text("${step.kind.name} · ${step.title} · ${step.minutes}", color = BodyInk)
            }
        }
        Panel {
            Text(model.copy.cefrDisclaimer, color = BodyInk)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CefrLevel.entries.take(4).forEach { level ->
                    FilterChip(selected = placement == level, onClick = { placement = level }, label = { Text(level.name) })
                }
            }
            PrimaryButton(model.copy.save, Icons.Filled.Save, { model.confirmPlacement(placement, true) })
            TextButton(onClick = { model.confirmPlacement(placement, false) }, modifier = Modifier.height(48.dp)) {
                Text(model.copy.skip, color = Action)
            }
        }
        if (model.banner.isNotBlank()) Text(model.banner, color = Danger)
        PrimaryButton(model.copy.chat, Icons.Filled.Chat, { nav.navigate("chat") }, wideButton())
    }
}

@Composable
private fun StatusRow(model: AetherModel) {
    val chipIcon = when (model.chip) {
        OfflineChip.OFFLINE -> Icons.Filled.CloudOff
        OfflineChip.CLOUD -> Icons.Filled.Cloud
        OfflineChip.DOWNLOADING -> Icons.Filled.Download
        OfflineChip.NEED_MODEL -> Icons.Filled.Warning
    }
    val chipLabel = when (model.chip) {
        OfflineChip.OFFLINE -> model.copy.offline
        OfflineChip.CLOUD -> model.copy.cloud
        OfflineChip.DOWNLOADING -> model.copy.downloading
        OfflineChip.NEED_MODEL -> model.copy.needModel
    }
    val thermalLabel = when {
        !model.thermal.supported -> model.copy.thermalUnavailable
        model.thermal.band == ThermalBand.CRITICAL || model.thermal.band == ThermalBand.EMERGENCY -> model.copy.thermalStopped
        model.thermal.band == ThermalBand.MODERATE || model.thermal.band == ThermalBand.SEVERE -> model.copy.thermalShorter
        else -> model.copy.thermalNormal
    }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(chipIcon, contentDescription = chipLabel, tint = Ink)
            Text(chipLabel, color = Ink)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.Warning, contentDescription = thermalLabel, tint = if (model.thermal.band == ThermalBand.CRITICAL) Danger else Ink)
            Text(thermalLabel, color = BodyInk)
        }
    }
}

@Composable
private fun ReviewScreen(model: AetherModel) {
    Screen {
        Text(model.copy.review, style = MaterialTheme.typography.headlineMedium, color = Ink)
        if (model.due.isEmpty()) Text(model.copy.offline, color = BodyInk)
        model.due.forEach { item ->
            Panel {
                Text(item.front, color = Ink, style = MaterialTheme.typography.titleMedium)
                Text(item.back, color = BodyInk)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    grade(model, item, FsrsRating.AGAIN, model.copy.again)
                    grade(model, item, FsrsRating.HARD, model.copy.hard)
                    grade(model, item, FsrsRating.GOOD, model.copy.good)
                    grade(model, item, FsrsRating.EASY, model.copy.easy)
                }
            }
        }
    }
}

@Composable
private fun grade(model: AetherModel, item: SrsItem, rating: FsrsRating, label: String) {
    TextButton(onClick = { model.grade(item, rating) }, modifier = Modifier.height(48.dp)) {
        Text(label, color = Action)
    }
}

@Composable
private fun ChatScreen(model: AetherModel, mode: ConversationMode) {
    var draft by remember { mutableStateOf("") }
    Screen {
        Text(if (mode == ConversationMode.WRITING) model.copy.write else model.copy.chat, style = MaterialTheme.typography.headlineMedium, color = Ink)
        Text(model.copy.cefrDisclaimer, color = BodyInk)
        if (model.settings.preferCloud) Text(model.copy.cloudLeavesDevice, color = Violet)
        if (model.banner.isNotBlank()) Text(model.banner, color = Danger)
        Text(model.transcript, color = BodyInk)
        model.fixes.forEach { fix ->
            Panel {
                Text("${fix.original} => ${fix.corrected}", color = Ink)
                Text(fix.note, color = BodyInk)
                PrimaryButton(model.copy.save, Icons.Filled.Save, { model.saveFix(fix, MistakeKind.GRAMMAR) })
            }
        }
        OutlinedTextField(draft, { draft = it }, modifier = Modifier.fillMaxWidth())
        PrimaryButton(model.copy.send, Icons.Filled.Send, {
            model.transcript = ""
            model.send(draft, mode)
            draft = ""
        })
    }
}

@Composable
private fun ReadScreen(model: AetherModel) {
    var selected by remember { mutableStateOf(model.library.firstOrNull { it.kind == LibraryKind.READING }) }
    var highlight by remember { mutableStateOf("") }
    Screen {
        Text(model.copy.read, style = MaterialTheme.typography.headlineMedium, color = Ink)
        model.library.filter { it.kind == LibraryKind.READING }.forEach { item ->
            TextButton(onClick = { selected = item }, modifier = Modifier.height(48.dp)) {
                Text(item.title, color = Action)
            }
        }
        val item = selected
        if (item != null) {
            Panel {
                Text(item.title, color = Ink, style = MaterialTheme.typography.titleMedium)
                Text("${item.cefr.name} · ${item.license}", color = BodyInk)
                Text(item.body, color = BodyInk)
                Text(item.attribution, color = BodyInk)
            }
            OutlinedTextField(highlight, { highlight = it }, label = { Text(model.copy.save) }, modifier = Modifier.fillMaxWidth())
            PrimaryButton(model.copy.save, Icons.Filled.Save, {
                if (highlight.isNotBlank()) model.saveSelection(highlight, item.externalId)
            })
            PrimaryButton(model.copy.speak, Icons.Filled.Mic, { model.speak(item.body) })
            if (model.voiceNote.isNotBlank()) Text(model.voiceNote, color = Danger)
        }
    }
}

@Composable
private fun SpeakScreen(model: AetherModel) {
    var target by remember { mutableStateOf("I walk to the market.") }
    val feedback = model.feedback
    val recordPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) model.startRecording()
    }
    Screen {
        Text(model.copy.speak, style = MaterialTheme.typography.headlineMedium, color = Ink)
        Text(model.copy.pronunciationLimit, color = BodyInk)
        Text(
            if (model.runtime.speech.availability() == SpeechAvailability.ON_DEVICE) model.copy.record else model.copy.speechUnavailable,
            color = BodyInk,
        )
        OutlinedTextField(target, { target = it }, modifier = Modifier.fillMaxWidth())
        PrimaryButton(model.copy.speak, Icons.Filled.Mic, { model.speak(target) })
        if (model.recording) {
            PrimaryButton(model.copy.stop, Icons.Filled.Stop, { model.stopRecording(target) })
        } else {
            PrimaryButton(model.copy.record, Icons.Filled.Mic, {
                recordPermission.launch(Manifest.permission.RECORD_AUDIO)
            })
        }
        if (feedback != null) {
            Panel {
                Text(feedback.transcript ?: model.copy.speechUnavailable, color = Ink)
                Text("ms ${feedback.durationMillis}", color = BodyInk)
                Text("rms ${feedback.rms}", color = BodyInk)
                Text("clippingRatio ${feedback.clippingRatio}", color = BodyInk)
                feedback.notes.forEach { note -> Text("${note.code}: ${note.message}", color = BodyInk) }
            }
        }
    }
}

@Composable
private fun ProgressScreen(model: AetherModel) {
    val board = model.dashboard
    Screen {
        Text(model.copy.progress, style = MaterialTheme.typography.headlineMedium, color = Ink)
        Text(model.copy.cefrDisclaimer, color = BodyInk)
        Panel {
            Text(board?.estimate?.overall?.name ?: model.copy.skip, color = Ink, style = MaterialTheme.typography.headlineMedium)
            Text(board?.estimate?.note ?: model.copy.cefrDisclaimer, color = BodyInk)
            Text("${board?.streakDays ?: 0}", color = Ink)
            Text("${board?.learnedCards ?: 0}", color = BodyInk)
            board?.mistakesByKind?.forEach { (kind, count) ->
                Text("${kind.name} $count", color = BodyInk)
            }
        }
    }
}

@Composable
private fun LibraryScreen(model: AetherModel) {
    var outline by remember { mutableStateOf("A short original note about buying fruit.") }
    Screen {
        Text(model.copy.library, style = MaterialTheme.typography.headlineMedium, color = Ink)
        model.library.forEach { item ->
            Panel {
                Text(item.title, color = Ink)
                Text("${item.kind.name} · ${item.cefr.name} · ${item.license}", color = BodyInk)
            }
        }
        OutlinedTextField(outline, { outline = it }, modifier = Modifier.fillMaxWidth())
        PrimaryButton(model.copy.draft, Icons.Filled.Edit, { model.draft(outline, CefrLevel.A2) })
        if (model.banner.isNotBlank()) Text(model.banner, color = Danger)
    }
}

@Composable
private fun SettingsScreen(model: AetherModel) {
    val current = model.settings
    var cloudModel by remember(current.cloudModelId) { mutableStateOf(current.cloudModelId) }
    var key by remember { mutableStateOf("") }
    var hfToken by remember { mutableStateOf("") }
    Screen {
        Text(model.copy.settings, style = MaterialTheme.typography.headlineMedium, color = Ink)
        Text(model.copy.downloadUnavailable, color = BodyInk)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("CPU", color = Ink, modifier = Modifier.weight(1f))
            Switch(checked = current.backend == LocalBackend.GPU, onCheckedChange = {
                model.updateSettings(current.copy(backend = if (it) LocalBackend.GPU else LocalBackend.CPU))
            })
            Text("GPU", color = Ink)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(model.copy.cloud, color = Ink, modifier = Modifier.weight(1f))
            Switch(checked = current.preferCloud, onCheckedChange = {
                model.updateSettings(current.copy(preferCloud = it))
            })
        }
        if (current.preferCloud) Text(model.copy.cloudLeavesDevice, color = Violet)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Gemini", color = Ink, modifier = Modifier.weight(1f))
            Switch(checked = current.vendor == CloudVendor.OPENAI_COMPATIBLE, onCheckedChange = {
                val vendor = if (it) CloudVendor.OPENAI_COMPATIBLE else CloudVendor.GEMINI
                model.updateSettings(current.copy(vendor = vendor))
            })
            Text("OpenAI", color = Ink)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(model.copy.cloud, color = Ink, modifier = Modifier.weight(1f))
            Switch(checked = current.sendPersonalContext, onCheckedChange = {
                model.updateSettings(current.copy(sendPersonalContext = it))
            })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("MTP", color = Ink, modifier = Modifier.weight(1f))
            Switch(checked = current.speculative, onCheckedChange = {
                model.updateSettings(current.copy(speculative = it))
            })
        }
        OutlinedTextField(cloudModel, { cloudModel = it }, label = { Text(model.copy.cloud) }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(key, { key = it }, label = { Text(model.copy.keyMissing) }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            hfToken,
            { hfToken = it },
            label = { Text(model.copy.huggingfaceToken) },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        PrimaryButton(model.copy.save, Icons.Filled.Save, {
            model.updateSettings(current.copy(cloudModelId = cloudModel))
            if (key.isNotEmpty()) {
                model.saveKey(key)
                key = ""
            }
            if (hfToken.isNotEmpty()) {
                model.saveHuggingFaceToken(hfToken)
                hfToken = ""
            }
        })
        ModelCatalog.specs.forEach { spec ->
            val row = model.downloads[spec.id]
            val idle = if (spec.role == ModelRole.CHAT) model.copy.downloadChat else model.copy.downloadEmbed
            val label = if (row?.running == true) model.copy.downloading else idle
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PrimaryButton(
                    label,
                    Icons.Filled.Download,
                    { model.downloadModel(spec) },
                    wideButton(),
                    enabled = ModelCatalog.downloadAllowed(spec) && row?.running != true,
                )
                if (row?.running == true) Text("${row.bytes} / ${row.total}", color = BodyInk)
                val message = when {
                    row?.error == "huggingface-token" || spec.gated -> model.copy.huggingfaceTokenHint
                    row?.error != null -> model.copy.downloadFailed
                    else -> ""
                }
                if (message.isNotEmpty()) Text(message, color = Danger)
            }
        }
        if (model.keySaved) Text(model.copy.save, color = Ok)
        if (model.huggingfaceSaved) Text(model.copy.huggingfaceToken, color = Ok)
    }
}

@Composable
private fun MoreScreen(nav: NavHostController, copy: Copy) {
    Screen {
        Text(copy.more, style = MaterialTheme.typography.headlineMedium, color = Ink)
        PrimaryButton(copy.speak, Icons.Filled.Mic, { nav.navigate("speak") }, wideButton())
        PrimaryButton(copy.write, Icons.Filled.Edit, { nav.navigate("write") }, wideButton())
        PrimaryButton(copy.progress, Icons.Filled.Insights, { nav.navigate("progress") }, wideButton())
        PrimaryButton(copy.library, Icons.Filled.MenuBook, { nav.navigate("library") }, wideButton())
        PrimaryButton(copy.settings, Icons.Filled.Settings, { nav.navigate("settings") }, wideButton())
    }
}

private fun DeviceSettings.copy(
    backend: LocalBackend = this.backend,
    preferCloud: Boolean = this.preferCloud,
    vendor: CloudVendor = this.vendor,
    cloudModelId: String = this.cloudModelId,
    sendPersonalContext: Boolean = this.sendPersonalContext,
    speculative: Boolean = this.speculative,
    chatPath: String = this.chatPath,
    embedPath: String = this.embedPath,
    gpuFailed: Boolean = this.gpuFailed,
): DeviceSettings = DeviceSettings().also {
    it.backend = backend
    it.preferCloud = preferCloud
    it.vendor = vendor
    it.cloudModelId = cloudModelId
    it.sendPersonalContext = sendPersonalContext
    it.speculative = speculative
    it.chatPath = chatPath
    it.embedPath = embedPath
    it.gpuFailed = gpuFailed
}
