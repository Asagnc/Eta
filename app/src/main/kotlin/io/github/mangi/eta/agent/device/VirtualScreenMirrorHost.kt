@file:JvmName("VirtualScreenMirrorHost")

package io.github.mangi.eta.agent.device

import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.SystemClock
import android.view.Surface
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * 副屏镜像的 root 侧宿主，由 app_process 承载：把指定 display 的画面持续写进本地 socket。
 *
 * 镜像任意 display 需要 signature 级的 CAPTURE_VIDEO_OUTPUT 权限，应用进程拿不到；root 裸进程
 * 又建不了窗口——WindowManagerService 只接受在 ActivityManager 注册过的进程。画面因此在这里
 * 产生，由应用进程持有的浮层通过抽象本地 socket 消费。
 *
 * 帧协议：16 字节小端头部（宽度、高度、行字节数、负载字节数）加原始 RGBA 像素。行字节数可能
 * 大于宽度乘四，消费方需要按行压紧。
 *
 * Eta 以 CLASSPATH=<自身 apk> app_process /system/bin <类名> 启动本类，类名与 main 由 proguard
 * 规则保留，混淆或裁剪后宿主无法启动。
 *
 * 用法：app_process / VirtualScreenMirrorHost <displayId> <socketName> <width> <height>
 */
fun main(args: Array<String>) {
    if (args.size < ARGUMENT_COUNT) {
        System.err.println("需要 displayId、socketName、width、height 四个参数")
        return
    }
    val displayId = args[0].toIntOrNull()
    val width = args[2].toIntOrNull()
    val height = args[3].toIntOrNull()
    if (displayId == null || width == null || height == null || width <= 0 || height <= 0) {
        System.err.println("displayId、width、height 必须是正整数")
        return
    }

    val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, MAX_IMAGES)
    val mirror = runCatching {
        DisplayManager::class.java
            .getMethod(
                "createVirtualDisplay",
                String::class.java,
                Integer.TYPE,
                Integer.TYPE,
                Integer.TYPE,
                Surface::class.java,
            )
            .invoke(null, MIRROR_NAME, width, height, displayId, reader.surface)
    }.getOrElse { throwable ->
        System.err.println("创建镜像 display 失败：${throwable.cause ?: throwable}")
        reader.close()
        return
    }
    if (mirror == null) {
        System.err.println("创建镜像 display 失败：系统未返回 VirtualDisplay")
        reader.close()
        return
    }

    val server = runCatching { LocalServerSocket(args[1]) }.getOrElse { throwable ->
        System.err.println("监听本地 socket 失败：$throwable")
        reader.close()
        return
    }
    try {
        val accepted = AtomicBoolean(false)
        // 浮层可能根本没连上来（启动失败或进程死亡），留一个兜底退出，避免宿主长留。
        Thread({
            runCatching { Thread.sleep(CONNECT_TIMEOUT_MS) }
            if (!accepted.get()) exitProcess(0)
        }, ACCEPT_WATCH_THREAD_NAME).apply { isDaemon = true }.start()

        val client = server.accept()
        accepted.set(true)
        watchClientClose(client)
        streamFrames(client, reader, width, height)
    } catch (throwable: Throwable) {
        System.err.println("镜像投喂异常：$throwable")
    } finally {
        runCatching { server.close() }
        reader.close()
    }
}

/**
 * 空闲期间不写 socket，写入失败无法反映浮层已经收工；单独一条线程阻塞在读取上，
 * 读到流结束（-1）即客户端关闭，直接结束进程。协议是单向的，宿主不会收到有效数据。
 */
private fun watchClientClose(client: LocalSocket) {
    Thread({
        val input: InputStream = runCatching { client.inputStream }.getOrElse { return@Thread }
        runCatching { input.read() }
        exitProcess(0)
    }, CLIENT_WATCH_THREAD_NAME).apply { isDaemon = true }.start()
}

private fun streamFrames(client: LocalSocket, reader: ImageReader, width: Int, height: Int) {
    val output: OutputStream = BufferedOutputStream(client.outputStream, OUTPUT_BUFFER_BYTES)
    val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    var pixels = ByteArray(0)
    var lastSentAt = SystemClock.elapsedRealtime()
    while (true) {
        val image = reader.acquireLatestImage()
        if (image == null) {
            Thread.sleep(IDLE_POLL_MS)
            continue
        }
        val plane = image.planes[0]
        val rowStride = plane.rowStride
        val byteCount = plane.buffer.remaining()
        if (pixels.size < byteCount) pixels = ByteArray(byteCount)
        try {
            plane.buffer.get(pixels, 0, byteCount)
        } finally {
            image.close()
        }

        header.clear()
        header.putInt(width)
        header.putInt(height)
        header.putInt(rowStride)
        header.putInt(byteCount)
        val sent = runCatching {
            output.write(header.array())
            output.write(pixels, 0, byteCount)
            output.flush()
        }.isSuccess
        if (!sent) {
            // 客户端已关闭：镜像会话结束
            return
        }

        // 以本次发送时刻为周期起点，睡满帧间隔再取下一帧；基准要在 sleep 之后更新，
        // 否则一次 sleep 要隔一轮才生效，实际帧率会翻倍。
        val wait = lastSentAt + FRAME_INTERVAL_MS - SystemClock.elapsedRealtime()
        if (wait > 0) Thread.sleep(wait)
        lastSentAt = SystemClock.elapsedRealtime()
    }
}

private const val ARGUMENT_COUNT = 4
private const val HEADER_BYTES = 16
private const val MAX_IMAGES = 2
private const val IDLE_POLL_MS = 20L
private const val FRAME_INTERVAL_MS = 33L
private const val CONNECT_TIMEOUT_MS = 10_000L
private const val OUTPUT_BUFFER_BYTES = 64 * 1024
private const val MIRROR_NAME = "eta-virtual-screen-mirror"
private const val ACCEPT_WATCH_THREAD_NAME = "eta-mirror-accept-watch"
private const val CLIENT_WATCH_THREAD_NAME = "eta-mirror-client-watch"
