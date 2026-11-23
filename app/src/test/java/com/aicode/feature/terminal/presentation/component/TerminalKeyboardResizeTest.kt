package com.aicode.feature.terminal.presentation.component

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import io.mockk.every
import io.mockk.mockk
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class TerminalKeyboardResizeTest {
    @Test
    fun heightChangesAreCoalescedAndDetachCancelsPendingResize() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val root = FrameLayout(activity)
        activity.setContentView(root)
        val view = TerminalView(activity, null)
        view.setTerminalViewClient(mockk<TerminalViewClient>(relaxed = true))
        view.setTextSize(24)
        root.addView(view)
        assertTrue(view.isAttachedToWindow)

        val session = mockk<TerminalSession>()
        val reports = mutableListOf<Pair<Int, Int>>()
        val output = mockk<TerminalOutput>(relaxed = true)
        val client = mockk<TerminalSessionClient>(relaxed = true)
        var emulator: TerminalEmulator? = null
        every { session.emulator } answers { emulator }
        every { session.updateSize(any(), any()) } answers {
            val columns = firstArg<Int>()
            val rows = secondArg<Int>()
            reports += columns to rows
            val current = emulator
            if (current == null) emulator = TerminalEmulator(output, columns, rows, null, client)
            else current.resize(columns, rows)
        }
        fun layout(width: Int, height: Int) {
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, width, height)
        }
        val looper = shadowOf(Looper.getMainLooper())
        try {
            view.attachSession(session)
            layout(800, 600)
            val start = reports.single()
            for (height in listOf(660, 720, 780, 840, 900)) {
                layout(800, height)
                looper.idleFor(Duration.ofMillis(16))
            }
            assertEquals(listOf(start), reports)
            looper.idleFor(Duration.ofMillis(120))
            assertEquals(2, reports.size)
            assertEquals(start.first, reports.last().first)
            assertTrue(reports.last().second > start.second)

            layout(800, 960)
            layout(720, 960)
            assertEquals(3, reports.size)
            assertTrue(reports.last().first < start.first)
            looper.idleFor(Duration.ofMillis(120))
            assertEquals(3, reports.size)

            layout(720, 840)
            view.setTextSize(28)
            assertEquals(4, reports.size)
            layout(720, 720)
            root.removeView(view)
            looper.idleFor(Duration.ofMillis(240))
            assertEquals(4, reports.size)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
