package com.youngs.dailynet.util

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import java.io.File

/**
 * 만든 영수증 PNG를 캐시에 두고 공유 시트로 넘긴다.
 *
 * 캐시에 두는 이유: 공유는 받는 앱이 우리 URI를 읽어 가는 동안만 파일이 있으면 된다.
 * 사용자의 사진첩(MediaStore)에 넣으면 지운 줄 모르게 쌓이고, 권한도 필요해진다.
 *
 * FileProvider 경로는 res/xml/file_paths.xml의 `receipts/`와 짝이다.
 * 둘 중 하나만 있으면 공유 시트에서 "파일을 찾을 수 없음"이 된다.
 */
object ReceiptShare {

    private const val DIR = "receipts"
    private const val MIME = "image/png"

    /**
     * PNG로 저장하고 그 파일을 돌려준다.
     * 이전에 공유한 영수증은 지운다 — 캐시니까 남길 이유가 없고, 계속 쌓이면 앱 용량만 늘어난다.
     */
    fun save(context: Context, bitmap: Bitmap, date: String): File {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        val safeDate = date.replace(Regex("[^0-9A-Za-z-]"), "-").ifEmpty { "today" }
        val file = File(dir, "dailynet_receipt_$safeDate.png")
        file.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        dir.listFiles()?.forEach { if (it.name != file.name) it.delete() }
        return file
    }

    /**
     * 카카오톡·인스타그램 스토리 등에서 바로 열리는 공유 시트.
     *
     * ClipData를 함께 넣는 이유: EXTRA_STREAM만으로는 일부 앱(구형 카카오톡 포함)이
     * URI 읽기 권한을 못 받아 빈 이미지가 간다.
     */
    fun chooser(context: Context, file: File, title: String): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, title).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
