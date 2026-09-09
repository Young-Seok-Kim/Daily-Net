package com.youngs.dailynet.ui.view

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.ZoomState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.youngs.dailynet.R
import com.youngs.dailynet.util.MealPhoto
import java.io.File

/**
 * 음식 사진을 앱 안에서 직접 찍는 전체 화면 카메라.
 *
 * 시스템 카메라 앱을 인텐트로 띄우면 셔터음을 우리가 제어할 수 없다 (국내 단말은 무음 모드에서도 강제).
 * CameraX로 직접 캡처하면 소리는 앱이 재생할 때만 나므로, 여기서는 아무 소리도 내지 않는다.
 *
 * 촬영에 성공하면 [MealPhoto.createTempImageFile] 위치에 JPEG를 쓰고 [onCaptured]를 부른다.
 * 닫기·뒤로가기·카메라 실패는 모두 [onDismiss]로 끝난다.
 *
 * 호출 전에 CAMERA 권한이 있어야 한다. 권한 처리는 호출한 쪽 책임.
 */
@Composable
fun MealCameraScreen(
    onCaptured: (File) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }

    // 캡처 유즈케이스는 프리뷰 바인딩 때 만들어 두고 셔터 버튼에서 재사용한다
    val imageCapture = remember {
        ImageCapture.Builder()
            // 인식용이라 화질보다 빠른 응답이 낫다. 어차피 768px로 줄여서 보낸다.
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    // 현재 줌 상태(배율·범위). 카메라가 알려주는 값을 그대로 받아 핀치와 슬라이더가 같은 값을 본다.
    var zoomState by remember { mutableStateOf<ZoomState?>(null) }
    var capturing by remember { mutableStateOf(false) }

    val unavailableMessage = stringResource(R.string.camera_unavailable)
    val failedMessage = stringResource(R.string.camera_capture_failed)

    // 화면이 사라지면 카메라를 확실히 놓아준다. 다이얼로그가 닫혀도 바인딩이 남으면 다음에 열 때 충돌한다.
    DisposableEffect(Unit) {
        onDispose { cameraProvider?.unbindAll() }
    }

    BackHandler(enabled = !capturing) { onDismiss() }

    // 프리뷰 뷰가 만들어지면 한 번만 카메라를 붙인다.
    // (AndroidView의 update 람다에서 하면 재구성마다 불려 바인딩이 겹칠 수 있다)
    LaunchedEffect(previewView) {
        val view = previewView ?: return@LaunchedEffect
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = runCatching { future.get() }.getOrNull()
            if (provider == null) {
                Toast.makeText(context, unavailableMessage, Toast.LENGTH_SHORT).show()
                onDismiss()
                return@addListener
            }
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = view.surfaceProvider
            }
            view.display?.let { imageCapture.targetRotation = it.rotation }
            val bound = runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture
                )
            }.getOrNull()
            if (bound == null) {
                Toast.makeText(context, unavailableMessage, Toast.LENGTH_SHORT).show()
                onDismiss()
                return@addListener
            }
            cameraProvider = provider
            camera = bound
        }, mainExecutor)
    }

    // 줌 범위와 현재 배율을 카메라에서 받아온다 (기기마다 최소·최대가 다르다)
    DisposableEffect(camera) {
        val info = camera?.cameraInfo ?: return@DisposableEffect onDispose {}
        val observer = Observer<ZoomState> { zoomState = it }
        info.zoomState.observe(lifecycleOwner, observer)
        onDispose { info.zoomState.removeObserver(observer) }
    }

    fun setZoomRatio(ratio: Float) {
        val z = zoomState ?: return
        camera?.cameraControl?.setZoomRatio(ratio.coerceIn(z.minZoomRatio, z.maxZoomRatio))
    }

    Dialog(
        onDismissRequest = { if (!capturing) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        // TextureView 대신 SurfaceView를 쓰면 프리뷰가 부드럽고 배터리도 덜 먹는다
                        implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                        previewView = this
                    }
                }
            )

            // 두 손가락 핀치로 줌. 프리뷰(안드로이드 뷰)에 직접 걸지 않고 투명한 층을 위에 덮어 받는다.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(camera) {
                        detectTransformGestures { _, _, zoomChange, _ ->
                            val current = zoomState?.zoomRatio ?: return@detectTransformGestures
                            setZoomRatio(current * zoomChange)
                        }
                    }
            )

            // 닫기
            IconButton(
                onClick = { if (!capturing) onDismiss() },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.camera_close),
                    tint = Color.White
                )
            }

            // 안내 + 셔터
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.camera_hint),
                    color = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                zoomState?.let { z ->
                    ZoomBar(
                        zoom = z,
                        onLinearZoom = { camera?.cameraControl?.setLinearZoom(it) }
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                ShutterButton(
                    enabled = cameraProvider != null && !capturing,
                    capturing = capturing,
                    onClick = {
                        capturing = true
                        val file = MealPhoto.createTempImageFile(context)
                        val output = ImageCapture.OutputFileOptions.Builder(file).build()
                        // 셔터음은 일부러 재생하지 않는다 (MediaActionSound 없음)
                        imageCapture.takePicture(
                            output,
                            mainExecutor,
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                                    capturing = false
                                    onCaptured(file)
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    exception.printStackTrace()
                                    capturing = false
                                    Toast.makeText(context, failedMessage, Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                )
            }
        }
    }
}

/**
 * 기본 카메라처럼 좌우로 끌어서 줌하는 바. 왼쪽 끝이 최소, 오른쪽 끝이 최대 배율.
 * 값은 CameraX의 linearZoom(0~1)을 그대로 쓴다. 배율을 직선으로 쓰면 저배율 구간이 너무 좁아진다.
 */
@Composable
private fun ZoomBar(
    zoom: ZoomState,
    onLinearZoom: (Float) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 40.dp)
    ) {
        Slider(
            value = zoom.linearZoom,
            onValueChange = onLinearZoom,
            valueRange = 0f..1f,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.35f)
            )
        )
        Text(
            text = String.format(java.util.Locale.US, "%.1fx", zoom.zoomRatio),
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}

/** 카메라 앱에서 흔히 보는 흰 원형 셔터. 찍는 동안엔 원 안에 진행 표시가 돈다. */
@Composable
private fun ShutterButton(
    enabled: Boolean,
    capturing: Boolean,
    onClick: () -> Unit
) {
    val description = stringResource(R.string.camera_shutter)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(76.dp)
            .border(4.dp, Color.White, CircleShape)
            .padding(6.dp)
            .background(
                if (enabled) Color.White else Color.White.copy(alpha = 0.4f),
                CircleShape
            )
            .semantics { contentDescription = description }
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxSize()
        ) {
            if (capturing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    color = Color.Black,
                    strokeWidth = 3.dp
                )
            }
        }
    }
}
