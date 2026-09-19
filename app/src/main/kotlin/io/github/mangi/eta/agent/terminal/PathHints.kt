package io.github.mangi.eta.agent.terminal

import java.io.File

/**
 * 路径不存在时的可操作提示：给出最近的可用父目录及其子项，让调用方一轮就能改对路径，
 * 而不是拿到上游的原始 IO 报错（rg 的 "IO error for operation on ..."、find 的
 * "No such file or directory"）反复换路径试。
 */
internal object PathHints {
    private const val MAX_ENTRIES = 12

    /** 路径存在时返回 null。 */
    fun missingPathMessage(path: File): String? {
        if (path.exists()) return null
        var parent = path.parentFile
        while (parent != null && !parent.exists()) parent = parent.parentFile
        val message = StringBuilder("路径不存在：").append(path.path)
        if (parent == null) return message.toString()
        message.append("。最近可用的是 ").append(parent.path)
        val entries = parent.listFiles()?.sortedBy { it.name }?.take(MAX_ENTRIES).orEmpty()
        if (entries.isNotEmpty()) {
            message.append("，其下：")
            message.append(entries.joinToString("、") { it.name + if (it.isDirectory) "/" else "" })
        }
        return message.toString()
    }
}
