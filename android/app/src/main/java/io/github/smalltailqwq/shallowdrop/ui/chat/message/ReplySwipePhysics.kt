package io.github.smalltailqwq.shallowdrop.ui.chat.message

/** 横滑重新生成的手感计算，与 Compose 无关，便于单元测试。距离单位均为 px。 */
internal object ReplySwipePhysics {
    /** 内容最大位移相对触发距离的比例；越过阈值时内容约移动到它的一半 */
    const val LIMIT_RATIO = 0.9f

    /**
     * 橡皮筋阻尼：起手时 1:1 跟手，越往外越沉，位移永远不超过 [limit]。
     * f(d) = limit·d / (d + limit)，f'(0) = 1。
     */
    fun rubberBand(distance: Float, limit: Float): Float =
        if (distance <= 0f || limit <= 0f) 0f else limit * distance / (distance + limit)

    /** 内容随手指距离的可见位移（不含方向） */
    fun visualOffset(distance: Float, threshold: Float): Float = rubberBand(distance, threshold * LIMIT_RATIO)

    fun progress(distance: Float, threshold: Float): Float =
        if (threshold <= 0f) 1f else (distance / threshold).coerceIn(0f, 1f)

    /** 起手点是否落在滑动起始侧的边缘死区内；sign > 0 表示从左向右滑 */
    fun inDeadZone(downX: Float, width: Float, deadZone: Float, sign: Float): Boolean =
        if (sign > 0) downX < deadZone else downX > width - deadZone
}

/** 一次手势内的阈值震动：首次越过阈值时返回 true，之后来回越过都不再重复。 */
internal class ThresholdLatch {
    var armed = false
        private set
    private var fired = false

    /** 更新当前距离；返回是否应该在这一刻震动 */
    fun update(distance: Float, threshold: Float): Boolean {
        armed = distance >= threshold
        if (armed && !fired) {
            fired = true
            return true
        }
        return false
    }

    fun reset() {
        armed = false
        fired = false
    }
}
