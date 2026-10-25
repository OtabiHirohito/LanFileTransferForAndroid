package com.lanfiletransfer.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.lanfiletransfer.MainViewModel
import com.lanfiletransfer.R
import com.lanfiletransfer.TransferEngine
import com.lanfiletransfer.UiMode
import com.lanfiletransfer.storage.ReceiveDestinations
import com.lanfiletransfer.util.formatBytes

/** Windows 版 (Theme.xaml) のデザインに合わせたメイン画面。 */
@Composable
fun MainScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val engine by vm.engine.collectAsState()
    val logs by vm.log.collectAsState()

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) vm.addFiles(uris) }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) vm.setSaveFolder(uri) }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    fun show(message: String?) {
        if (!message.isNullOrEmpty()) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Android 9 以前だけ、ダウンロード フォルダに保存するための権限を求める。
    val storagePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(
                context,
                "保存先の権限がないため、アプリ専用フォルダに保存します。",
                Toast.LENGTH_LONG
            ).show()
        }
        vm.refreshSaveLocationLabel()
        ensureNotificationPermission()
        show(vm.startReceive())
    }

    fun startReceive() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            !ReceiveDestinations.hasPublicDownloadsAccess(context)
        ) {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            ensureNotificationPermission()
            show(vm.startReceive())
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    listOf(LftColors.backgroundTop, LftColors.backgroundBottom)
                )
            )
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            HeaderBar()

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                ModeSelector(
                    mode = vm.mode,
                    enabled = !engine.isRunning,
                    onSelect = vm::selectMode
                )

                when (vm.mode) {
                    UiMode.SEND -> SendCard(
                        vm = vm,
                        running = engine.mode == TransferEngine.Mode.SENDING,
                        onPickFiles = { filePicker.launch(arrayOf("*/*")) },
                        onStart = {
                            ensureNotificationPermission()
                            show(vm.startSend())
                        },
                        onStop = vm::stop
                    )

                    UiMode.RECEIVE -> {
                        ReceiveInfoCard(
                            vm = vm,
                            enabled = engine.mode != TransferEngine.Mode.LISTENING
                        )
                        ReceiveCard(
                            vm = vm,
                            listening = engine.mode == TransferEngine.Mode.LISTENING,
                            onPickFolder = { folderPicker.launch(null) },
                            onStart = { startReceive() },
                            onStop = vm::stop
                        )
                    }
                }

                ProgressCard(engine)
                LogCard(logs = logs, onClear = vm::clearLog)

                Text(
                    "Android ⇄ Android / Android ⇄ Windows に対応（LFT2 / AES-256-GCM）",
                    style = MaterialTheme.typography.labelSmall,
                    color = LftColors.textMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
        }
    }
}

@Composable
private fun HeaderBar() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.linearGradient(listOf(LftColors.headerStart, LftColors.headerEnd)))
            .statusBarsPadding()
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.20f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_swap_vert),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(Modifier.width(12.dp))

            Column {
                Text(
                    "LAN File Transfer",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.2.sp
                )
                Text(
                    "かんたんLANファイル転送",
                    color = Color(0xFFE0E7FF),
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun ModeSelector(mode: UiMode, enabled: Boolean, onSelect: (UiMode) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(LftColors.accentSoft)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        ModeSegment(
            label = "送信する",
            selected = mode == UiMode.SEND,
            enabled = enabled,
            modifier = Modifier.weight(1f),
            onClick = { onSelect(UiMode.SEND) }
        )
        ModeSegment(
            label = "受信する",
            selected = mode == UiMode.RECEIVE,
            enabled = enabled,
            modifier = Modifier.weight(1f),
            onClick = { onSelect(UiMode.RECEIVE) }
        )
    }
}

@Composable
private fun ModeSegment(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(9.dp)
    Box(
        modifier = modifier
            .height(40.dp)
            .clip(shape)
            .background(
                if (selected) {
                    Brush.linearGradient(listOf(LftColors.accent, LftColors.gradientEnd))
                } else {
                    Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
                }
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = when {
                selected && !enabled -> Color.White.copy(alpha = 0.75f)
                selected -> Color.White
                !enabled -> LftColors.textMuted
                else -> LftColors.textSecondary
            },
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun LftCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 10.dp,
                shape = shape,
                ambientColor = Color(0x337C8DB5),
                spotColor = Color(0x337C8DB5)
            )
            .clip(shape)
            .background(LftColors.card)
            .border(1.dp, LftColors.cardBorder, shape)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}

@Composable
private fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(14.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(LftColors.accent)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = LftColors.textMuted,
            letterSpacing = 0.4.sp
        )
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
private fun LftDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(LftColors.divider)
    )
}

@Composable
private fun LftTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(9.dp)
    val borderColor = when {
        !enabled -> LftColors.inputBorder.copy(alpha = 0.6f)
        focused -> LftColors.accent
        else -> LftColors.inputBorder
    }

    Column(modifier = modifier) {
        Text(
            label,
            fontSize = 13.sp,
            color = if (enabled) LftColors.textSecondary else LftColors.textMuted
        )
        Spacer(Modifier.height(6.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            textStyle = TextStyle(
                fontSize = 14.sp,
                color = if (enabled) LftColors.textPrimary else LftColors.textMuted
            ),
            cursorBrush = SolidColor(LftColors.accent),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            visualTransformation = if (isPassword) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            interactionSource = interaction,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(shape)
                .background(if (enabled) Color.White else Color(0xFFF4F5F9))
                .border(if (focused) 1.5.dp else 1.dp, borderColor, shape)
                .padding(horizontal = 12.dp),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (value.isEmpty() && placeholder != null) {
                        Text(
                            placeholder,
                            fontSize = 14.sp,
                            color = LftColors.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    innerTextField()
                }
            }
        )
    }
}

@Composable
private fun LftCheckbox(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = onCheckedChange
            )
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(if (checked) LftColors.accent else Color.White)
                .border(
                    width = 1.5.dp,
                    color = if (checked) LftColors.accent else LftColors.inputBorder,
                    shape = RoundedCornerShape(5.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (checked) {
                Canvas(Modifier.size(11.dp)) {
                    val check = Path().apply {
                        moveTo(0f, size.height * 0.52f)
                        lineTo(size.width * 0.36f, size.height * 0.88f)
                        lineTo(size.width, size.height * 0.15f)
                    }
                    drawPath(
                        path = check,
                        color = Color.White,
                        style = Stroke(
                            width = 1.8.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }
            }
        }

        Spacer(Modifier.width(9.dp))

        Text(
            label,
            fontSize = 13.sp,
            color = if (enabled) LftColors.textPrimary else LftColors.textMuted
        )
    }
}

@Composable
private fun PrimaryButton(
    text: String,
    danger: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(10.dp)
    val glow = if (danger) LftColors.danger else LftColors.accent
    val brush = if (danger) {
        Brush.linearGradient(listOf(LftColors.dangerStart, LftColors.dangerEnd))
    } else {
        Brush.linearGradient(listOf(LftColors.accent, LftColors.gradientEnd))
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(46.dp)
            .shadow(
                elevation = 8.dp,
                shape = shape,
                ambientColor = glow.copy(alpha = 0.30f),
                spotColor = glow.copy(alpha = 0.30f)
            )
            .clip(shape)
            .background(brush)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun SecondaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (enabled) Color.White else Color(0xFFF4F5F9))
            .border(
                width = 1.dp,
                color = if (enabled) LftColors.inputBorder else LftColors.inputBorder.copy(alpha = 0.6f),
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (enabled) Color(0xFF334155) else LftColors.textMuted,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun GhostButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (enabled) LftColors.accent else LftColors.textMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun SendCard(
    vm: MainViewModel,
    running: Boolean,
    onPickFiles: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    LftCard {
        SectionTitle("送信設定")

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            LftTextField(
                label = "相手の IP アドレス",
                value = vm.host,
                onValueChange = { vm.host = it },
                enabled = !running,
                placeholder = "192.168.1.10",
                keyboardType = KeyboardType.Uri,
                modifier = Modifier.weight(1f)
            )
            LftTextField(
                label = "ポート",
                value = vm.portText,
                onValueChange = { vm.portText = it.filter(Char::isDigit).take(5) },
                enabled = !running,
                keyboardType = KeyboardType.Number,
                modifier = Modifier.width(96.dp)
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            SecondaryButton(
                text = "ファイルを選択…",
                enabled = !running,
                onClick = onPickFiles
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${vm.files.size} 件",
                fontSize = 12.sp,
                color = LftColors.textSecondary
            )
        }

        if (vm.files.isNotEmpty()) {
            LftDivider()
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 176.dp)
            ) {
                items(vm.files, key = { it.uri.toString() }) { file ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(LftColors.accentSoft),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_file),
                                contentDescription = null,
                                tint = Color.Unspecified,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        Spacer(Modifier.width(10.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                file.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 13.sp,
                                color = LftColors.textPrimary
                            )
                            Text(
                                if (file.size >= 0) formatBytes(file.size) else "サイズ不明",
                                fontSize = 11.sp,
                                color = LftColors.textMuted
                            )
                        }

                        GhostButton(
                            text = "削除",
                            enabled = !running,
                            onClick = { vm.removeFile(file.uri) }
                        )
                    }
                }
            }
        }

        PasswordSection(
            enabled = !running,
            checked = vm.passwordEnabled,
            onCheckedChange = { vm.passwordEnabled = it },
            password = vm.password,
            onPasswordChange = { vm.password = it }
        )

        PrimaryButton(
            text = if (running) "送信停止" else "送信開始",
            danger = running,
            onClick = if (running) onStop else onStart
        )

        Text(
            "送信先の端末で「受信待機を開始」してから送信してください。",
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = LftColors.textMuted
        )
    }
}

@Composable
private fun ReceiveInfoCard(vm: MainViewModel, enabled: Boolean) {
    val context = LocalContext.current

    LftCard {
        SectionTitle(
            text = "この端末の情報",
            trailing = {
                GhostButton(
                    text = "再取得",
                    enabled = enabled,
                    onClick = vm::refreshAddresses
                )
            }
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "端末名",
                fontSize = 13.sp,
                color = LftColors.textSecondary,
                modifier = Modifier.width(96.dp)
            )
            Text(
                Build.MODEL ?: "-",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = LftColors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        LftDivider()

        Text(
            "IPv4 アドレス",
            fontSize = 13.sp,
            color = LftColors.textSecondary
        )

        if (vm.addresses.isEmpty()) {
            Text(
                "IP アドレスを取得できませんでした。Wi-Fi 接続を確認してください。",
                fontSize = 12.sp,
                color = LftColors.danger
            )
        } else {
            vm.addresses.forEachIndexed { index, address ->
                if (index > 0) {
                    LftDivider()
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            address.interfaceName +
                                if (address.isWireless) "（Wi-Fi）" else "",
                            fontSize = 11.sp,
                            color = LftColors.textMuted
                        )
                        Text(
                            address.ipv4,
                            fontSize = 17.sp,
                            fontFamily = FontFamily.Monospace,
                            color = LftColors.textPrimary,
                            letterSpacing = 0.3.sp
                        )
                    }
                    GhostButton(
                        text = "コピー",
                        onClick = {
                            val clipboard =
                                context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            clipboard?.setPrimaryClip(
                                ClipData.newPlainText("IPv4", address.ipv4)
                            )
                            Toast.makeText(context, "コピーしました", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ReceiveCard(
    vm: MainViewModel,
    listening: Boolean,
    onPickFolder: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val context = LocalContext.current

    LftCard {
        SectionTitle("受信設定")

        Column {
            Text(
                "保存先",
                fontSize = 13.sp,
                color = LftColors.textSecondary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                vm.saveLocationLabel,
                fontSize = 13.sp,
                color = LftColors.textPrimary,
                lineHeight = 18.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                GhostButton(
                    text = "フォルダを変更",
                    enabled = !listening,
                    onClick = onPickFolder
                )
                if (vm.saveTreeUri != null) {
                    GhostButton(
                        text = "既定に戻す",
                        enabled = !listening,
                        onClick = vm::clearSaveFolder
                    )
                }
            }
            if (vm.saveTreeUri == null &&
                ReceiveDestinations.hasPublicDownloadsAccess(context)
            ) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "受け取ったファイルは端末の「ダウンロード」から開けます。",
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = LftColors.textMuted
                )
            }
        }

        LftTextField(
            label = "待受ポート",
            value = vm.portText,
            onValueChange = { vm.portText = it.filter(Char::isDigit).take(5) },
            enabled = !listening,
            keyboardType = KeyboardType.Number
        )

        PasswordSection(
            enabled = !listening,
            checked = vm.passwordEnabled,
            onCheckedChange = { vm.passwordEnabled = it },
            password = vm.password,
            onPasswordChange = { vm.password = it }
        )

        PrimaryButton(
            text = if (listening) "受信待機を停止" else "受信待機を開始",
            danger = listening,
            onClick = if (listening) onStop else onStart
        )

        Text(
            "送信側の端末で、上の IP アドレスとポートを指定してください。画面を閉じても受信は続きます。",
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = LftColors.textMuted
        )
    }
}

@Composable
private fun PasswordSection(
    enabled: Boolean,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit
) {
    Column {
        LftDivider()
        LftCheckbox(
            label = "合言葉で暗号化する",
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange
        )
        AnimatedVisibility(
            visible = checked,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Box(modifier = Modifier.padding(top = 10.dp)) {
                LftTextField(
                    label = "合言葉（送信側と同じもの）",
                    value = password,
                    onValueChange = onPasswordChange,
                    enabled = enabled,
                    isPassword = true
                )
            }
        }
    }
}

@Composable
private fun ProgressCard(engine: TransferEngine.State) {
    LftCard {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("転送状況", modifier = Modifier.weight(1f))
            StatusBadge(engine)
        }

        LftProgressBar(
            fraction = engine.fraction,
            indeterminate = engine.mode == TransferEngine.Mode.LISTENING && engine.fileName == null
        )

        Text(
            describeProgress(engine),
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = LftColors.textSecondary
        )
    }
}

@Composable
private fun LftProgressBar(fraction: Float, indeterminate: Boolean) {
    val shape = RoundedCornerShape(7.dp)
    val brush = Brush.horizontalGradient(
        listOf(LftColors.progressStart, LftColors.progressMid, LftColors.progressEnd)
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(14.dp)
            .clip(shape)
            .background(LftColors.track)
    ) {
        if (indeterminate) {
            val transition = rememberInfiniteTransition(label = "indeterminate")
            val position by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 1500, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "position"
            )

            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .offset(x = maxWidth * (position * 1.4f - 0.4f))
                        .width(maxWidth * 0.4f)
                        .fillMaxHeight()
                        .background(brush)
                )
            }
        } else {
            val animatedFraction by animateFloatAsState(
                targetValue = fraction.coerceIn(0f, 1f),
                animationSpec = tween(durationMillis = 220),
                label = "fraction"
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedFraction)
                    .fillMaxHeight()
                    .background(brush)
            )
        }
    }
}

private data class BadgeSpec(
    val label: String,
    val background: Color,
    val dot: Color,
    val foreground: Color
)

@Composable
private fun StatusBadge(engine: TransferEngine.State) {
    val spec = when {
        engine.mode == TransferEngine.Mode.LISTENING && engine.fileName != null ->
            BadgeSpec(
                label = "受信中",
                background = Color(0xFFE0F2FE),
                dot = Color(0xFF0EA5E9),
                foreground = Color(0xFF0284C7)
            )

        engine.mode == TransferEngine.Mode.LISTENING ->
            BadgeSpec(
                label = "受信待機中",
                background = Color(0xFFECFDF5),
                dot = LftColors.success,
                foreground = Color(0xFF059669)
            )

        engine.mode == TransferEngine.Mode.SENDING ->
            BadgeSpec(
                label = "送信中",
                background = LftColors.accentSoft,
                dot = LftColors.accent,
                foreground = LftColors.accentDeep
            )

        else ->
            BadgeSpec(
                label = "待機中",
                background = Color(0xFFF1F5F9),
                dot = LftColors.textMuted,
                foreground = LftColors.textSecondary
            )
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(spec.background)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(spec.dot, CircleShape)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            spec.label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = spec.foreground
        )
    }
}

@Composable
private fun LogCard(logs: List<String>, onClear: () -> Unit) {
    LftCard {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("ログ", modifier = Modifier.weight(1f))
            GhostButton(
                text = "クリア",
                enabled = logs.isNotEmpty(),
                onClick = onClear
            )
        }

        val shape = RoundedCornerShape(11.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(LftColors.consoleBackground)
                .border(1.dp, LftColors.consoleBorder, shape)
        ) {
            if (logs.isEmpty()) {
                Text(
                    "まだ操作はありません。",
                    fontSize = 11.sp,
                    color = LftColors.textMuted,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(14.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp)
                        .padding(vertical = 8.dp)
                ) {
                    items(logs) { line ->
                        Text(
                            line,
                            fontSize = 11.sp,
                            lineHeight = 17.sp,
                            color = LftColors.consoleForeground,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 1.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun describeProgress(engine: TransferEngine.State): String = when {
    engine.mode == TransferEngine.Mode.LISTENING && engine.fileName == null ->
        "ポート ${engine.port} で接続を待っています" +
            if (engine.filesDone > 0) "（${engine.filesDone} 件受信済み）" else ""

    engine.mode == TransferEngine.Mode.LISTENING && engine.fileName != null ->
        "${engine.fileName}  ${formatBytes(engine.fileDoneBytes)} / ${formatBytes(engine.fileTotalBytes)}"

    engine.mode == TransferEngine.Mode.SENDING && engine.fileName != null ->
        "${engine.fileName}  (${engine.filesDone + 1}/${engine.filesTotal})  " +
            "${formatBytes(engine.overallDoneBytes)} / ${formatBytes(engine.overallTotalBytes)}  " +
            "(${(engine.fraction * 100).toInt()}%)"

    engine.lastSavedName != null ->
        "前回の受信: ${engine.lastSavedName}"

    else ->
        "待機中です。送信または受信を開始してください。"
}
