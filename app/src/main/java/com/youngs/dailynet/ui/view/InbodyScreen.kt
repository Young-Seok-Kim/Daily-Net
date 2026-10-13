package com.youngs.dailynet.ui.view

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.youngs.dailynet.R
import com.youngs.dailynet.data.model.InbodyData
import com.youngs.dailynet.ui.viewmodel.MainViewModel
import com.youngs.dailynet.util.MealPhoto
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 인바디 결과 화면. 설정 > 내 정보 > 인바디 결과에서 들어온다.
 *
 * 위쪽은 입력(결과지 사진으로 채우기 + 칸), 아래쪽은 저장된 값에 대한 해석 리포트다.
 * 처음엔 다이얼로그였는데 사진 버튼·칸 여섯 개·해석 글까지 들어가니 한 창에 담기엔 길어 화면으로 뺐다.
 * 카메라도 다이얼로그 위에서는 가려지는 문제가 있었다 (다이얼로그는 별도 창이라 카메라 위에 남는다).
 *
 * 사진으로 읽은 값은 바로 저장하지 않고 칸에 채워 사용자가 확인한 뒤 저장한다.
 * 결과지 양식이 제각각이라 칸을 잘못 읽을 수 있어서다. 저장하면 해석은 자동으로 다시 돌린다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InbodyScreen(
    mainViewModel: MainViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val userProfile by mainViewModel.userProfile.collectAsState()
    val photoProcessing by mainViewModel.inbodyPhotoProcessing.collectAsState()
    val analyzing by mainViewModel.inbodyAnalyzing.collectAsState()
    val saved = userProfile?.let { InbodyData.fromProfile(it) }

    BackHandler(enabled = true) { onBack() }

    // ── 입력칸 상태 ──
    var bmrText by remember { mutableStateOf("") }
    var muscleText by remember { mutableStateOf("") }
    var fatText by remember { mutableStateOf("") }
    var fatPctText by remember { mutableStateOf("") }
    var weightText by remember { mutableStateOf("") }
    var dateText by remember { mutableStateOf("") }
    // 칸을 한 번 채웠는지. 프로필이 늦게 도착해도 사용자가 치던 값을 덮지 않기 위해서다
    var loaded by remember { mutableStateOf(false) }

    fun num(v: Float) = if (v > 0f) v.toString() else ""
    fun load(d: InbodyData) {
        bmrText = if (d.bmr > 0) d.bmr.toString() else ""
        muscleText = num(d.skeletalMuscle)
        fatText = num(d.bodyFat)
        fatPctText = num(d.bodyFatPercent)
        weightText = num(d.weight)
        dateText = d.measuredAt.ifBlank { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()) }
    }

    LaunchedEffect(saved) {
        if (!loaded && saved != null) {
            load(saved)
            loaded = true
        }
    }

    fun current() = InbodyData(
        bmr = bmrText.trim().toIntOrNull() ?: 0,
        skeletalMuscle = muscleText.trim().toFloatOrNull() ?: 0f,
        bodyFat = fatText.trim().toFloatOrNull() ?: 0f,
        bodyFatPercent = fatPctText.trim().toFloatOrNull() ?: 0f,
        weight = weightText.trim().toFloatOrNull() ?: 0f,
        measuredAt = dateText.trim()
    )

    fun calendarOf(dateStr: String): Calendar {
        val cal = Calendar.getInstance()
        runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(dateStr)?.let { cal.time = it }
        }
        return cal
    }

    // ── 결과지 사진 ──
    var showPhotoSource by remember { mutableStateOf(false) }
    var cameraOpen by remember { mutableStateOf(false) }
    val cameraDeniedMessage = stringResource(R.string.camera_permission_denied)
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) cameraOpen = true
        else Toast.makeText(context, cameraDeniedMessage, Toast.LENGTH_LONG).show()
    }
    // 결과지는 한 장이면 된다. 읽은 값은 칸에만 채우고 저장은 사용자가 한다
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) mainViewModel.extractInbodyFromPhoto(uri) { read -> if (read != null) load(read) }
    }

    if (cameraOpen) {
        MealCameraScreen(
            onCaptured = {
                cameraOpen = false
                mainViewModel.extractInbodyFromPhoto(MealPhoto.createTempImageUri(context)) { read ->
                    if (read != null) load(read)
                }
            },
            onDismiss = { cameraOpen = false }
        )
        return
    }

    if (showPhotoSource) {
        AlertDialog(
            onDismissRequest = { showPhotoSource = false },
            title = { Text(stringResource(R.string.inbody_photo_source_title)) },
            text = { Text(stringResource(R.string.inbody_photo_source_desc)) },
            confirmButton = {
                TextButton(onClick = {
                    showPhotoSource = false
                    val granted = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) cameraOpen = true
                    else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }) { Text(stringResource(R.string.photo_source_camera)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showPhotoSource = false
                    galleryLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }) { Text(stringResource(R.string.photo_source_gallery)) }
            }
        )
    }

    val busy = photoProcessing || analyzing
    val draft = current()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.inbody_dialog_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FilledTonalButton(
                onClick = { showPhotoSource = true },
                enabled = !busy && userProfile != null,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (photoProcessing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.inbody_processing))
                } else {
                    // 아이콘 세트(core)에 카메라가 없어 입력칸과 같은 이모지를 쓴다
                    Text("📷")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.inbody_photo_button))
                }
            }

            Text(
                stringResource(R.string.inbody_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = bmrText,
                onValueChange = { bmrText = it.filter { c -> c.isDigit() } },
                label = { Text(stringResource(R.string.inbody_bmr_label)) },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = muscleText,
                    onValueChange = { muscleText = it },
                    label = { Text(stringResource(R.string.inbody_muscle_label)) },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
                OutlinedTextField(
                    value = weightText,
                    onValueChange = { weightText = it },
                    label = { Text(stringResource(R.string.inbody_weight_label)) },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = fatText,
                    onValueChange = { fatText = it },
                    label = { Text(stringResource(R.string.inbody_fat_label)) },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
                OutlinedTextField(
                    value = fatPctText,
                    onValueChange = { fatPctText = it },
                    label = { Text(stringResource(R.string.inbody_fat_pct_label)) },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
            }
            OutlinedTextField(
                value = dateText,
                onValueChange = { },
                label = { Text(stringResource(R.string.inbody_date_label)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val cal = calendarOf(dateText)
                        android.app.DatePickerDialog(
                            context,
                            { _, year, month, day ->
                                dateText = String.format(Locale.getDefault(), "%d-%02d-%02d", year, month + 1, day)
                            },
                            cal.get(Calendar.YEAR),
                            cal.get(Calendar.MONTH),
                            cal.get(Calendar.DAY_OF_MONTH)
                        ).show()
                    },
                enabled = false,
                colors = OutlinedTextFieldDefaults.colors(
                    disabledTextColor = MaterialTheme.colorScheme.onSurface,
                    disabledBorderColor = MaterialTheme.colorScheme.outline,
                    disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                singleLine = true
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (saved != null && saved.hasData) {
                    TextButton(
                        onClick = { mainViewModel.updateInbody(null) { ok -> if (ok) load(InbodyData()) } },
                        enabled = !busy
                    ) {
                        Text(stringResource(R.string.inbody_clear), color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                Button(
                    onClick = { mainViewModel.updateInbody(draft) { } },
                    enabled = draft.hasData && !busy && userProfile != null
                ) {
                    Text(stringResource(R.string.inbody_save_and_analyze))
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ── 해석 리포트 ──
            Text(
                stringResource(R.string.inbody_analysis_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            when {
                analyzing -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.inbody_analyzing), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                saved == null || !saved.hasData -> {
                    Text(
                        stringResource(R.string.inbody_analysis_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                saved.analysis.isBlank() -> {
                    Text(
                        stringResource(R.string.inbody_analysis_missing),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(onClick = { mainViewModel.analyzeInbody() }, enabled = !busy) {
                        Text(stringResource(R.string.inbody_analyze_button))
                    }
                }
                else -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(saved.analysis, style = MaterialTheme.typography.bodyMedium)
                            if (saved.analyzedAt.isNotBlank()) {
                                Text(
                                    stringResource(R.string.inbody_analyzed_at, saved.analyzedAt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    OutlinedButton(onClick = { mainViewModel.analyzeInbody() }, enabled = !busy) {
                        Text(stringResource(R.string.inbody_reanalyze_button))
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/** 설정 목록 부제에 쓸 한 줄 요약. 있는 값만 " · "로 잇는다 */
@Composable
fun inbodySummary(data: InbodyData?): String {
    if (data == null || !data.hasData) return stringResource(R.string.settings_inbody_empty)
    val parts = mutableListOf<String>()
    if (data.bmr > 0) parts += stringResource(R.string.inbody_summary_bmr, data.bmr)
    if (data.skeletalMuscle > 0f) parts += stringResource(R.string.inbody_summary_muscle, data.skeletalMuscle)
    if (data.bodyFatPercent > 0f) parts += stringResource(R.string.inbody_summary_fat_pct, data.bodyFatPercent)
    if (data.measuredAt.isNotBlank()) parts += data.measuredAt
    return parts.joinToString(" · ")
}
