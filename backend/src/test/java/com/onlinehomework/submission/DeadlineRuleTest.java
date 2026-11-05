package com.onlinehomework.submission;
import org.junit.jupiter.api.Test; import java.time.Instant; import static org.junit.jupiter.api.Assertions.*;
class DeadlineRuleTest { @Test void equalityIsOnTimeAndOnlyAfterIsLate(){Instant due=Instant.parse("2026-01-01T00:00:00Z"); assertFalse(Instant.parse("2025-12-31T23:59:59Z").isAfter(due)); assertFalse(due.isAfter(due)); assertTrue(Instant.parse("2026-01-01T00:00:01Z").isAfter(due));} }
