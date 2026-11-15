package io.github.smalltailqwq.shallowdrop.ui.sider

import io.github.smalltailqwq.shallowdrop.core.model.SessionSummary
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionGroupsTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    private fun at(text: String) = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    private fun session(id: String, updatedAt: Long) = SessionSummary(id, id, updatedAt, updatedAt)

    private val now = at("2026-11-15T10:00:00")

    @Test
    fun boundariesMatchWebVersion() {
        assertEquals(SessionGroupKey.Today, groupKeyOf(at("2026-11-15T00:00:00"), now, zone))
        // 未来时间（时钟偏差）也算今天
        assertEquals(SessionGroupKey.Today, groupKeyOf(at("2026-11-16T09:00:00"), now, zone))
        assertEquals(SessionGroupKey.Yesterday, groupKeyOf(at("2026-11-14T23:59:59"), now, zone))
        assertEquals(SessionGroupKey.Yesterday, groupKeyOf(at("2026-11-14T00:00:00"), now, zone))
        assertEquals(SessionGroupKey.Days7, groupKeyOf(at("2026-11-13T12:00:00"), now, zone))
        assertEquals(SessionGroupKey.Days7, groupKeyOf(at("2026-11-09T00:00:00"), now, zone))
        assertEquals(SessionGroupKey.Days30, groupKeyOf(at("2026-11-08T23:00:00"), now, zone))
        assertEquals(SessionGroupKey.Days30, groupKeyOf(at("2026-10-17T00:00:00"), now, zone))
        assertEquals(SessionGroupKey.Month(2026, 10), groupKeyOf(at("2026-10-16T08:00:00"), now, zone))
        assertEquals(SessionGroupKey.Month(2025, 1), groupKeyOf(at("2025-01-31T08:00:00"), now, zone))
    }

    @Test
    fun adjacentSessionsMergeInInputOrder() {
        val sessions = listOf(
            session("a", at("2026-11-15T09:00:00")),
            session("b", at("2026-11-15T01:00:00")),
            session("c", at("2026-11-14T12:00:00")),
            session("d", at("2026-09-03T12:00:00")),
            session("e", at("2026-09-01T12:00:00")),
            session("f", at("2025-12-01T12:00:00")),
        )
        val groups = groupSessions(sessions, now, zone)
        assertEquals(
            listOf(
                SessionGroupKey.Today,
                SessionGroupKey.Yesterday,
                SessionGroupKey.Month(2026, 9),
                SessionGroupKey.Month(2025, 12),
            ),
            groups.map { it.key },
        )
        assertEquals(listOf("a", "b"), groups[0].sessions.map { it.id })
        assertEquals(listOf("d", "e"), groups[2].sessions.map { it.id })
    }

    @Test
    fun emptyInputYieldsNoGroups() {
        assertEquals(emptyList<SessionGroup>(), groupSessions(emptyList(), now, zone))
    }
}
