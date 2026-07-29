package com.youngs.dailynet.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 음식 사진을 카메라로 찍어 서버로 보낼 수 있는 형태로 만드는 유틸.
 *
 * 사진은 **저장하지 않는다.** 서버가 메뉴를 텍스트로 바꿔주면 원본은 바로 지운다.
 * 사진을 Cloud Storage에 쌓으면 사용자 한 명당 연간 수백 MB가 되는데,
 * 정작 다시 볼 일은 거의 없기 때문이다.
 */
object MealPhoto {

    /**
     * 서버로 보낼 때의 최대 변 길이.
     * 음식 인식에는 이 정도면 충분하고, 더 키워봐야 업로드 시간과 토큰만 늘어난다.
     */
    private const val MAX_EDGE = 768
    private const val JPEG_QUALITY = 80
    private const val DIR_NAME = "meal_photos"

    /** 카메라 앱이 사진을 써 넣을 임시 파일의 URI */
    fun createTempImageUri(context: Context): Uri {
        val dir = File(context.cacheDir, DIR_NAME).apply { mkdirs() }
        // 파일명은 하나로 고정한다. 어차피 바로 지우므로 쌓일 일이 없다.
        val file = File(dir, "capture.jpg")
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /**
     * 사진을 줄이고 압축해 Base64 문자열로 바꾼다. 읽지 못하면 null.
     */
    fun encodeToBase64(context: Context, uri: Uri): String? {
        return try {
            val bitmap = decodeScaled(context, uri) ?: return null
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
            bitmap.recycle()
            Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** 임시 사진 파일을 지운다. 분석이 끝났으면 원본을 남길 이유가 없다. */
    fun deleteTemp(context: Context) {
        runCatching { File(context.cacheDir, DIR_NAME).deleteRecursively() }
    }

    /**
     * 원본을 통째로 메모리에 올리지 않도록, 크기를 먼저 재고 샘플링해서 읽은 뒤 정확히 맞춘다.
     * 요즘 폰 사진은 4000px가 넘어서 그냥 읽으면 OutOfMemory가 날 수 있다.
     */
    private fun decodeScaled(context: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / sample > MAX_EDGE * 2 && bounds.outHeight / sample > MAX_EDGE * 2) {
            sample *= 2
        }

        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null

        val longEdge = maxOf(decoded.width, decoded.height)
        if (longEdge <= MAX_EDGE) return decoded

        val ratio = MAX_EDGE.toFloat() / longEdge
        val scaled = Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * ratio).toInt().coerceAtLeast(1),
            (decoded.height * ratio).toInt().coerceAtLeast(1),
            true
        )
        if (scaled != decoded) decoded.recycle()
        return scaled
    }
}
