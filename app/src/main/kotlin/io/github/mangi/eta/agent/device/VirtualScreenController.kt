package io.github.mangi.eta.agent.device

import android.content.Context
import io.github.mangi.eta.agent.media.AgentImageCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.core.AgentLogger
import java.io.File
import org.json.JSONObject

/**
 * 系统副屏（overlay display）：创建、销毁、投应用、注入输入与截图。
 *
 * 副屏由系统自行合成，本进程不参与渲染，主屏的前台应用与输入都不受影响。
 * displayId 每次创建都会变化，只在动作发生前即时解析，不接受调用方传入。
 */
internal class VirtualScreenController(
    private val context: Context,
    private val logger: AgentLogger,
    private val root: BoundedRootCommandExecutor,
) {
    fun execute(args: JSONObject): AgentModelClient.ToolResult {
        val action = args.optString("action").trim().lowercase()
        return when (action) {
            "create" -> content(create(args))
            "list" -> content(list())
            "destroy" -> content(destroy())
            "launch" -> content(launch(args))
            "input" -> content(input(args))
            "capture" -> capture()
            else -> content(errorJson("INVALID_ARGUMENT", "不支持的 virtual_screen action：$action"))
        }
    }

    private fun create(args: JSONObject): String {
        val width = args.optInt("width", DEFAULT_WIDTH).coerceIn(MIN_SIZE, MAX_SIZE)
        val height = args.optInt("height", DEFAULT_HEIGHT).coerceIn(MIN_SIZE, MAX_SIZE)
        val dpi = args.optInt("dpi", DEFAULT_DPI).coerceIn(MIN_DPI, MAX_DPI)
        val result = root.execute(
            "settings put global overlay_display_devices ${shellQuote("${width}x$height/$dpi")} || exit 31; " +
                "sleep 2; dumpsys display",
            timeoutMillis = SETUP_TIMEOUT_MS,
        )
        if (!result.ok) return failure("VIRTUAL_SCREEN_CREATE_FAILED", result)
        val display = parseOverlay(result.stdout)
            ?: return errorJson(
                "VIRTUAL_SCREEN_CREATE_FAILED",
                "副屏未出现在系统显示列表中，请确认设备允许叠加显示",
            )
        logger.info("Agent virtual screen action=create outcome=succeeded displayId=${display.displayId}")
        VirtualScreenMirror.onVirtualScreenCreated(context, display.displayId, display.width, display.height)
        return JSONObject()
            .put("ok", true)
            .put("tool", "virtual_screen")
            .put("action", "create")
            .put("display_id", display.displayId)
            .put("width", display.width)
            .put("height", display.height)
            .put("dpi", display.dpi)
            .toString()
    }

    private fun list(): String {
        val result = root.execute("dumpsys display", timeoutMillis = QUERY_TIMEOUT_MS)
        if (!result.ok) return failure("VIRTUAL_SCREEN_QUERY_FAILED", result)
        val display = parseOverlay(result.stdout)
        return JSONObject()
            .put("ok", true)
            .put("tool", "virtual_screen")
            .put("action", "list")
            .put("display_id", display?.displayId ?: JSONObject.NULL)
            .put("width", display?.width ?: JSONObject.NULL)
            .put("height", display?.height ?: JSONObject.NULL)
            .put("dpi", display?.dpi ?: JSONObject.NULL)
            .toString()
    }

    private fun destroy(): String {
        val result = root.execute(
            "settings delete global overlay_display_devices || exit 32; sleep 1; dumpsys display",
            timeoutMillis = SETUP_TIMEOUT_MS,
        )
        if (!result.ok) return failure("VIRTUAL_SCREEN_DESTROY_FAILED", result)
        val remaining = parseOverlay(result.stdout)
        logger.info("Agent virtual screen action=destroy outcome=succeeded remaining=${remaining != null}")
        VirtualScreenMirror.onVirtualScreenDestroyed()
        return JSONObject()
            .put("ok", true)
            .put("tool", "virtual_screen")
            .put("action", "destroy")
            .put("display_id", remaining?.displayId ?: JSONObject.NULL)
            .toString()
    }

    private fun launch(args: JSONObject): String {
        val packageName = args.optString("package").trim()
        if (packageName.isEmpty()) return errorJson("INVALID_ARGUMENT", "launch 需要提供 package")
        val display = current()
            ?: return errorJson("VIRTUAL_SCREEN_MISSING", "尚未创建副屏，请先执行 action=create")
        val activity = args.optString("activity").trim()
        val component = when {
            activity.isEmpty() -> resolveLauncherActivity(packageName)
            activity.contains('/') -> activity
            else -> "$packageName/$activity"
        }
        if (component.isEmpty()) {
            return errorJson("VIRTUAL_SCREEN_LAUNCH_FAILED", "无法解析 $packageName 的启动入口")
        }
        val result = root.execute(
            "am start --display ${display.displayId} -n ${shellQuote(component)}",
            timeoutMillis = SETUP_TIMEOUT_MS,
        )
        val output = (result.stdout + result.stderr).trim()
        if (!result.ok || output.contains("Error") || output.contains("Exception")) {
            return errorJson("VIRTUAL_SCREEN_LAUNCH_FAILED", output.ifBlank { "exit=${result.exitCode}" })
        }
        logger.info(
            "Agent virtual screen action=launch outcome=succeeded " +
                "displayId=${display.displayId} package=$packageName",
        )
        return JSONObject()
            .put("ok", true)
            .put("tool", "virtual_screen")
            .put("action", "launch")
            .put("display_id", display.displayId)
            .put("component", component)
            .toString()
    }

    private fun input(args: JSONObject): String {
        val display = current()
            ?: return errorJson("VIRTUAL_SCREEN_MISSING", "尚未创建副屏，请先执行 action=create")
        val command = when (val type = args.optString("type").trim().lowercase()) {
            "tap" -> {
                val x = args.optInt("x", Int.MIN_VALUE)
                val y = args.optInt("y", Int.MIN_VALUE)
                if (x == Int.MIN_VALUE || y == Int.MIN_VALUE) {
                    return errorJson("INVALID_ARGUMENT", "tap 需要 x 与 y")
                }
                "input -d ${display.displayId} tap $x $y"
            }
            "swipe" -> {
                val points = listOf("x1", "y1", "x2", "y2").map { args.optInt(it, Int.MIN_VALUE) }
                if (points.any { it == Int.MIN_VALUE }) {
                    return errorJson("INVALID_ARGUMENT", "swipe 需要 x1、y1、x2、y2")
                }
                val duration = args.optInt("duration_ms", DEFAULT_SWIPE_MS).coerceIn(MIN_SWIPE_MS, MAX_SWIPE_MS)
                "input -d ${display.displayId} swipe ${points.joinToString(" ")} $duration"
            }
            "key" -> {
                val event = normalizeKeyEvent(args.optString("key"))
                    ?: return errorJson("INVALID_ARGUMENT", "key 需要形如 KEYCODE_BACK 的按键名")
                "input -d ${display.displayId} keyevent $event"
            }
            "text" -> {
                val value = args.optString("text")
                if (value.isEmpty()) return errorJson("INVALID_ARGUMENT", "text 需要 text")
                if (value.length > MAX_TEXT_CHARS) {
                    return errorJson("INVALID_ARGUMENT", "text 最多 $MAX_TEXT_CHARS 个字符")
                }
                "input -d ${display.displayId} text ${shellQuote(value)}"
            }
            else -> return errorJson("INVALID_ARGUMENT", "不支持的 input type：$type")
        }
        val result = root.execute(command, timeoutMillis = QUERY_TIMEOUT_MS)
        if (!result.ok) return failure("VIRTUAL_SCREEN_INPUT_FAILED", result)
        return JSONObject()
            .put("ok", true)
            .put("tool", "virtual_screen")
            .put("action", "input")
            .put("type", args.optString("type").lowercase())
            .put("display_id", display.displayId)
            .toString()
    }

    private fun capture(): AgentModelClient.ToolResult {
        val display = current()
            ?: return content(errorJson("VIRTUAL_SCREEN_MISSING", "尚未创建副屏，请先执行 action=create"))
        val identifier = surfaceFlingerDisplayId()
            ?: return content(errorJson("VIRTUAL_SCREEN_MISSING", "无法定位副屏的显示标识"))
        val directory = File(context.cacheDir, CAPTURE_DIRECTORY).apply { mkdirs() }
        val file = File(directory, "capture-${System.currentTimeMillis()}.png")
        val result = root.execute(
            "screencap -d ${shellQuote(identifier)} -p ${shellQuote(file.absolutePath)}",
            timeoutMillis = SETUP_TIMEOUT_MS,
        )
        if (!result.ok || !file.isFile) {
            file.delete()
            return content(failure("VIRTUAL_SCREEN_CAPTURE_FAILED", result))
        }
        val image = AgentImageCodec.fromToolFile(file, "tool_virtual_screen")
        file.delete()
        if (image == null) {
            return content(errorJson("VIRTUAL_SCREEN_CAPTURE_FAILED", "截取的画面不是可识别的图片"))
        }
        logger.info(
            "Agent virtual screen action=capture outcome=succeeded " +
                "displayId=${display.displayId} bytes=${image.bytes}",
        )
        return AgentModelClient.ToolResult(
            content = JSONObject()
                .put("ok", true)
                .put("tool", "virtual_screen")
                .put("action", "capture")
                .put("display_id", display.displayId)
                .put("width", display.width)
                .put("height", display.height)
                .toString(),
            images = listOf(image),
        )
    }

    /** 操作前即时取一次副屏，避免持有已被系统回收的 displayId。 */
    private fun current(): OverlayDisplay? {
        val result = root.execute("dumpsys display", timeoutMillis = QUERY_TIMEOUT_MS)
        if (!result.ok) return null
        return parseOverlay(result.stdout)
    }

    private fun resolveLauncherActivity(packageName: String): String {
        val result = root.execute(
            "cmd package resolve-activity --brief -c android.intent.category.LAUNCHER " +
                "${shellQuote(packageName)} 2>/dev/null | grep / | tail -n 1",
            timeoutMillis = QUERY_TIMEOUT_MS,
        )
        return if (result.ok) result.stdout.trim() else ""
    }

    /**
     * 副屏在 SurfaceFlinger 里只有大数 display id 可供 screencap 使用；
     * 它与 dumpsys display 的小数 displayId 不是同一个值，必须分别解析。
     */
    private fun surfaceFlingerDisplayId(): String? {
        val result = root.execute(
            "dumpsys SurfaceFlinger --display-id | grep 'Virtual display' | head -n 1 | awk '{print \$2}'",
            timeoutMillis = QUERY_TIMEOUT_MS,
        )
        if (!result.ok) return null
        return result.stdout.trim().takeIf { it.isNotEmpty() }
    }

    private fun parseOverlay(output: String): OverlayDisplay? {
        val displayId = OVERLAY_VIEWPORT.find(output)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val device = OVERLAY_DEVICE.find(output)?.groupValues ?: return null
        return OverlayDisplay(
            displayId = displayId,
            width = device[1].toInt(),
            height = device[2].toInt(),
            dpi = OVERLAY_DPI.find(output)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
        )
    }

    private fun normalizeKeyEvent(raw: String): String? {
        val trimmed = raw.trim().uppercase()
        if (trimmed.isEmpty()) return null
        val name = if (trimmed.startsWith("KEYCODE_")) trimmed else "KEYCODE_$trimmed"
        return name.takeIf { KEY_EVENT.matches(it) }
    }

    private fun content(value: String): AgentModelClient.ToolResult =
        AgentModelClient.ToolResult(content = value)

    private fun failure(code: String, result: BoundedRootCommandExecutor.Result): String =
        errorJson(
            code,
            result.stderr.ifBlank { "exit=${result.exitCode}${if (result.timedOut) " timeout" else ""}" },
        )

    private fun errorJson(code: String, message: String): String = JSONObject()
        .put("ok", false)
        .put("code", code)
        .put("message", message.take(300))
        .toString()

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private data class OverlayDisplay(
        val displayId: Int,
        val width: Int,
        val height: Int,
        val dpi: Int,
    )

    private companion object {
        const val QUERY_TIMEOUT_MS = 8_000L
        const val SETUP_TIMEOUT_MS = 15_000L

        const val DEFAULT_WIDTH = 1280
        const val DEFAULT_HEIGHT = 720
        const val DEFAULT_DPI = 160
        const val MIN_SIZE = 320
        const val MAX_SIZE = 2560
        const val MIN_DPI = 72
        const val MAX_DPI = 640

        const val DEFAULT_SWIPE_MS = 300
        const val MIN_SWIPE_MS = 100
        const val MAX_SWIPE_MS = 2_000
        const val MAX_TEXT_CHARS = 200

        const val CAPTURE_DIRECTORY = "virtual-screen"

        val KEY_EVENT = Regex("""KEYCODE_[A-Z0-9_]+""")

        /** mViewports 的 displayId 与 overlay 的 uniqueId 同现一行，且不随系统语言变化。 */
        val OVERLAY_VIEWPORT = Regex("""displayId=(\d+), uniqueId='overlay:""")

        /** DisplayDeviceInfo 行提供副屏的实际分辨率与密度。 */
        val OVERLAY_DEVICE = Regex("""uniqueId="overlay:[^"]*", (\d+) x (\d+)""")
        val OVERLAY_DPI = Regex("""uniqueId="overlay:[^"]*"[^\n]*?density (\d+)""")
    }
}
