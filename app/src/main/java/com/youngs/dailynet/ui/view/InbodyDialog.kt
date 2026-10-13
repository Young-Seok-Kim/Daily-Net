package com.youngs.dailynet.ui.view

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.youngs.dailynet.R
import com.youngs.dailynet.data.model.InbodyData
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 인바디 결과 입력 다이얼로그. 설정 > 내 정보 > 인바디 결과에서 연다.
 *
 * 맨 위 사진 버튼으로 결과지를 찍거나 고르면 서버가 숫자를 읽어 [prefill]로 들어오고,
 * 칸이 그 값으로 다시 채워진다. 사용자는 확인·수정한 뒤 저장한다. 읽은 값을 바로 저장하지 않는
 * 이유는 결과지 양식이 제각각이라 칸을 잘못 읽을 수 있어서다. 저장 전에 눈으로 한 번 거친다.
 *
 * 신체 정보 다이얼로그와 따로 둔 이유: 그쪽은 가입 때 한 번 넣는 고정 정보고, 인바디는 몇 달마다
 * 갱신하는 값이라 성격이 다르다. 사진 버튼까지 들어가면 한 다이얼로그에 담기엔 길다.
 */
@Composable
fun InbodyEditDialog(
    initial: InbodyData,
    prefill: InbodyData?,
    processing: Boolean,
    onPhoto: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (InbodyData) -> Unit,
    onClear: (() -> Unit)?
) {
    // 사진에서 읽은 값이 들어오면(prefill이 바뀌면) 칸을 그 값으로 다시 채운다
    val source = prefill ?: initial
    fun num(v: Float) = if (v > 0f) v.toString() else ""
    var bmrText by remember(prefill) { mutableStateOf(if (source.bmr > 0) source.bmr.toString() else "") }
    var muscleText by remember(prefill) { mutableStateOf(num(source.skeletalMuscle)) }
    var fatText by remember(prefill) { mutableStateOf(num(source.bodyFat)) }
    var fatPctText by remember(prefill) { mutableStateOf(num(source.bodyFatPercent)) }
    var weightText by remember(prefill) { mutableStateOf(num(source.weight)) }
    var dateText by remember(prefill) {
        mutableStateOf(
            source.measuredAt.ifBlank {
                SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            }
        )
    }

    val context = LocalContext.current

    fun calendarOf(dateStr: String): Calendar {
        val cal = Calendar.getInstance()
        runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(dateStr)?.let { cal.time = it }
        }
        return cal
    }

    fun current() = InbodyData(
        bmr = bmrText.trim().toIntOrNull() ?: 0,
        skeletalMuscle = muscleText.trim().toFloatOrNull() ?: 0f,
        bodyFat = fatText.trim().toFloatOrNull() ?: 0f,
        bodyFatPercent = fatPctText.trim().toFloatOrNull() ?: 0f,
        weight = weightText.trim().toFloatOrNull() ?: 0f,
        measuredAt = dateText.trim()
    )

    AlertDialog(
        onDismissRequest = { if (!processing) onDismiss() },
        title = { Text(stringResource(R.string.inbody_dialog_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FilledTonalButton(
                    onClick = onPhoto,
                    enabled = !processing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (processing) {
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

                if (onClear != null) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onClear, enabled = !processing) {
                            Text(stringResource(R.string.inbody_clear), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        },
        confirmButton = {
            val data = current()
            Button(
                onClick = { onSave(data) },
                enabled = data.hasData && !processing
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !processing) { Text(stringResource(R.string.cancel)) }
        }
    )
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
