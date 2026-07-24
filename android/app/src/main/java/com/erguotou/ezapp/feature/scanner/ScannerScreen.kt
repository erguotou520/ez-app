package com.erguotou.ezapp.feature.scanner

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.erguotou.ezapp.ui.theme.Ink
import com.erguotou.ezapp.ui.components.AppScreen
import com.erguotou.ezapp.ui.theme.MutedInk
import com.erguotou.ezapp.ui.theme.Night
import com.erguotou.ezapp.ui.theme.Paper
import com.erguotou.ezapp.ui.theme.SoftLine
import com.erguotou.ezapp.ui.theme.Vermilion
import com.google.mlkit.vision.barcode.BarcodeScanning
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

private enum class ScannerPage { CAMERA, HISTORY }

@Composable
fun ScannerScreen(onBack: () -> Unit, viewModel: ScannerViewModel = viewModel()) {
    val context = LocalContext.current
    val history by viewModel.history.collectAsState()
    var page by remember { mutableStateOf(ScannerPage.CAMERA) }
    var result by remember { mutableStateOf<ScanRecord?>(null) }
    var imageError by remember { mutableStateOf(false) }
    var cameraGranted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { cameraGranted = it }
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        viewModel.scanImage(uri) { value, format ->
            if (value == null) imageError = true else {
                viewModel.save(value, format ?: "二维码")
                result = ScanRecord(value, format ?: "二维码", System.currentTimeMillis())
            }
        }
    }
    LaunchedEffect(Unit) { if (!cameraGranted) permissionLauncher.launch(Manifest.permission.CAMERA) }

    AppScreen(background = if (page == ScannerPage.CAMERA) Night else Paper) {
        Column(Modifier.fillMaxSize()) {
            ScannerTopBar(
                page = page,
                onBack = if (page == ScannerPage.HISTORY) ({ page = ScannerPage.CAMERA }) else onBack,
                onHistory = { page = ScannerPage.HISTORY },
                onClear = if (history.isNotEmpty()) viewModel::clearHistory else null,
            )
            if (page == ScannerPage.CAMERA) {
                CameraPage(
                    cameraGranted = cameraGranted,
                    requestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    openGallery = { galleryLauncher.launch("image/*") },
                    onDetected = { value, format ->
                        if (result == null) {
                            viewModel.save(value, format)
                            result = ScanRecord(value, format, System.currentTimeMillis())
                        }
                    },
                )
            } else {
                HistoryPage(history = history, onSelect = { result = it })
            }
        }
    }

    result?.let { record ->
        ResultDialog(record = record, onDismiss = { result = null }, onCopy = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("扫描结果", record.value))
            Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
            result = null
        })
    }
    if (imageError) {
        AlertDialog(
            onDismissRequest = { imageError = false },
            title = { Text("没有识别到码") },
            text = { Text("请换一张更清晰、二维码占比更大的图片再试。") },
            confirmButton = { TextButton(onClick = { imageError = false; galleryLauncher.launch("image/*") }) { Text("重新选择") } },
            dismissButton = { TextButton(onClick = { imageError = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun ScannerTopBar(page: ScannerPage, onBack: () -> Unit, onHistory: () -> Unit, onClear: (() -> Unit)?) {
    val foreground = if (page == ScannerPage.CAMERA) Color(0xFFF5F1E8) else Ink
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回", tint = foreground) }
        Text(if (page == ScannerPage.CAMERA) "扫一扫" else "识别历史", color = foreground, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (page == ScannerPage.CAMERA) {
            IconButton(onClick = onHistory) { Icon(Icons.Outlined.History, "识别历史", tint = foreground) }
        } else if (onClear != null) {
            TextButton(onClick = onClear) { Icon(Icons.Outlined.DeleteOutline, null); Text("清空") }
        }
    }
}

@Composable
private fun CameraPage(cameraGranted: Boolean, requestPermission: () -> Unit, openGallery: () -> Unit, onDetected: (String, String) -> Unit) {
    Box(Modifier.fillMaxSize()) {
        if (cameraGranted) CameraPreview(onDetected) else PermissionPrompt(requestPermission)
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(.23f))
            Box(
                Modifier.size(264.dp).drawBehind {
                    val stroke = 3.dp.toPx(); val corner = 24.dp.toPx(); val arm = 42.dp.toPx()
                    listOf(
                        Pair(Offset(0f, arm), Offset(0f, corner)), Pair(Offset(corner, 0f), Offset(arm, 0f)),
                        Pair(Offset(size.width - arm, 0f), Offset(size.width - corner, 0f)), Pair(Offset(size.width, corner), Offset(size.width, arm)),
                        Pair(Offset(0f, size.height - arm), Offset(0f, size.height - corner)), Pair(Offset(corner, size.height), Offset(arm, size.height)),
                        Pair(Offset(size.width, size.height - arm), Offset(size.width, size.height - corner)), Pair(Offset(size.width - arm, size.height), Offset(size.width - corner, size.height)),
                    ).forEach { drawLine(Vermilion, it.first, it.second, stroke) }
                    drawRoundRect(Color.White.copy(alpha = .12f), style = Stroke(1.dp.toPx()), cornerRadius = CornerRadius(corner), size = Size(size.width, size.height))
                }
            )
            Spacer(Modifier.height(26.dp))
            Text("将二维码放入框内", color = Color(0xFFF5F1E8), fontSize = 15.sp)
            Text("识别成功后会自动停留", color = Color(0xFFA8AAA3), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.weight(1f))
            OutlinedButton(
                onClick = openGallery,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFF5F1E8)),
                shape = CircleShape,
                modifier = Modifier.padding(bottom = 34.dp).height(52.dp),
            ) {
                Icon(Icons.Outlined.PhotoLibrary, null, Modifier.size(20.dp))
                Text("从相册识别", modifier = Modifier.padding(horizontal = 10.dp))
            }
        }
    }
}

@Composable
private fun CameraPreview(onDetected: (String, String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember { BarcodeScanning.getClient() }

    AndroidView(
        factory = { PreviewView(it).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } },
        modifier = Modifier.fillMaxSize(),
        update = { previewView ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                val provider = future.get()
                val preview = androidx.camera.core.Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                    .also { it.setAnalyzer(executor, BarcodeAnalyzer(scanner, onDetected)) }
                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }
            }, ContextCompat.getMainExecutor(context))
        },
    )
    DisposableEffect(Unit) { onDispose { scanner.close(); executor.shutdown() } }
}

@Composable
private fun PermissionPrompt(onGrant: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(38.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Outlined.QrCodeScanner, null, tint = Vermilion, modifier = Modifier.size(48.dp))
        Text("需要使用摄像头", color = Color(0xFFF5F1E8), fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 20.dp))
        Text("画面只在你的手机上用于识别，不会上传。", color = Color(0xFFA8AAA3), fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
        Button(onClick = onGrant, modifier = Modifier.padding(top = 24.dp), shape = CircleShape) { Text("允许并开始扫码") }
    }
}

@Composable
private fun HistoryPage(history: List<ScanRecord>, onSelect: (ScanRecord) -> Unit) {
    if (history.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Outlined.Collections, null, tint = SoftLine, modifier = Modifier.size(58.dp))
            Text("还没有识别记录", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp))
            Text("每次扫码结果都会保存在这里", color = MutedInk, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        items(history, key = { "${it.scannedAt}-${it.value}" }) { record ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(record.value, color = Ink, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text("${record.format}  ·  ${formatTime(record.scannedAt)}", color = MutedInk, fontSize = 12.sp, modifier = Modifier.padding(top = 7.dp))
                }
                IconButton(onClick = { onSelect(record) }) { Icon(Icons.Outlined.ContentCopy, "查看结果", tint = Vermilion) }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun ResultDialog(record: ScanRecord, onDismiss: () -> Unit, onCopy: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.QrCodeScanner, null, tint = Vermilion) },
        title = { Text("识别成功") },
        text = {
            Column {
                Text(record.format, color = MutedInk, fontSize = 12.sp)
                Text(record.value, color = Ink, fontSize = 16.sp, lineHeight = 24.sp, modifier = Modifier.padding(top = 10.dp))
            }
        },
        confirmButton = { Button(onClick = onCopy, shape = CircleShape) { Icon(Icons.Outlined.ContentCopy, null); Text("复制", Modifier.padding(start = 7.dp)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("继续扫描") } },
    )
}

private fun formatTime(timestamp: Long): String = SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA).format(Date(timestamp))
