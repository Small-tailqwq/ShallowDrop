# 固定样本压缩与召回测试（2026-11-16）

使用 ADB 在 MuMu Android 15（API 35）上运行真实模型请求，模型为 `deepseek-flash`，设置为后台 45%、等待 65%。测试结束后保留该设置。测试会话为 `compaction-probe-1794833578162`，标题「压缩可靠性 · 固定样本」。

## 样本与方法

[设备测试源码](../../android/app/src/androidTest/java/io/github/smalltailqwq/shallowdrop/domain/chat/CompactionReliabilityTest.kt) 使用固定随机种子 `20261116` 生成 30 轮旅行档案。每轮包含一条随机封印代码及大量重复背景文字；第 28 轮更正第 1、11、21 轮的代码。另问 5 条未提供的信息，预期回答 `UNKNOWN`。按代码字符串精确匹配评分。

先让模型用完整上下文回答，再用生产 `ChatEngine` 生成摘要并回答相同问题。基线问题和答案没有写入待压缩会话，摘要无法从基线答案取得测试结果。前 28 轮被总结，最后 2 轮按生产策略保留原文。

客户端估算原始上下文为 692,751 token（窗口的 66.07%），自然超过等待阈值，没有伪造 usage。服务端实测基线输入为 581,919 token（55.50%）。本轮触发依据是客户端估算值。

## 结果

| 指标 | 未压缩基线 | 压缩后 |
|---|---:|---:|
| 已知事实正确数 | 30/30 | 30/30 |
| 前段 / 中段 / 后段正确数 | 各 10/10 | 各 10/10 |
| 被总结的前 28 轮事实 | 28/28 | 28/28 |
| 后续更正采纳数 | 3/3 | 3/3 |
| 未知问题正确拒答数 | 5/5 | 5/5 |
| 回答请求输入 token | 581,919 | 40,366 |
| 回答请求耗时 | 10.770 秒 | 2.236 秒 |

本次事实召回率为 100%，相对基线下降 0 个百分点。回答请求的输入 token 减少 **93.06%**，压缩后输入包括摘要及保留的两轮原文。

生成摘要使用 543,261 输入 token，输出 1,497 token，耗时 **8.105 秒**。首次等待压缩再回答的两次网络请求合计约 10.341 秒，完整设备测试耗时 23.743 秒。摘要请求计入额外用量，输入缩减比例不表示本次测试总用量减少相同比例。

结构性检查全部通过：

- 原始 60 条消息的内容保留完整。
- 回答实际使用了新摘要，并记录对应检查点标识。
- 重新创建聊天引擎、从 Room 读取会话后，后续请求继续使用同一检查点。
- 全程只生成一次摘要；重新创建引擎没有重复压缩。
- 四次模型请求都完整结束，未出现截断或错误。

## 执行与证据

在已安装主 APK 和测试 APK 的模拟器上执行：

```powershell
adb -s 127.0.0.1:16384 shell am instrument -w -r -e class 'io.github.smalltailqwq.shallowdrop.domain.chat.CompactionReliabilityTest#fixedCorpusRecallAndCheckpoint' -e liveCompaction true io.github.smalltailqwq.shallowdrop.test/androidx.test.runner.AndroidJUnitRunner
```

执行结果为 `OK (1 test)`。原始评分 JSON、基线回答、摘要和压缩后回答分别保存在设备的 `files/compaction-test/{result.json,baseline.txt,summary.txt,compacted.txt}`，并提取到本机 `android/app/build/reports/compaction/2026-11-16/`。报告不包含 API Key。

## 结果范围

这是一次固定事实样本测试：需要记住的代码被明确标注，背景文字高度重复，属于易于压缩的材料。本次验证一次压缩后的精确事实召回，以及同一进程内重新创建引擎后的持久化恢复。连续压缩结果见[五轮测试报告](compaction-multipass-2026-11-16.md)。小说中的跨章节联系和进程重启后的恢复需要单独测试。
