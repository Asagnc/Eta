package io.github.mangi.eta.agent.browser

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

internal data class BrowserDownloadOutcome(
    val sourceUrl: String,
    val finalUrl: String,
    val file: File,
    val bytes: Long,
    val mimeType: String,
    val httpStatus: Int,
)

/**
 * 把下载内容落到 /storage/emulated/0/Download/Eta。
 *
 * 不用 WebView 自己的下载栈：这里要复用当前页面的 Cookie 与 User-Agent，并把落盘路径、字节数
 * 交给 Agent。写入公共下载目录依赖「所有文件访问」权限，是否具备由调用方先判断。
 */
internal object BrowserDownloader {
    private const val CONNECT_TIMEOUT_SECONDS = 20L
    private const val READ_TIMEOUT_SECONDS = 60L
    private const val DEFAULT_MIME_TYPE = "application/octet-stream"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun download(
        context: Context,
        url: String,
        fileName: String?,
        cookieHeader: String?,
        userAgent: String?,
        referer: String?,
    ): BrowserDownloadOutcome {
        val directory = File(
            Environment.getExternalStorageDirectory(),
            BrowserDownloadNaming.RELATIVE_DIRECTORY,
        )
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("无法创建下载目录 ${directory.absolutePath}")
        }
        val request = Request.Builder()
            .url(url)
            .header("Accept", "*/*")
            .apply {
                cookieHeader?.takeIf { it.isNotBlank() }?.let { header("Cookie", it) }
                userAgent?.takeIf { it.isNotBlank() }?.let { header("User-Agent", it) }
                referer?.takeIf { it.isNotBlank() }?.let { header("Referer", it) }
            }
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("下载失败：服务端返回 HTTP ${response.code}")
            }
            val body = response.body
            val suggested = fileName
                ?: BrowserDownloadNaming.contentDispositionFileName(response.header("Content-Disposition"))
                ?: BrowserDownloadNaming.urlFileName(url)
            val target = BrowserDownloadNaming.uniqueFile(
                directory,
                BrowserDownloadNaming.sanitizeFileName(suggested),
            )
            body.byteStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            val mimeType = body.contentType()?.toString()?.takeIf { it.isNotBlank() } ?: DEFAULT_MIME_TYPE
            MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mimeType), null)
            return BrowserDownloadOutcome(
                sourceUrl = url,
                finalUrl = response.request.url.toString(),
                file = target,
                bytes = target.length(),
                mimeType = mimeType,
                httpStatus = response.code,
            )
        }
    }
}
