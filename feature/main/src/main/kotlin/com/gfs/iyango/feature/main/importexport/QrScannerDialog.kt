package com.gfs.iyango.feature.main.importexport

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gfs.iyango.core.designsystem.component.GlassTopBarIconButton
import com.gfs.iyango.core.designsystem.liquidglass.GlassMaterial
import com.gfs.iyango.core.designsystem.liquidglass.GlassSurface
import com.gfs.iyango.core.designsystem.liquidglass.LiquidButton
import com.gfs.iyango.core.designsystem.liquidglass.LiquidOutlinedButton
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 应用内离线二维码扫描弹窗（CameraX + ZXing）。
 */
@Composable
internal fun QrScannerDialog(
    onScanned: (String) -> Unit,
    onPickFromGallery: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var permissionRequested by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCameraPermission = granted
        permissionRequested = true
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission && !permissionRequested) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    var torchEnabled by remember { mutableStateOf(false) }
    var activeCamera by remember { mutableStateOf<Camera?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            if (hasCameraPermission) {
                val previewView = remember {
                    PreviewView(context).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }
                }
                val executor = remember { Executors.newSingleThreadExecutor() }
                val handled = remember { AtomicBoolean(false) }
                val mainExecutor = remember { ContextCompat.getMainExecutor(context) }

                AndroidView(
                    factory = { previewView },
                    modifier = Modifier.fillMaxSize(),
                )

                DisposableEffect(lifecycleOwner, previewView) {
                    val providerFuture = ProcessCameraProvider.getInstance(context)
                    var cameraProvider: ProcessCameraProvider? = null
                    val listener = Runnable {
                        val provider = runCatching { providerFuture.get() }.getOrNull() ?: return@Runnable
                        cameraProvider = provider
                        val preview = Preview.Builder().build().apply {
                            surfaceProvider = previewView.surfaceProvider
                        }
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        analysis.setAnalyzer(executor) { imageProxy ->
                            try {
                                if (handled.get()) return@setAnalyzer
                                val plane = imageProxy.planes.firstOrNull() ?: return@setAnalyzer
                                val buffer = plane.buffer
                                val bytes = ByteArray(buffer.remaining())
                                buffer.get(bytes)
                                val decoded = decodeQrFromYuvPlane(
                                    yBytes = bytes,
                                    width = imageProxy.width,
                                    height = imageProxy.height,
                                    rowStride = plane.rowStride,
                                )
                                if (!decoded.isNullOrBlank() && handled.compareAndSet(false, true)) {
                                    mainExecutor.execute { onScanned(decoded) }
                                }
                            } finally {
                                imageProxy.close()
                            }
                        }
                        runCatching {
                            provider.unbindAll()
                            activeCamera = provider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                analysis,
                            )
                        }
                    }
                    providerFuture.addListener(listener, mainExecutor)
                    onDispose {
                        runCatching { cameraProvider?.unbindAll() }
                        activeCamera = null
                        executor.shutdown()
                    }
                }

                // 中央取景框四角引导
                Canvas(
                    modifier = Modifier
                        .size(240.dp)
                        .align(Alignment.Center),
                ) {
                    val corner = 28.dp.toPx()
                    val stroke = 3.5.dp.toPx()
                    val color = Color.White
                    val w = size.width
                    val h = size.height

                    // 左上
                    drawLine(color, Offset(0f, 0f), Offset(corner, 0f), stroke, StrokeCap.Round)
                    drawLine(color, Offset(0f, 0f), Offset(0f, corner), stroke, StrokeCap.Round)
                    // 右上
                    drawLine(color, Offset(w - corner, 0f), Offset(w, 0f), stroke, StrokeCap.Round)
                    drawLine(color, Offset(w, 0f), Offset(w, corner), stroke, StrokeCap.Round)
                    // 左下
                    drawLine(color, Offset(0f, h), Offset(corner, h), stroke, StrokeCap.Round)
                    drawLine(color, Offset(0f, h - corner), Offset(0f, h), stroke, StrokeCap.Round)
                    // 右下
                    drawLine(color, Offset(w - corner, h), Offset(w, h), stroke, StrokeCap.Round)
                    drawLine(color, Offset(w, h - corner), Offset(w, h), stroke, StrokeCap.Round)
                    drawRect(
                        color = Color.White.copy(alpha = 0.08f),
                        topLeft = Offset.Zero,
                        size = Size(w, h),
                    )
                }
            } else {
                GlassSurface(
                    shape = RoundedCornerShape(20.dp),
                    material = GlassMaterial.CONTENT,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp)
                        .fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "需要相机权限",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            LiquidButton(
                                onClick = {
                                    val intent = Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.fromParts("package", context.packageName, null),
                                    )
                                    runCatching { context.startActivity(intent) }
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("去授权")
                            }
                            LiquidOutlinedButton(
                                onClick = {
                                    onDismiss()
                                    onPickFromGallery()
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("相册识码")
                            }
                        }
                    }
                }
            }

            // 顶栏返回
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GlassTopBarIconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "关闭扫码",
                        tint = Color.White,
                    )
                }
                Text(
                    text = "扫描课表二维码",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                )
            }

            // 底部操作按钮
            if (hasCameraPermission) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 24.dp, vertical = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val hasFlash = activeCamera?.cameraInfo?.hasFlashUnit() == true
                    if (hasFlash) {
                        LiquidOutlinedButton(
                            onClick = {
                                val next = !torchEnabled
                                torchEnabled = next
                                activeCamera?.cameraControl?.enableTorch(next)
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(if (torchEnabled) "关闭手电筒" else "打开手电筒")
                        }
                    }
                    LiquidButton(
                        onClick = {
                            onDismiss()
                            onPickFromGallery()
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("相册识码")
                    }
                }
            }
        }
    }
}
