package io.github.smalltailqwq.shallowdrop.ui.sider

import io.github.smalltailqwq.shallowdrop.core.model.SessionSummary
import java.time.Instant
import java.time.ZoneId

/*
 * 会话按时间分组，对照 src/services/chat/sessionGroups.ts：
 * 今天 / 昨天 / 7 天内 / 30 天内 / 按年月。相邻且同组的会话合并为一组，组顺序沿用输入顺序。
 */

sealed interface SessionGroupKey {
    data object Today : SessionGroupKey

    data object Yesterday : SessionGroupKey

    data object Days7 : SessionGroupKey

    data object Days30 : SessionGroupKey

    /** 更早的会话按年月归组，month 为 1–12 */
    data class Month(val year: Int, val month: Int) : SessionGroupKey
}

data class SessionGroup(val key: SessionGroupKey, val sessions: List<SessionSummary>)

private const val DAY = 86_400_000L

/** 与 Web 版一致：以 updatedAt 所在日 0 点到 now 的整天数判断。 */
fun groupKeyOf(updatedAt: Long, now: Long, zone: ZoneId): SessionGroupKey {
    val date = Instant.ofEpochMilli(updatedAt).atZone(zone).toLocalDate()
    val startOfDay = date.atStartOfDay(zone).toInstant().toEpochMilli()
    val diff = Math.floorDiv(now - startOfDay, DAY)
    return when {
        diff <= 0 -> SessionGroupKey.Today
        diff <= 1 -> SessionGroupKey.Yesterday
        diff < 7 -> SessionGroupKey.Days7
        diff < 30 -> SessionGroupKey.Days30
        else -> SessionGroupKey.Month(date.year, date.monthValue)
    }
}

/** 按标题筛选会话（忽略大小写与首尾空白），保留分组与顺序；空查询返回原列表。 */
fun filterSessionGroups(groups: List<SessionGroup>, query: String, untitled: String): List<SessionGroup> {
    val needle = query.trim()
    if (needle.isEmpty()) return groups
    return groups.mapNotNull { group ->
        val matched = group.sessions.filter { it.title.ifEmpty { untitled }.contains(needle, ignoreCase = true) }
        if (matched.isEmpty()) null else group.copy(sessions = matched)
    }
}

fun groupSessions(
    sessions: List<SessionSummary>,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): List<SessionGroup> {
    val groups = mutableListOf<Pair<SessionGroupKey, MutableList<SessionSummary>>>()
    for (session in sessions) {
        val key = groupKeyOf(session.updatedAt, now, zone)
        val last = groups.lastOrNull()
        if (last?.first == key) {
            last.second += session
        } else {
            groups += key to mutableListOf(session)
        }
    }
    return groups.map { (key, list) -> SessionGroup(key, list) }
}
