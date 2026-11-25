package com.aichat.app.core.audio

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 长按输入框录音的手势。
 *
 * 【为什么不用 combinedClickable(onLongClick)】
 * combinedClickable 在系统判定长按（默认 400ms）后才回调，但**回调后不会告诉你
 * 手指什么时候抬起** —— onLongClick 只给「按住了」这一个事件。要做 push-to-talk
 * （按住说话、松开结束），必须自己跟踪 ACTION_DOWN / ACTION_UP 的完整生命周期。
 * 智谱那份 AndLua 实现就是用 setOnTouchListener 手写的，同一个原因。
 *
 * 【这里比智谱那份实现强在哪】
 * 智谱用 `os.time()`（秒级精度）做 500ms 判定，所以它写的是
 * `os.time() - start >= 1`（至少 1 秒）—— 拿不到真正的 500ms。
 * Compose 的 awaitPointerEvent 自带高精度事件时间戳，直接用 withTimeoutOrNull
 * 就能精确到毫秒，也不会像 sleep 轮询那样空转线程。
 *
 * 【手势语义】
 * - 按下后 400ms 内抬起 → 视为点击，交给 onClick（聚焦输入框）
 * - 按住超过 400ms → 触发 onStart，进入录音
 * - 录音中抬起 → onStop，结束录音并上传
 * - 录音中滑出控件范围超过阈值 → onCancel，丢弃这次录音
 *
 * 滑动取消是 push-to-talk 的标配：按住说话时难免要说错，
 * 想取消就会下意识把手指滑开。没有这个的话用户只能录完再删。
 */
fun Modifier.longPressToTalk(
    enabled: Boolean = true,
    longPressMs: Long = 400L,
    /** 滑出多远算「取消」（像素）。默认 120px，约 40dp。 */
    cancelThresholdPx: Float = 120f,
    onClick: () -> Unit = {},
    onStart: () -> Unit = {},
    onStop: () -> Unit = {},
    onCancel: () -> Unit = {},
): Modifier = composed {
    if (!enabled) return@composed this

    this.pointerInput(enabled, longPressMs, cancelThresholdPx) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val downPos = down.position
            val downTime = down.uptimeMillis

            // 等「长按判定时长」或「手指抬起」，谁先来算谁。
            // 用 timeout 而不是 delay+sleep：抬手事件会立刻打断等待，
            // 不会出现「快速点击后还要等 400ms 才响应」的迟滞。
            val heldLongEnough = withTimeoutOrNull(longPressMs) {
                waitForUpOrCancel()
            } == null

            if (!heldLongEnough) {
                // 400ms 内就抬手了 —— 这是一次点击，不是长按。
                // 手势已被消费的情况下也要走 onClick，否则第一次点输入框没反应。
                onClick()
                return@awaitEachGesture
            }

            // —— 进入录音态 ——
            onStart()
            var cancelled = false
            var lastPos = downPos

            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break

                if (!change.pressed) {
                    // 抬起
                    change.consume()
                    break
                }

                lastPos = change.position
                // 滑出阈值 → 取消。只看垂直/水平的最大偏移，够直观。
                val dx = lastPos.x - downPos.x
                val dy = lastPos.y - downPos.y
                if (!cancelled && (dx * dx + dy * dy) > cancelThresholdPx * cancelThresholdPx) {
                    cancelled = true
                }
                // 手指移回范围内可以恢复 —— 误滑出去又滑回来不该白白丢录音
                else if (cancelled && (dx * dx + dy * dy) <= cancelThresholdPx * cancelThresholdPx) {
                    cancelled = false
                }
            }

            if (cancelled) onCancel() else onStop()
        }
    }
}

/** 等到手指抬起或手势被取消为止。用于长按判定的超时竞争。 */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.waitForUpOrCancel() {
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Main)
        val change = event.changes.firstOrNull() ?: return
        if (!change.pressed) return
    }
}
