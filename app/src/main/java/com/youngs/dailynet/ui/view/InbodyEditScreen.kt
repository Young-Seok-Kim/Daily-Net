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
 * 인바디 측정 한 건의 입력·보기 화면.
 *
 * [measuredAt]이 비어 있으면 새 측정 추가, 있으면 그 측정을 열어 값·해석을 보고 고치거나 지운다.
 * 위쪽은 입력(결과지 사진으로 채우기 + 칸), 아래쪽은 해석 리포트다.
 *
 * 사진으로 읽은 값은 바로 저장하지 않고 칸에 채워 사용자가 확인한 뒤 저장한다.
 * 결과지 양식이 제각각이라 칸을 잘못 읽을 수 있어서다. 저장하면 해석은 자동으로 받는다.
 * 측정일이 키라, 새 측정의 날짜를 기존 것과 같게 저장하면 그 측정을 덮어쓴다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InbodyEditScreen(
    mainViewModel: MainViewModel,
    measuredAt: String,
    onBack: () -> Unit,
    onSaved: (measuredAt: String) -> Unit
) {
    val context = LocalContext.current
    val userProfile by mainViewModel.userProfile.collectAsState()
    val records by mainViewModel.inbodyRecords.collectAsState()
    val photoProcessing by mainViewModel.inbodyPhotoProcessing.collectAsState()
    val analyzingFor by mainViewModel.inbodyAnalyzingFor.collectAsState()
    val isNew = measuredAt.isBlank()
    val saved = if (isNew) null else records.firstOrNull { it.measuredAt == measuredAt }?.toData()
    val analyzing = !isNew && analyzingFor == measuredAt

    BackHandler(enabled = true) { onBack() }

    // ── 입력칸 상태 ──
    var bmrText by remember { mutableStateOf("") }
    var muscleText by remember { mutableStateOf("") }
    var fatText by remember { mutableStateOf("") }
    var fatPctText by remember { mutableStateOf("") }
    var weightText by remember { mutableStateOf("") }
    var visceralText by remember { mutableStateOf("") }
    var scoreText by remember { mutableStateOf("") }
    var waterText by remember { mutableStateOf("") }
    var dateText by remember {
        mutableStateOf(SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()))
    }
    // 저장된 값을 한 번 채웠는지. 이력이 늦게 도착해도 사용자가 치던 값을 덮지 않기 위해서다
    var loaded by remember { mutableStateOf(isNew) }

    fun num(v: Float) = if (v > 0f) v.toString() else ""
    fun int(v: Int) = if (v > 0) v.toString() else ""
    fun load(d: InbodyData) {
        bmrText = int(d.bmr)
        muscleText = num(d.skeletalMuscle)
        fatText = num(d.bodyFat)
        fatPctText = num(d.bodyFatPercent)
        weightText = num(d.weight)
        visceralText = int(d.visceralFatLevel)
        scoreText = int(d.inbodyScore)
        waterText = num(d.bodyWater)
        if (d.measuredAt.isNotBlank()) dateText = d.measuredAt
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
        visceralFatLevel = visceralText.trim().toIntOrNull() ?: 0,
        inbodyScore = scoreText.trim().toIntOrNull() ?: 0,
        bodyWater = waterText.trim().toFloatOrNull() ?: 0f,
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
    // 결과지는 한 장이면 된다. 읽은 값은 칸에만 채우고 저장은 사용자가 한다. 사진 자체는 남기지 않는다
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

    var showDeleteConfirm by remember { mutableStateOf(false) }
    if (showDeleteConfirm && !isNew) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.inbody_delete_title)) },
            text = { Text(stringResource(R.string.inbody_delete_message, measuredAt)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    mainViewModel.deleteInbody(measuredAt) { ok -> if (ok) onBack() }
                }) { Text(stringResource(R.string.inbody_clear), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    val busy = photoProcessing || analyzing
    val draft = current()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) stringResource(R.string.inbody_add) else measuredAt) },
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

            OutlinedTextField(
                value = bmrText,
                onValueChange = { bmrText = it.filter { c -> c.isDigit() } },
                label = { Text(stringResource(R.string.inbody_bmr_label)) },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(muscleText, { muscleText = it }, R.string.inbody_muscle_label, Modifier.weight(1f))
                NumberField(weightText, { weightText = it }, R.string.inbody_weight_label, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(fatText, { fatText = it }, R.string.inbody_fat_label, Modifier.weight(1f))
                NumberField(fatPctText, { fatPctText = it }, R.string.inbody_fat_pct_label, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(
                    visceralText, { visceralText = it.filter { c -> c.isDigit() } },
                    R.string.inbody_visceral_label, Modifier.weight(1f), integer = true
                )
                NumberField(
                    scoreText, { scoreText = it.filter { c -> c.isDigit() } },
                    R.string.inbody_score_label, Modifier.weight(1f), integer = true
                )
            }
            NumberField(waterText, { waterText = it }, R.string.inbody_water_label, Modifier.fillMaxWidth())

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!isNew) {
                    TextButton(onClick = { showDeleteConfirm = true }, enabled = !busy) {
                        Text(stringResource(R.string.inbody_clear), color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                Button(
                    onClick = { mainViewModel.saveInbody(draft) { ok -> if (ok) onSaved(draft.measuredAt) } },
                    enabled = draft.hasData && draft.measuredAt.isNotBlank() && !busy && userProfile != null
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
                saved == null -> {
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
                    OutlinedButton(onClick = { mainViewModel.analyzeInbody(measuredAt) }, enabled = !busy) {
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
                    OutlinedButton(onClick = { mainViewModel.analyzeInbody(measuredAt) }, enabled = !busy) {
                        Text(stringResource(R.string.inbody_reanalyze_button))
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun NumberField(
    value: String,
    onChange: (String) -> Unit,
    labelRes: Int,
    modifier: Modifier,
    integer: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(labelRes)) },
        modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = if (integer) KeyboardType.Number else KeyboardType.Decimal),
        singleLine = true
    )
}

/** 설정 목록·이력 줄에 쓸 한 줄 요약. 있는 값만 " · "로 잇는다 */
@Composable
fun inbodySummary(data: InbodyData?, withDate: Boolean = true): String {
    if (data == null || !data.hasData) return stringResource(R.string.settings_inbody_empty)
    val parts = mutableListOf<String>()
    if (data.bmr > 0) parts += stringResource(R.string.inbody_summary_bmr, data.bmr)
    if (data.skeletalMuscle > 0f) parts += stringResource(R.string.inbody_summary_muscle, data.skeletalMuscle)
    if (data.bodyFatPercent > 0f) parts += stringResource(R.string.inbody_summary_fat_pct, data.bodyFatPercent)
    if (withDate && data.measuredAt.isNotBlank()) parts += data.measuredAt
    return parts.joinToString(" · ")
}
