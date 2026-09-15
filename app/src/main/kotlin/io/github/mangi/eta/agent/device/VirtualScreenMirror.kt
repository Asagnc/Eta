package io.github.mangi.eta.agent.device

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AndroidAgentLogger
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import top.yukonga.miuix.kmp.basic.CardDefaults

/**
 * 副屏镜像浮层：在主屏的系统浮层上实时显示副屏画面。
 *
 * 窗口必须由应用进程持有——root 裸进程会被 WindowManagerService 拒绝；画面必须由 root 产生
 * ——镜像 display 需要 signature 级的 CAPTURE_VIDEO_OUTPUT 权限。两侧因此分工：宿主
 * [VirtualScreenMirrorHost] 把画面写进抽象本地 socket，这里读出来画到浮层上。
 *
 * 浮层跟随副屏的存在出现与消失，是否显示由 `agent_virtual_screen_mirror` 开关决定。
 * 全部窗口状态只在主线程读写，工具线程只负责把请求投递过来。
 */
internal object VirtualScreenMirror {
    private val mainHandler = Handler(Looper.getMainLooper())

    private var session: Session? = null

    /** 已拉起宿主但还没连上 socket 的中间状态。 */
    private var pending: PendingStart? = null

    private var preferenceListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    /** 副屏创建成功后调用：按设置与权限决定是否显示镜像浮层。 */
    fun onVirtualScreenCreated(context: Context, displayId: Int, sourceWidth: Int, sourceHeight: Int) {
        val appContext = context.applicationContext
        mainHandler.post { start(appContext, displayId, sourceWidth, sourceHeight) }
    }

    /** 副屏销毁后调用：收起镜像浮层并结束宿主进程。 */
    fun onVirtualScreenDestroyed() {
        mainHandler.post { stop() }
    }

    private fun start(context: Context, displayId: Int, sourceWidth: Int, sourceHeight: Int) {
        stop()
        if (!Prefs.isEnabled(Prefs.Keys.AGENT_VIRTUAL_SCREEN_MIRROR)) return
        if (!RootAccess.isGranted) return
        if (sourceWidth <= 0 || sourceHeight <= 0) return

        val accessibility = AgentAccessibilityService.current()
        if (accessibility == null && !Settings.canDrawOverlays(context)) {
            AndroidAgentLogger.warnThrottled("virtual_screen_mirror_overlay_unavailable") {
                "Agent virtual screen mirror skipped: overlay permission is missing"
            }
            return
        }
        val windowContext = accessibility ?: context
        val windowManager = windowContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return

        val metrics = context.resources.displayMetrics
        var width = (metrics.widthPixels * MIRROR_WIDTH_RATIO).toInt()
        var height = (width.toLong() * sourceHeight / sourceWidth).toInt()
        val maxHeight = (metrics.heightPixels * MIRROR_HEIGHT_RATIO).toInt()
        if (height > maxHeight) {
            height = maxHeight
            width = (height.toLong() * sourceWidth / sourceHeight).toInt()
        }
        width = width.coerceAtLeast(MIN_MIRROR_SIZE)
        height = height.coerceAtLeast(MIN_MIRROR_SIZE)

        val socketName = SOCKET_NAME_PREFIX + UUID.randomUUID().toString().replace("-", "")
        val host = startHost(context, displayId, socketName, width, height) ?: return
        val view = MirrorFrameView(windowContext, width, height)
        val params = windowParams(context, width, height, accessibility != null)
        val added = runCatching { windowManager.addView(view, params) }
        if (added.isFailure) {
            AndroidAgentLogger.warnThrottled("virtual_screen_mirror_add_view_failed") {
                "Agent virtual screen mirror addView failed: " +
                    added.exceptionOrNull()?.javaClass?.simpleName
            }
            host.close()
            return
        }
        val dragListener = DragListener(windowManager, params, width, height, metrics)
        view.setOnTouchListener(dragListener)
        pending = PendingStart(host, socketName, view, params, windowManager, width, height)
        registerPreferenceListener()
        connectPending()
    }

    /** 宿主启动要花一点时间，连不上就在主线程上重试，工具线程不必等待。 */
    private fun connectPending() {
        val start = pending ?: return
        val socket = connectHost(start.socketName)
        if (socket == null) {
            if (start.attempts >= CONNECT_ATTEMPTS) {
                AndroidAgentLogger.warnThrottled("virtual_screen_mirror_host_unavailable") {
                    "Agent virtual screen mirror host did not accept a connection: ${start.host.tail()}"
                }
                stop()
                return
            }
            start.attempts += 1
            mainHandler.postDelayed({ connectPending() }, CONNECT_RETRY_MS)
            return
        }
        pending = null
        val started = Session(socket, start.host, start.view, start.params, start.windowManager) { ended ->
            mainHandler.post { handleEnded(ended) }
        }
        session = started
        started.startReading()
        AndroidAgentLogger.info(
            "Agent virtual screen mirror outcome=started size=${start.width}x${start.height}",
        )
    }

    private fun handleEnded(ended: Session) {
        if (session !== ended) return
        AndroidAgentLogger.warnThrottled("virtual_screen_mirror_host_ended") {
            "Agent virtual screen mirror host ended: ${ended.tail()}"
        }
        session = null
        ended.close()
        ended.detach()
    }

    private fun stop() {
        unregisterPreferenceListener()
        pending?.let { start ->
            runCatching { start.host.close() }
            runCatching { start.windowManager.removeView(start.view) }
        }
        pending = null
        val current = session ?: return
        session = null
        current.close()
        current.detach()
        AndroidAgentLogger.info("Agent virtual screen mirror outcome=stopped")
    }

    private fun startHost(
        context: Context,
        displayId: Int,
        socketName: String,
        width: Int,
        height: Int,
    ): HostProcess? {
        val script = "CLASSPATH=${shellQuote(context.applicationInfo.sourceDir)} " +
            "app_process /system/bin $HOST_CLASS $displayId $socketName $width $height"
        val process = runCatching {
            ProcessBuilder("su", "-c", script).redirectErrorStream(true).start()
        }.getOrElse { throwable ->
            AndroidAgentLogger.warnThrottled("virtual_screen_mirror_host_start_failed") {
                "Agent virtual screen mirror host start failed: ${throwable.javaClass.simpleName}"
            }
            return null
        }
        return HostProcess(process)
    }

    private fun connectHost(socketName: String): LocalSocket? {
        val address = LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT)
        val socket = LocalSocket()
        return if (runCatching { socket.connect(address) }.isSuccess) {
            socket
        } else {
            runCatching { socket.close() }
            null
        }
    }

    private fun windowParams(
        context: Context,
        width: Int,
        height: Int,
        accessibilityOverlay: Boolean,
    ): WindowManager.LayoutParams {
        val type = if (accessibilityOverlay) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }
        return WindowManager.LayoutParams(
            width,
            height,
            type,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (INITIAL_MARGIN_DP * context.resources.displayMetrics.density).toInt()
            y = (context.resources.displayMetrics.heightPixels * INITIAL_TOP_RATIO).toInt()
        }
    }

    /** 开关由设置页写入，关掉时立即收起浮层，不必等下一次副屏创建。 */
    private fun registerPreferenceListener() {
        if (preferenceListener != null) return
        val preferences = Prefs.localAgentPreferences() ?: return
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == Prefs.Keys.AGENT_VIRTUAL_SCREEN_MIRROR) {
                mainHandler.post {
                    if (!Prefs.isEnabled(Prefs.Keys.AGENT_VIRTUAL_SCREEN_MIRROR)) stop()
                }
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        preferenceListener = listener
    }

    private fun unregisterPreferenceListener() {
        val listener = preferenceListener ?: return
        preferenceListener = null
        val preferences = Prefs.localAgentPreferences() ?: return
        runCatching { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private class PendingStart(
        val host: HostProcess,
        val socketName: String,
        val view: MirrorFrameView,
        val params: WindowManager.LayoutParams,
        val windowManager: WindowManager,
        val width: Int,
        val height: Int,
        var attempts: Int = 0,
    )

    private class Session(
        private val socket: LocalSocket,
        private val host: HostProcess,
        private val view: MirrorFrameView,
        private val params: WindowManager.LayoutParams,
        private val windowManager: WindowManager,
        private val onEnded: (Session) -> Unit,
    ) {
        @Volatile
        private var closed = false

        fun startReading() {
            Thread({ readFrames() }, READER_THREAD_NAME).apply { isDaemon = true }.start()
        }

        fun tail(): String = host.tail()

        fun close() {
            closed = true
            runCatching { socket.close() }
            host.close()
        }

        fun detach() {
            runCatching { windowManager.removeView(view) }
        }

        private fun readFrames() {
            var pixels = ByteArray(0)
            try {
                val input = DataInputStream(BufferedInputStream(socket.inputStream))
                val header = ByteArray(HEADER_BYTES)
                while (!closed) {
                    input.readFully(header)
                    val fields = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                    val frameWidth = fields.getInt()
                    val frameHeight = fields.getInt()
                    val rowStride = fields.getInt()
                    val byteCount = fields.getInt()
                    if (frameWidth != view.frameWidth ||
                        frameHeight != view.frameHeight ||
                        rowStride < frameWidth * BYTES_PER_PIXEL ||
                        byteCount < rowStride * frameHeight ||
                        byteCount > MAX_FRAME_BYTES
                    ) {
                        throw IOException("镜像帧头部与浮层尺寸不匹配")
                    }
                    if (pixels.size < byteCount) pixels = ByteArray(byteCount)
                    input.readFully(pixels, 0, byteCount)
                    view.submit(pixels, rowStride)
                }
            } catch (exception: IOException) {
                // 会话结束时 socket 已被关闭，读取到此为止
            } finally {
                if (!closed) onEnded(this)
            }
        }
    }

    private class HostProcess(val process: Process) {
        private val output = StringBuilder()

        init {
            // 宿主正常运行时没有输出；这里只保留最近的失败信息，供连接失败时定位原因。
            Thread({
                runCatching {
                    process.inputStream.bufferedReader().forEachLine { line ->
                        synchronized(output) {
                            if (output.length < MAX_HOST_OUTPUT_CHARS) output.append(line).append('\n')
                        }
                    }
                }
            }, HOST_THREAD_NAME).apply { isDaemon = true }.start()
        }

        fun tail(): String = synchronized(output) { output.toString().trim() }

        fun close() {
            runCatching { process.destroy() }
        }
    }

    private class DragListener(
        private val windowManager: WindowManager,
        private val params: WindowManager.LayoutParams,
        private val width: Int,
        private val height: Int,
        private val metrics: DisplayMetrics,
    ) : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var downX = 0f
        private var downY = 0f

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    downX = event.rawX
                    downY = event.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (startX + (event.rawX - downX).toInt())
                        .coerceIn(0, (metrics.widthPixels - width).coerceAtLeast(0))
                    params.y = (startY + (event.rawY - downY).toInt())
                        .coerceIn(0, (metrics.heightPixels - height).coerceAtLeast(0))
                    runCatching { windowManager.updateViewLayout(view, params) }
                }
            }
            return true
        }
    }

    private class MirrorFrameView(
        context: Context,
        val frameWidth: Int,
        val frameHeight: Int,
    ) : View(context) {
        private val lock = Any()
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val destination = Rect(0, 0, frameWidth, frameHeight)
        private var displayed = Bitmap.createBitmap(frameWidth, frameHeight, Bitmap.Config.ARGB_8888)
        private var staging = Bitmap.createBitmap(frameWidth, frameHeight, Bitmap.Config.ARGB_8888)
        private var packed: ByteArray? = null

        init {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = CardDefaults.CornerRadius.value * resources.displayMetrics.density
                setColor(Color.BLACK)
            }
            clipToOutline = true
        }

        /** 写入一帧像素并切换到显示缓冲；由读取线程调用。 */
        fun submit(payload: ByteArray, rowStride: Int) {
            val packedRow = frameWidth * BYTES_PER_PIXEL
            synchronized(lock) {
                val target = staging
                val source = if (rowStride == packedRow) {
                    ByteBuffer.wrap(payload, 0, packedRow * frameHeight)
                } else {
                    // 行字节数大于宽度乘四时先按行压紧，Bitmap 只接受连续像素数据
                    val buffer = packed?.takeIf { it.size >= packedRow * frameHeight }
                        ?: ByteArray(packedRow * frameHeight).also { packed = it }
                    for (row in 0 until frameHeight) {
                        System.arraycopy(payload, row * rowStride, buffer, row * packedRow, packedRow)
                    }
                    ByteBuffer.wrap(buffer, 0, packedRow * frameHeight)
                }
                target.copyPixelsFromBuffer(source)
                staging = displayed
                displayed = target
            }
            postInvalidateOnAnimation()
        }

        override fun onDraw(canvas: Canvas) {
            synchronized(lock) {
                canvas.drawBitmap(displayed, null, destination, paint)
            }
        }
    }

    private const val HEADER_BYTES = 16
    private const val BYTES_PER_PIXEL = 4
    private const val MAX_FRAME_BYTES = 16 * 1024 * 1024
    private const val CONNECT_ATTEMPTS = 25
    private const val CONNECT_RETRY_MS = 200L
    private const val MIRROR_WIDTH_RATIO = 0.55f
    private const val MIRROR_HEIGHT_RATIO = 0.45f
    private const val MIN_MIRROR_SIZE = 240
    private const val INITIAL_MARGIN_DP = 12
    private const val INITIAL_TOP_RATIO = 0.12f
    private const val MAX_HOST_OUTPUT_CHARS = 2_000
    private const val SOCKET_NAME_PREFIX = "eta-mirror-"
    private const val HOST_CLASS = "io.github.mangi.eta.agent.device.VirtualScreenMirrorHost"
    private const val READER_THREAD_NAME = "eta-virtual-screen-mirror"
    private const val HOST_THREAD_NAME = "eta-virtual-screen-mirror-host"
}
