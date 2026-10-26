package dev.wahh.quotashell.core

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class SnapshotCodecTest {
    @Test fun roundTripPreservesAbsentVersusZero() {
        val snapshot =
            ProviderSnapshot(
                ProviderId.CODEX,
                Connection.STALE,
                Instant.parse("2026-01-01T00:00:00Z"),
                listOf(QuotaWindow(WindowKind.FIVE_HOUR, 0.0), QuotaWindow(WindowKind.MONTH)),
                0,
            )
        assertEquals(snapshot, SnapshotCodec.decode(ProviderId.CODEX, SnapshotCodec.encode(snapshot)))
        assertEquals(ProviderSnapshot(ProviderId.CLAUDE), SnapshotCodec.decode(ProviderId.CLAUDE, null))
    }

    @Test fun damagedDatesAreStorageErrorsNotCrashes() {
        for (key in listOf("capturedAt", "WEEK.reset")) {
            val decoded = SnapshotCodec.decode(ProviderId.CLAUDE, "version=1\nconnection=CONNECTED\n$key=not-a-date")
            assertEquals(Connection.ERROR, decoded.connection)
            assertEquals(Failure.STORAGE, decoded.failure)
        }
    }

    @Test fun corruptOrFutureSchemaIsExplicitStorageError() {
        for (data in listOf("version=99", "version=1\nconnection=BOGUS", "version=1\nconnection=CONNECTED\nFIVE_HOUR.used=NaN")) {
            val decoded = SnapshotCodec.decode(ProviderId.CODEX, data)
            assertEquals(Connection.ERROR, decoded.connection)
            assertEquals(Failure.STORAGE, decoded.failure)
            assertTrue(decoded.windows.isEmpty())
        }
    }
}
