package io.github.mangi.eta.agent.eval

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 评测报告的落盘位置与读写。
 *
 * 放在 App 私有目录而不是共享存储：报告包含任务 prompt 与运行细节，而它的用途只是
 * “和上一轮比一比”，不需要对外可见。文件名用开始时间，天然按时间排序。
 */
internal class AgentEvalReportStore(context: Context) {
    private val root: File = File(context.filesDir, "evals").apply { mkdirs() }

    /** 任务集覆盖：私有目录里放了 `evals/tasks.json` 就优先用它。 */
    fun taskSetFile(): File = File(root, "tasks.json")

    /** 单个任务的过程痕迹：每次工具调用一行，用来回答“轮次花在哪条链路上”。 */
    fun saveTrace(taskId: String, lines: List<String>): File? {
        if (lines.isEmpty()) return null
        val dir = File(root, "traces").apply { mkdirs() }
        return File(dir, "${taskId.replace('/', '_')}.txt")
            .also { file -> file.writeText(lines.joinToString("\n")) }
    }

    fun save(report: AgentEvalReport): File {
        val file = File(root, "report-${report.startedAt}.json")
        file.writeText(report.toJson().toString())
        return file
    }

    fun recent(limit: Int = 10): List<AgentEvalReport> = reportFiles()
        .take(limit)
        .mapNotNull { file -> runCatching { AgentEvalReport.fromJson(JSONObject(file.readText())) }.getOrNull() }

    fun latest(): AgentEvalReport? = recent(1).firstOrNull()

    fun prune(keep: Int = 20) {
        reportFiles().drop(keep).forEach { file -> runCatching { file.delete() } }
    }

    private fun reportFiles(): List<File> = (root.listFiles() ?: emptyArray())
        .filter { it.isFile && it.name.startsWith("report-") && it.name.endsWith(".json") }
        .sortedByDescending { it.name }
}
