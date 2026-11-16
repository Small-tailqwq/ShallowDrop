package io.github.smalltailqwq.shallowdrop.domain.chat

import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import io.github.smalltailqwq.shallowdrop.App
import io.github.smalltailqwq.shallowdrop.core.contract.ChatApi
import io.github.smalltailqwq.shallowdrop.core.contract.SendOptions
import io.github.smalltailqwq.shallowdrop.core.model.*
import java.io.File
import java.util.Collections
import kotlin.random.Random
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** 显式传入 liveCompaction=true 才调用设备中配置的模型接口，并产生 API 用量。 */
class CompactionReliabilityTest {
    @Test fun fixedCorpusRecallAndCheckpoint() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveCompaction") == "true")
        val context = instrumentation.targetContext
        val container = (context.applicationContext as App).container
        val settings = container.settingsRepository
        withTimeout(5000) { settings.loaded.first { it } }
        check(settings.settings.value.hasApiKey) { "请先在模拟器 App 中配置 API Key" }
        val thresholds = CompactionThresholds(45, 65)
        settings.setCompactionSettings(thresholds, enabled = true)
        withTimeout(5000) { settings.settings.first { it.compactionThresholds == thresholds } }
        val model = settings.settings.value.model
        val output = File(context.filesDir, "compaction-test").apply { mkdirs() }
        fun stage(message: String) {
            output.resolve("stage.txt").writeText(message)
            instrumentation.sendStatus(2, Bundle().apply { putString("stream", "$message\n") })
        }
        val fixture = corpus(model)
        val question = Message.User("probe", System.currentTimeMillis(), content =
            "根据此前档案，给出 A01 至 A35 每本账册最新确认的封印代码。只输出一个 JSON 对象，键为 A01 等编号，值为完整代码字符串；没有明确记录的用 UNKNOWN。不要推算代码。")
        val records = Collections.synchronizedList(mutableListOf<JsonObject>())
        val api = object : ChatApi by container.chatApi {
            override fun streamMessage(apiKey: String, request: CreateMessageRequest): Flow<MessageStreamEvent> = flow {
                val began = SystemClock.elapsedRealtime()
                var usage = MessageUsage()
                var stopped = false
                emitAll(container.chatApi.streamMessage(apiKey, request).onEach { event ->
                    when (event) {
                        is MessageStreamEvent.MessageStart -> event.usage?.let { usage = it }
                        is MessageStreamEvent.MessageDelta -> event.usage?.let { usage = usage.copy(outputTokens = it.outputTokens) }
                        is MessageStreamEvent.MessageStop -> stopped = true
                        else -> Unit
                    }
                })
                records += buildJsonObject {
                    put("summary", request.maxTokens == COMPACTION_MAX_OUTPUT_TOKENS)
                    put("durationMs", SystemClock.elapsedRealtime() - began)
                    put("inputTokens", usage.inputTokens + usage.cacheReadInputTokens + usage.cacheCreationInputTokens)
                    put("outputTokens", usage.outputTokens)
                    put("completed", stopped)
                    put("hasSummary", request.messages.filterIsInstance<MessageParam.User>().any { message ->
                        message.content.filterIsInstance<UserContentParam.Text>().any { it.text.contains("<conversation_summary>") }
                    })
                }
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            withTimeout(900_000) {
                stage("固定样本：${fixture.history.size / 2} 轮，估算 ${activeContextTokens(fixture.history, model)} token；先请求未压缩基线")
                var baseline = Message.Assistant("baseline", 0, status = ReplyStatus.Pending)
                val indexes = HashMap<Int, Int>()
                api.streamMessage(settings.apiKey().orEmpty(), CreateMessageRequest(
                    model, ChatEngine.SYSTEM_PROMPT, toRequestMessages(fixture.history, question), false, emptyList(), maxTokens = 2048,
                )).collect { baseline = applyEvent(baseline, it, System.currentTimeMillis(), indexes) }
                check(baseline.stopReason == StopReason.EndTurn) { "基线未完整生成" }
                output.resolve("baseline.txt").writeText(replyText(baseline))
                // 基线问题和答案不写入测试会话，避免摘要提前获知答案。
                val sessionId = "compaction-probe-${System.currentTimeMillis()}"
                container.sessionStore.save(Session(sessionId, "压缩可靠性 · 固定样本", System.currentTimeMillis(),
                    System.currentTimeMillis(), fixture.history, fixture.history.first().id))
                fun engine() = ChatEngine(api, container.sessionStore, container.attachmentStore, settings, scope,
                    resolveLocation = { _, _ -> "" })
                val engine = engine()
                stage("基线完成；使用 45% / 65% 设置，等待实际摘要后回答同一组问题")
                engine.send(sessionId, question.content, emptyList(), SendOptions(false, false, model))
                engine.generating.first { sessionId !in it }
                val stored = container.sessionStore.load(sessionId)!!
                val answer = visibleMessages(stored).last() as Message.Assistant
                check(answer.status == ReplyStatus.Done) { "压缩后回答失败：${answer.error}" }
                val summary = stored.messages.filterIsInstance<Message.Assistant>().mapNotNull { it.contextSummary }.single()
                assertEquals(summary.sourceHash, answer.contextSummaryId)
                val original = stored.messages.take(fixture.history.size).mapIndexed { index, message ->
                    when (message) {
                        is Message.Assistant -> message.copy(selectedChildId = fixture.history[index].selectedChildId, contextSummary = null)
                        is Message.User -> message
                    }
                }
                assertEquals(fixture.history, original)
                output.resolve("summary.txt").writeText(summary.text)
                output.resolve("compacted.txt").writeText(replyText(answer))
                stage("摘要已采用且原始消息完整；重新创建引擎验证持久化检查点")
                val reloaded = engine()
                reloaded.send(sessionId, "仅回复：检查点已恢复", emptyList(), SendOptions(false, false, model))
                reloaded.generating.first { sessionId !in it }
                val resumed = container.sessionStore.load(sessionId)!!
                assertEquals(summary.sourceHash, (visibleMessages(resumed).last() as Message.Assistant).contextSummaryId)
                assertEquals(1, records.count { it["summary"]?.jsonPrimitive?.boolean == true })
                val baselineAnswers = answers(replyText(baseline))
                val compactedAnswers = answers(replyText(answer))
                fun score(actual: Map<String, String>, keys: List<String>) = keys.count { actual[it] == fixture.expected[it] }
                val known = fixture.expected.keys.take(30)
                val unknown = fixture.expected.keys.drop(30)
                val report = buildJsonObject {
                    put("sessionId", sessionId)
                    put("model", model.id)
                    put("backgroundPercent", 45); put("foregroundPercent", 65)
                    put("estimatedOriginalTokens", activeContextTokens(fixture.history, model))
                    put("estimatedSummaryTokens", approximateTokens(summary.text))
                    put("estimatedActiveTokens", activeContextTokens(visibleMessages(stored), model))
                    put("baselineCorrect", score(baselineAnswers, known)); put("compactedCorrect", score(compactedAnswers, known)); put("knownCount", known.size)
                    put("baselineUnknownCorrect", score(baselineAnswers, unknown)); put("compactedUnknownCorrect", score(compactedAnswers, unknown)); put("unknownCount", unknown.size)
                    put("correctedFactsCorrect", score(compactedAnswers, fixture.corrected)); put("correctedFactsCount", fixture.corrected.size)
                    put("earlyCorrect", score(compactedAnswers, known.take(10)))
                    put("middleCorrect", score(compactedAnswers, known.drop(10).take(10)))
                    put("lateCorrect", score(compactedAnswers, known.takeLast(10)))
                    put("originalMessagesIntact", true); put("checkpointReloaded", true)
                    put("requests", JsonArray(records.toList()))
                    put("expected", JsonObject(fixture.expected.mapValues { JsonPrimitive(it.value) }))
                    put("baseline", JsonObject(baselineAnswers.mapValues { JsonPrimitive(it.value) }))
                    put("compacted", JsonObject(compactedAnswers.mapValues { JsonPrimitive(it.value) }))
                }
                output.resolve("result.json").writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), report))
                stage("完成：基线 ${score(baselineAnswers, known)}/30，压缩后 ${score(compactedAnswers, known)}/30，未知 ${score(compactedAnswers, unknown)}/5")
            }
        } finally { scope.cancel() }
    }

    @Test fun repeatedCorpusRecall() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveCompaction") == "true")
        val context = instrumentation.targetContext
        val container = (context.applicationContext as App).container
        val settings = container.settingsRepository
        withTimeout(5000) { settings.loaded.first { it } }
        check(settings.settings.value.hasApiKey) { "请先在模拟器 App 中配置 API Key" }
        val thresholds = CompactionThresholds(45, 65)
        settings.setCompactionSettings(thresholds, enabled = true)
        withTimeout(5000) { settings.settings.first { it.compactionThresholds == thresholds } }
        val model = settings.settings.value.model
        val sessionId = "compaction-multi-${System.currentTimeMillis()}"
        val output = File(context.filesDir, "compaction-multi-test/$sessionId").apply { mkdirs() }
        fun stage(message: String) {
            File(context.filesDir, "compaction-multi-test/stage.txt").writeText("$sessionId\n$message")
            instrumentation.sendStatus(2, Bundle().apply { putString("stream", "$message\n") })
        }
        val fixture = corpus(model)
        val expected = LinkedHashMap(fixture.expected)
        val originalKeys = expected.keys.take(30)
        val stableKeys = originalKeys - fixture.corrected.toSet()
        val unknownKeys = expected.filterValues { it == "UNKNOWN" }.keys.toList()
        val records = Collections.synchronizedList(mutableListOf<JsonObject>())
        val rounds = mutableListOf<JsonObject>()
        val roundNumber = java.util.concurrent.atomic.AtomicInteger(0)
        val phase = java.util.concurrent.atomic.AtomicReference("baseline")
        val previousSummary = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val api = object : ChatApi by container.chatApi {
            override fun streamMessage(apiKey: String, request: CreateMessageRequest): Flow<MessageStreamEvent> = flow {
                val began = SystemClock.elapsedRealtime()
                val round = roundNumber.get()
                val kind = when (request.maxTokens) {
                    COMPACTION_MAX_OUTPUT_TOKENS -> "summary"
                    null -> "acknowledgement"
                    else -> phase.get()
                }
                val texts = request.messages.filterIsInstance<MessageParam.User>().flatMap { it.content.filterIsInstance<UserContentParam.Text>() }
                val includesPrevious = previousSummary.get()?.let { previous -> texts.any { it.text.contains(previous) } } ?: false
                if (round > 1) {
                    check(texts.none { it.text == (fixture.history.first() as Message.User).content }) { "早期原文重新进入请求，无法衡量连续摘要损失" }
                    if (kind == "summary") check(includesPrevious) { "后续压缩没有包含上轮摘要" }
                }
                var usage = MessageUsage()
                var stopped = false
                emitAll(container.chatApi.streamMessage(apiKey, request).onEach { event ->
                    when (event) {
                        is MessageStreamEvent.MessageStart -> event.usage?.let { usage = it }
                        is MessageStreamEvent.MessageDelta -> event.usage?.let { usage = usage.copy(outputTokens = it.outputTokens) }
                        is MessageStreamEvent.MessageStop -> stopped = true
                        else -> Unit
                    }
                })
                records += buildJsonObject {
                    put("round", round); put("kind", kind)
                    put("durationMs", SystemClock.elapsedRealtime() - began)
                    put("inputTokens", usage.inputTokens + usage.cacheReadInputTokens + usage.cacheCreationInputTokens)
                    put("outputTokens", usage.outputTokens); put("completed", stopped)
                    put("includesPreviousSummary", includesPrevious)
                }
            }
        }
        suspend fun probe(history: CompactionContext, prompt: Message.User): Message.Assistant {
            var reply = Message.Assistant("probe", 0, status = ReplyStatus.Pending)
            val indexes = HashMap<Int, Int>()
            api.streamMessage(settings.apiKey().orEmpty(), CreateMessageRequest(
                model, ChatEngine.SYSTEM_PROMPT, history.messages(prompt), false, emptyList(), maxTokens = 2048,
            )).collect { reply = applyEvent(reply, it, System.currentTimeMillis(), indexes) }
            check(reply.stopReason == StopReason.EndTurn) { "评测回答未完整生成" }
            return reply
        }
        val reportJson = Json { prettyPrint = true }
        fun saveReport() {
            output.resolve("result.json").writeText(reportJson.encodeToString(JsonObject.serializer(), buildJsonObject {
                put("sessionId", sessionId); put("model", model.id)
                put("backgroundPercent", 45); put("foregroundPercent", 65)
                put("plannedRounds", 5); put("completedRounds", rounds.size)
                put("probesWrittenToHistory", false)
                put("rounds", JsonArray(rounds.toList())); put("requests", JsonArray(records.toList()))
            }))
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = ChatEngine(api, container.sessionStore, container.attachmentStore, settings, scope, resolveLocation = { _, _ -> "" })
        var session = Session(sessionId, "连续压缩可靠性 · 5 轮", System.currentTimeMillis(), System.currentTimeMillis(), fixture.history, fixture.history.first().id)
        val summaryHashes = mutableSetOf<String>()
        try {
            withTimeout(1_200_000) {
                for (round in 1..5) {
                    roundNumber.set(round)
                    assertEquals(thresholds, settings.settings.value.compactionThresholds)
                    val newKeys = if (round == 1) originalKeys else {
                        val batch = subsequentCorpus(model, round, expected, fixture.corrected)
                        session = appendBranch(session, visibleMessages(session).last().id, batch.history).copy(updatedAt = System.currentTimeMillis())
                        batch.expected.keys.toList()
                    }
                    container.sessionStore.save(session)
                    engine.invalidateCache()
                    val before = visibleMessages(session)
                    val tokensBefore = activeContextTokens(before, model)
                    check(tokensBefore >= CONTEXT_WINDOW_TOKENS * thresholds.foregroundPercent / 100) { "材料不足以触发等待阈值" }
                    val active = compactionContext(before, model)
                    if (round > 1) check(active.summary?.text == previousSummary.get())
                    val knownKeys = expected.filterValues { it != "UNKNOWN" }.keys.toList()
                    val prompt = Message.User("multi-probe-$round", 0, content =
                        "根据此前档案，给出这些账册最新确认的完整封印代码：${(knownKeys + unknownKeys).joinToString("、")}。只输出 JSON 对象，键为账册编号，值为代码字符串；没有明确记录的用 UNKNOWN。不要推算代码。")
                    stage("第 $round/5 轮：压缩前估算 $tokensBefore token，评测 ${knownKeys.size} 条事实")
                    phase.set("baseline")
                    val baseline = probe(active, prompt)
                    output.resolve("round-$round-baseline.txt").writeText(replyText(baseline))
                    // 只有简短确认进入会话；评测问题和回答始终在会话之外。
                    engine.send(sessionId, "本批档案已全部提供，请仅回复：已记录。", emptyList(), SendOptions(false, false, model))
                    engine.generating.first { sessionId !in it }
                    session = container.sessionStore.load(sessionId)!!
                    val after = visibleMessages(session)
                    val acknowledgement = after.last() as Message.Assistant
                    check(acknowledgement.status == ReplyStatus.Done) { "第 $round 轮压缩失败：${acknowledgement.error}" }
                    assertEquals(contextFingerprint(before), contextFingerprint(after.take(before.size)))
                    assertEquals(before.size + 2, after.size)
                    val compacted = compactionContext(after, model)
                    val summary = checkNotNull(compacted.summary)
                    assertTrue("每轮必须采用新摘要", summaryHashes.add(summary.sourceHash))
                    assertEquals(summary.sourceHash, acknowledgement.contextSummaryId)
                    assertEquals(round, records.count { it["kind"]?.jsonPrimitive?.content == "summary" })
                    output.resolve("round-$round-summary.txt").writeText(summary.text)
                    phase.set("recall")
                    val answer = probe(compacted, prompt)
                    output.resolve("round-$round-recall.txt").writeText(replyText(answer))
                    val baselineAnswers = answers(replyText(baseline))
                    val recalled = answers(replyText(answer))
                    fun score(values: Map<String, String>, keys: List<String>) = keys.count { values[it] == expected[it] }
                    rounds += buildJsonObject {
                        put("round", round); put("knownCount", knownKeys.size)
                        put("baselineCorrect", score(baselineAnswers, knownKeys)); put("recallCorrect", score(recalled, knownKeys))
                        put("originalCorrect", score(recalled, originalKeys)); put("originalCount", originalKeys.size)
                        put("stableOriginalCorrect", score(recalled, stableKeys)); put("stableOriginalCount", stableKeys.size)
                        put("newCorrect", score(recalled, newKeys)); put("newCount", newKeys.size)
                        put("correctedCorrect", score(recalled, fixture.corrected)); put("correctedCount", fixture.corrected.size)
                        put("unknownCorrect", score(recalled, unknownKeys)); put("unknownCount", unknownKeys.size)
                        put("estimatedTokensBefore", tokensBefore); put("estimatedTokensAfter", activeContextTokens(after, model))
                        put("summaryHash", summary.sourceHash); put("originalMessagesIntact", true); put("historyMessages", after.size)
                        put("expected", JsonObject(expected.mapValues { JsonPrimitive(it.value) }))
                        put("baseline", JsonObject(baselineAnswers.mapValues { JsonPrimitive(it.value) }))
                        put("recall", JsonObject(recalled.mapValues { JsonPrimitive(it.value) }))
                    }
                    saveReport()
                    stage("第 $round/5 轮完成：${score(recalled, knownKeys)}/${knownKeys.size}，未重复提供的原始事实 ${score(recalled, stableKeys)}/27，更正 ${score(recalled, fixture.corrected)}/3，未知 ${score(recalled, unknownKeys)}/5")
                    previousSummary.set(summary.text)
                }
                assertEquals(5, summaryHashes.size)
                stage("连续 5 轮压缩完成：$sessionId")
            }
        } finally {
            scope.cancel()
            saveReport()
        }
    }

    private fun subsequentCorpus(model: ChatModel, round: Int, expected: MutableMap<String, String>, corrected: List<String>): Corpus {
        val random = Random(20261116 + round)
        fun code() = (1..8).map { "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"[random.nextInt(31)] }.joinToString("")
        val prefix = 'A' + round - 1
        val fresh = linkedMapOf<String, String>()
        repeat(10) { fresh["$prefix%02d".format(it + 1)] = code() }
        val updates = corrected.associateWith { code() }
        val positions = fresh.entries.mapIndexed { index, entry -> index * 25 / 9 to entry }.toMap()
        val targetSize = (CONTEXT_WINDOW_TOKENS * 66 / 100 * 4 / 30).toInt()
        val history = buildList {
            repeat(28) { index ->
                val text = buildString {
                    append("Archive batch $round, chapter ${index + 1}.\n")
                    positions[index]?.let { append("The confirmed seal code for ledger ${it.key} is ${it.value}.\n") }
                    if (index == 25) updates.forEach { (key, value) -> append("更正：账册 $key 的旧封印代码已作废，最新确认的代码是 $value。\n") }
                    var scene = 0
                    while (length < targetSize) {
                        append("Journey batch $round, chapter ${index + 1}, scene ${scene++}: The travelers followed the river past the orchard. They watched clouds gather above the hills, then crossed the wooden bridge to the quiet village. The keeper lit a lamp beside the old window while the rain washed the empty road. No ledger code was recorded in this scene.\n")
                    }
                }
                add(Message.User("multi-$round-u$index", index.toLong(), content = text))
                add(Message.Assistant("multi-$round-a$index", index.toLong(), model = model,
                    status = ReplyStatus.Done, stopReason = StopReason.EndTurn, blocks = listOf(ReplyBlock.Text("本章已记录。"))))
            }
        }
        expected.putAll(fresh)
        expected.putAll(updates)
        return Corpus(history, fresh, corrected)
    }

    private fun answers(text: String): Map<String, String> {
        val body = text.substringAfter('{', "").substringBeforeLast('}', "")
        return Json.parseToJsonElement("{$body}").jsonObject.mapValues { it.value.jsonPrimitive.content.trim() }
    }

    private data class Corpus(val history: List<Message>, val expected: LinkedHashMap<String, String>, val corrected: List<String>)

    private fun corpus(model: ChatModel): Corpus {
        val random = Random(20261116)
        fun code() = (1..8).map { "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"[random.nextInt(31)] }.joinToString("")
        val expected = linkedMapOf<String, String>()
        repeat(30) { expected["A%02d".format(it + 1)] = code() }
        val corrected = listOf("A01", "A11", "A21")
        val corrections = corrected.associateWith { code() }
        val targetSize = (CONTEXT_WINDOW_TOKENS * 66 / 100 * 4 / 30).toInt()
        val history = buildList {
            repeat(30) { index ->
                val key = "A%02d".format(index + 1)
                val text = buildString {
                    if (index == 0) append("这是一份虚构旅行档案。请记住每本账册确认的封印代码和后续更正，旅途描写只作背景。\n")
                    append("Archive chapter ${index + 1}. The confirmed seal code for ledger $key is ${expected[key]}.\n")
                    if (index == 27) corrections.forEach { (name, value) -> append("更正：账册 $name 的旧封印代码已作废，最新确认的代码是 $value。\n") }
                    var scene = 0
                    while (length < targetSize) {
                        append("Journey ${index + 1}, scene ${scene++}: The travelers followed the river past the orchard. They watched clouds gather above the hills, then crossed the wooden bridge to the quiet village. The keeper lit a lamp beside the old window while the rain washed the empty road. No ledger code was recorded in this scene.\n")
                    }
                }
                add(Message.User("probe-u$index", index.toLong(), parentId = if (index == 0) null else "probe-a${index - 1}", selectedChildId = "probe-a$index", content = text))
                add(Message.Assistant("probe-a$index", index.toLong(), parentId = "probe-u$index", selectedChildId = if (index == 29) null else "probe-u${index + 1}",
                    model = model, status = ReplyStatus.Done, stopReason = StopReason.EndTurn, blocks = listOf(ReplyBlock.Text("本章已记录。"))))
            }
        }
        expected.putAll(corrections)
        repeat(5) { expected["A%02d".format(it + 31)] = "UNKNOWN" }
        return Corpus(history, expected, corrected)
    }
}
