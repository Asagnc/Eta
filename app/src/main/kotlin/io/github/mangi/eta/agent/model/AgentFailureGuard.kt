package io.github.mangi.eta.agent.model

/**
 * 连续失败模式的检测。
 *
 * 模型有时会拿同一个工具按同样的参数反复试，每次都撞同一堵墙：错误码和消息一模一样，却还在原地重试。
 * 这是可以确定的外部反馈（错误来自真实执行，不是模型自己的猜测），所以在运行中就该提醒它换做法，
 * 而不是等运行结束再写一句总结给用户看。
 *
 * 判断范围是"同一工具、同一错误签名"的连续出现：签名变了说明换了思路，计数立刻重置。
 */
internal class AgentFailureGuard(private val threshold: Int = DEFAULT_THRESHOLD) {
    private var lastSignature: String? = null
    private var streak = 0

    /**
     * 记录一次失败，返回当前连续次数。
     * 达到阈值时提醒一次，之后每再满一个阈值再提醒，避免只提醒一次后继续无效重试。
     */
    fun observe(signature: String): Int {
        streak = if (signature == lastSignature) streak + 1 else 1
        lastSignature = signature
        return streak
    }

    /** 成功后调用：断了连续失败，计数归零。 */
    fun reset() {
        lastSignature = null
        streak = 0
    }

    fun shouldNudge(streak: Int): Boolean = streak >= threshold && streak % threshold == 0

    companion object {
        /** 连续两次同样的失败就值得提醒：一次可能是偶发，两次说明原样重试没有意义。 */
        const val DEFAULT_THRESHOLD = 2
    }
}
