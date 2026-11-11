package lab

import kotlinx.coroutines.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class ClockTest {
    @Test fun `strict ordering samples one instant even if wall clock then goes backwards`(): Unit =
        runBlocking {
            val previous = Instant.parse("2026-11-11T00:00:00Z")
            val clock =
                object : Clock() {
                    var calls = 0

                    override fun getZone(): ZoneId = ZoneOffset.UTC

                    override fun withZone(zone: ZoneId): Clock = this

                    override fun instant(): Instant = if (calls++ == 0) previous.plusSeconds(1) else previous
                }
            assertThat(WallClock(clock).after(timestamp(previous))).isEqualTo("2026-11-11 00:00:01")
        }

    @Test fun `clock skew fails within deadline and cancellation propagates`() {
        val clock = WallClock(Clock.fixed(Instant.parse("2026-11-11T00:00:00Z"), ZoneOffset.UTC))
        assertThatThrownBy {
            runBlocking { clock.after("2026-11-12 00:00:00") }
        }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy {
            runBlocking { withTimeout(20) { clock.after("2026-11-12 00:00:00") } }
        }.isInstanceOf(TimeoutCancellationException::class.java)
    }
}
