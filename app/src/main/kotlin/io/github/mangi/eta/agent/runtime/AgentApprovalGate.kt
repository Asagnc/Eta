package io.github.mangi.eta.agent.runtime

import android.os.Message
import android.os.Messenger
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * ask 档的执行前确认门。
 *
 * 命中高风险工具时向界面发一次确认请求并挂起当前工具调用，用户答复或超时后继续。
 * 界面侧按自己是否在前台决定给用户 90 秒还是 10 分钟，这里只保留一个兜底上限，
 * 避免 run 被永久挂住。
 *
 * 同类合并：同一个 groupKey（工具 + 参数里的路径前缀）的并发请求共享一次确认，
 * 用户只看到一次提示，点一次就放行这一批同类操作。
 */
class AgentApprovalGate {

    private class Pending(
        val id: String,
        val latch: CountDownLatch,
        val answer: AtomicReference<Boolean> = AtomicReference(null),
    )

    private val groups = ConcurrentHashMap<String, Pending>()
    private val byId = ConcurrentHashMap<String, Pending>()

    /** 同一次 run 内已经放行过的 groupKey，避免同类操作反复弹窗。 */
    private val granted = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var host: Messenger? = null

    fun attach(messenger: Messenger?) {
        host = messenger
    }

    fun isGranted(key: String): Boolean = key in granted

    fun remember(key: String) {
        granted.add(key)
    }

    fun clear() {
        granted.clear()
        groups.clear()
        byId.clear()
        host = null
    }

    /** 请求确认；返回 true 表示这次执行被允许。 */
    fun request(toolName: String, groupKey: String, maxWaitMillis: Long): Boolean {
        if (groupKey in granted) return true
        val entry = groups.computeIfAbsent(groupKey) { key ->
            val pending = Pending(UUID.randomUUID().toString(), CountDownLatch(1))
            byId[pending.id] = pending
            val messenger = host
            val sent = messenger != null && runCatching {
                val message = Message.obtain(null, AgentRuntimeWire.MSG_APPROVAL_REQUEST)
                message.data = AgentRuntimeWire.approvalRequestBundle(
                    AgentRuntimeWire.ApprovalRequest(
                        id = pending.id,
                        tool = toolName,
                        summary = key,
                        count = 1,
                    ),
                )
                messenger.send(message)
            }.isSuccess
            if (!sent) {
                pending.answer.set(false)
                pending.latch.countDown()
            }
            pending
        }
        val answered = runCatching { entry.latch.await(maxWaitMillis, TimeUnit.MILLISECONDS) }
            .getOrDefault(false)
        val allowed = answered && entry.answer.get() == true
        groups.remove(groupKey, entry)
        byId.remove(entry.id)
        if (allowed) granted.add(groupKey)
        return allowed
    }

    /** 界面回填用户的选择。 */
    fun resolve(id: String, allowed: Boolean) {
        val pending = byId[id] ?: return
        pending.answer.set(allowed)
        pending.latch.countDown()
    }

    companion object {
        /** 兜底上限：界面没回话时，最久等这么久就按拒绝处理。 */
        const val MAX_WAIT_MILLIS = 11 * 60 * 1000L
    }
}
