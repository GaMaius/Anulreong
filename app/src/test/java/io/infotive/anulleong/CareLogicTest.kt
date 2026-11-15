package io.infotive.anulleong

import io.infotive.anulleong.care.AheadEvents
import io.infotive.anulleong.care.josa
import io.infotive.anulleong.care.ieyo
import io.infotive.anulleong.care.AheadEvents.RawCaution
import io.infotive.anulleong.care.AheadEvents.RawTbt
import io.infotive.anulleong.care.CareRecord
import io.infotive.anulleong.care.CuePolicy
import io.infotive.anulleong.care.DataSource
import io.infotive.anulleong.care.DemoScenario
import io.infotive.anulleong.care.EventKind
import io.infotive.anulleong.care.Feeling
import io.infotive.anulleong.care.Loc
import io.infotive.anulleong.care.RecordRules
import io.infotive.anulleong.care.RevisitDetector
import io.infotive.anulleong.care.VentRules
import io.infotive.anulleong.care.RouteStats
import io.infotive.anulleong.care.ZoneAnalysis
import io.infotive.anulleong.care.SimilarityModel
import io.infotive.anulleong.care.PassTracker
import io.infotive.anulleong.care.AheadEvent
import io.infotive.anulleong.sdk.VoiceInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CareLogicTest {

    // Shape of the real Seoul Station run (TBT distances were not monotonic: 700, 50, 110…)
    private val seoulTbt = listOf(
        RawTbt("TURN_RIGHT", 700.0, "우회전"),
        RawTbt("TURN_RIGHT", 50.0, "우회전"),
        RawTbt("TURN_ENTER_IC_R", 110.0, "IC 진입"),
        RawTbt("TURN_TOLL", 40.0, "톨게이트"),
        RawTbt("TURN_STRAIGHT", 300.0, "직진"),
        RawTbt("TURN_ARRIVE", 50.0, "도착"),
    )

    @Test fun onlyFirstTbtCarriesDistance() {
        val events = AheadEvents.merge(seoulTbt, emptyList())
        assertEquals(700, events.first().distanceM)
        assertTrue(events.drop(1).all { it.distanceM == null })
        assertFalse("straight is not a sway cue", events.any { it.key.contains("STRAIGHT") })
        assertEquals("오른쪽으로 회전", events.first().title)
    }

    @Test fun cautionPointsAreSortedWithDistanceAndIrrelevantOnesDropped() {
        val events = AheadEvents.merge(seoulTbt, listOf(
            RawCaution("a", "SPEED_BUMP", 120),
            RawCaution("b", "TRAFFIC_LIGHT_VIOLATION_ENFORCEMENT", 30),
            RawCaution("c", "DOWNHILL_CAUTION_AREA", 900),
        ))
        assertEquals(listOf(EventKind.BUMP, EventKind.TURN, EventKind.DOWNHILL), events.take(3).map { it.kind })
        assertFalse(events.any { it.key == "caution:b" })
    }

    @Test fun sharpAndRoundaboutAreStrong() {
        assertEquals(EventKind.SHARP_TURN, AheadEvents.fromTbt(0, RawTbt("TURN_ENTER_ROUNDABOUT_03", 10.0, ""))!!.kind)
        assertEquals("유턴", AheadEvents.fromTbt(0, RawTbt("TURN_UTURN", 10.0, ""))!!.title)
        assertNull(AheadEvents.fromTbt(0, RawTbt("TURN_TUNNEL", 10.0, "")))
    }

    @Test fun cuePolicyRespectsDistanceGapAndCooldown() {
        val p = CuePolicy(announceWithinM = 300, minGapMs = 15_000, sameKeyCooldownMs = 90_000)
        val far = AheadEvents.merge(listOf(RawTbt("TURN_LEFT", 800.0, "")), emptyList())
        assertNull(p.pick(far, 0))
        val near = AheadEvents.merge(listOf(RawTbt("TURN_LEFT", 250.0, "")), emptyList())
        assertNotNull(p.pick(near, 1_000))
        assertNull("global gap", p.pick(near, 5_000))
        assertNull("same key cooldown", p.pick(near, 30_000))
        assertNotNull(p.pick(near, 100_000))
    }

    @Test fun duplicateTapIsIgnoredButDifferentFeelingIsNot() {
        val last = rec(Feeling.SICK, 1_000, null, null, null)
        assertTrue(RecordRules.isDuplicate(last, Feeling.SICK, 5_000))
        assertFalse(RecordRules.isDuplicate(last, Feeling.OK, 5_000))
        assertFalse(RecordRules.isDuplicate(last, Feeling.SICK, 20_000))
    }

    @Test fun staleLocationIsNotAttached() {
        assertNull(RecordRules.usableLocation(Loc(37.0, 127.0, 0.0, 0), 120_000))
        assertNotNull(RecordRules.usableLocation(Loc(37.0, 127.0, 0.0, 100_000), 120_000))
    }

    @Test fun revisitNeedsSameDirectionDistanceAndSource() {
        val past = rec(Feeling.SICK, 0, 37.5, 127.0, 90.0, "trip-a")
        val now = 60 * 60_000L
        val d = RevisitDetector()
        // heading east, 90 m west of the spot = approaching it
        val approaching = Loc(37.5, 126.999, 90.0, now)
        assertNull("opposite direction", d.check(Loc(37.5, 127.001, 270.0, now), listOf(past), now, DataSource.DEMO, "trip-b"))
        assertNull("too far", d.check(Loc(37.52, 127.0, 90.0, now), listOf(past), now, DataSource.DEMO, "trip-b"))
        assertNull("demo records never fire on real drives", d.check(approaching, listOf(past), now, DataSource.NAVI, "trip-b"))
        assertNotNull(d.check(approaching, listOf(past), now, DataSource.DEMO, "trip-b"))
        assertNull("cooldown", d.check(approaching, listOf(past), now + 1000, DataSource.DEMO, "trip-b"))
        assertNull("ok feelings never trigger", RevisitDetector().check(approaching,
            listOf(rec(Feeling.OK, 0, 37.5, 127.0, 90.0, "trip-a")), now, DataSource.DEMO, "trip-b"))
    }

    @Test fun recordFromTheSameTripIsIgnoredUntilNextTrip() {
        val now = 60_000L
        val past = rec(Feeling.SICK, 0, 37.5, 127.0, 90.0, "demo-trip-1")
        val here = Loc(37.5, 126.999, 90.0, now)
        assertTrue(RevisitDetector().ahead(here, listOf(past), now, DataSource.DEMO, "demo-trip-1").isEmpty())
        assertEquals(1, RevisitDetector().ahead(here, listOf(past), now, DataSource.DEMO, "demo-trip-2").size)
    }

    @Test fun spotsBehindTheCarAreNotAhead() {
        val past = rec(Feeling.SICK, 0, 37.5, 127.0, 90.0, "a")
        val behind = Loc(37.5, 127.002, 90.0, 0) // already passed it, still heading east
        assertTrue(RevisitDetector().ahead(behind, listOf(past), 0, DataSource.DEMO, "b").isEmpty())
    }

    /** The whole judge demo: record on trip 1, get a pin and an alert on trip 2. */
    @Test fun demoRecordThenAlertOnNextTrip() {
        val atRecord = DemoScenario.snapshot(40, 40_000, trip = 1) // 2.4 km into trip 1
        val loc = atRecord.location!!
        val record = CareRecord("r1", 40_000, Feeling.SICK, DataSource.DEMO, loc.lat, loc.lon, loc.heading,
            atRecord.routeId, null, null, null, null)
        val d = RevisitDetector()
        var pinSeen = false
        var alertAtSec = -1L
        for (sec in 0..80L) {
            val now = 200_000 + sec * 1000
            val s = DemoScenario.snapshot(sec, now, trip = 2)
            if (d.ahead(s.location, listOf(record), now, DataSource.DEMO, s.routeId).isNotEmpty()) pinSeen = true
            if (alertAtSec < 0 && d.check(s.location, listOf(record), now, DataSource.DEMO, s.routeId) != null) alertAtSec = sec
        }
        assertTrue(pinSeen)
        // spot at 2400 m, alert within 400 m before it: 2000..2400 m → 34..40 s at 60 m/s
        assertTrue("alert at $alertAtSec s", alertAtSec in 33..40)
    }

    @Test fun ventTargetUsesReportedBounds() {
        assertEquals(2, VentRules.slightlyOpenTarget(0, 10, 0))
        assertNull("already open enough", VentRules.slightlyOpenTarget(0, 10, 5))
        assertEquals(1, VentRules.slightlyOpenTarget(0, 3, 0))
        assertNull(VentRules.slightlyOpenTarget(5, 5, 5))
    }

    @Test fun demoIsTaggedAndStopsAtDestination() {
        val s = DemoScenario.snapshot(0, 0)
        assertEquals(DataSource.DEMO, s.source)
        assertEquals(EventKind.BUMP, s.events.first().kind)
        assertEquals(600, s.events.first().distanceM)
        val end = DemoScenario.snapshot(1000, 0, trip = 3)
        assertEquals(0, end.remainDistanceM)
        assertFalse(end.routing)
        assertEquals("demo-trip-3", end.routeId)
    }


    @Test fun zonesGroupNearbySickRecordsAndFindCommonCue() {
        val a = CareRecord("a", 1, Feeling.SICK, DataSource.NAVI, 37.5, 127.0, 90.0, null, null, null, null, "회전교차로")
        val b = CareRecord("b", 2, Feeling.SICK, DataSource.NAVI, 37.5005, 127.0005, 90.0, null, null, null, null, "회전교차로")
        val c = CareRecord("c", 3, Feeling.SICK, DataSource.NAVI, 37.6, 127.0, 90.0, null, null, null, null, "좌회전")
        val ok = CareRecord("d", 4, Feeling.OK, DataSource.NAVI, 37.5, 127.0, 90.0, null, null, null, null, null)
        val demo = CareRecord("e", 5, Feeling.SICK, DataSource.DEMO, 37.5, 127.0, 90.0, null, null, null, null, null)
        val zones = ZoneAnalysis.zones(listOf(a, b, c, ok, demo))
        assertEquals(3, zones.size)
        assertEquals(2, zones.first().count)
        assertEquals("회전교차로", zones.first().commonCue)
        assertEquals(2, ZoneAnalysis.countNear(a, listOf(a, b, c, ok, demo)))
    }

    @Test fun demoComfortRoadIsCalmerAndAvoidsTheRecordedZone() {
        val atRecord = DemoScenario.snapshot(40, 40_000, trip = 1).location!!
        val rec = CareRecord("r", 1, Feeling.SICK, DataSource.DEMO, atRecord.lat, atRecord.lon, 90.0, "demo-trip-1", null, null, null, null)
        val zones = ZoneAnalysis.zones(listOf(rec))
        val usual = DemoScenario.stats(DemoScenario.Road.USUAL, zones)
        val comfort = DemoScenario.stats(DemoScenario.Road.COMFORT, zones)
        assertEquals(1, usual.sickZones)
        assertEquals(0, comfort.sickZones)
        assertTrue(comfort.comfortScore > usual.comfortScore)
        assertTrue(comfort.distanceM!! > usual.distanceM!!)
        // and driving the comfort road never raises the old alert
        val d = RevisitDetector()
        for (sec in 0..95L) {
            val sn = DemoScenario.snapshot(sec, 300_000 + sec * 1000, trip = 3, road = DemoScenario.Road.COMFORT)
            assertNull(d.check(sn.location, listOf(rec), 300_000 + sec * 1000, DataSource.DEMO, sn.routeId))
            // ...and no "past sick spot" pin is drawn on a road that does not pass it
            assertTrue(d.ahead(sn.location, listOf(rec), 300_000 + sec * 1000, DataSource.DEMO, sn.routeId).isEmpty())
        }
    }

    @Test fun routeStatsScoreCountsSwayManoeuvres() {
        val ev = AheadEvents.merge(listOf(RawTbt("TURN_UTURN", 10.0, ""), RawTbt("TURN_ENTER_IC_R", 0.0, ""), RawTbt("TURN_LEFT", 0.0, "")),
            listOf(RawCaution("x", "SPEED_BUMP", 50)))
        val st = RouteStats.of(ev, null, 1000)
        assertEquals(3 + 2 + 1 + 2, st.swayPoints)
        // 8 points over 1 km → 100 − 80 = 20
        assertEquals(20, st.comfortScore)
        assertEquals(0, st.copy(sickZones = 2).comfortScore)
        assertEquals(12, st.copy(similar = 1).comfortScore)
    }


    @Test fun similarKindIsLearnedFromSickRecordsAndPasses() {
        val r = CareRecord("s", 1, Feeling.SICK, DataSource.DEMO, 37.5, 127.0, 90.0, "t1", null, null, null, "회전교차로", EventKind.SHARP_TURN)
        // sick once at a roundabout, passed roundabouts 2 times, ramps 5 times
        val sens = SimilarityModel.sensitivities(listOf(r), mapOf(EventKind.SHARP_TURN to 2, EventKind.RAMP to 5), DataSource.DEMO)
        val rb = sens.first { it.kind == EventKind.SHARP_TURN }
        assertEquals(0.5f, rb.rate, 0.001f)
        assertTrue(rb.flagged)
        assertFalse(sens.first { it.kind == EventKind.RAMP }.flagged)
        // 1 sick in 5 passes = 20 % → not flagged
        assertFalse(SimilarityModel.sensitivities(listOf(r), mapOf(EventKind.SHARP_TURN to 5), DataSource.DEMO).first().flagged)
        // other data source does not leak
        assertTrue(SimilarityModel.flaggedKinds(listOf(r), emptyMap(), DataSource.NAVI).isEmpty())
    }

    @Test fun similarSectionsAreAnnotatedExceptNextToAKnownSpot() {
        val rb = AheadEvents.fromTbt(0, RawTbt("TURN_ENTER_ROUNDABOUT_02", 900.0, ""))!!
        val far = AheadEvents.fromTbt(0, RawTbt("TURN_ENTER_ROUNDABOUT_03", 2000.0, "b"))!!
        val pin = AheadEvents.fromPastRecord("p", 950)
        val out = SimilarityModel.annotate(listOf(rb, pin, far), setOf(EventKind.SHARP_TURN))
        assertFalse("revisit alert covers this one", out[0].similarRisk)
        assertTrue(out[2].similarRisk)
        assertTrue(out[2].kidLine.startsWith("지난번에 비슷한"))
        // similar sections are announced earlier (500 m instead of 300 m)
        val p = CuePolicy()
        assertNotNull(p.pick(listOf(out[2].copy(distanceM = 480)), 0))
    }

    @Test fun recordIsAttributedToTheSectionJustPassedOrRightAhead() {
        val rb = AheadEvents.fromTbt(0, RawTbt("TURN_ENTER_ROUNDABOUT_03", 0.0, ""))!!
        val ramp = AheadEvents.fromTbt(0, RawTbt("TURN_ENTER_IC_R", 250.0, ""))!!
        assertEquals(EventKind.SHARP_TURN, SimilarityModel.attribute(rb to 1_000L, listOf(ramp), 30_000)?.kind)
        assertEquals(EventKind.RAMP, SimilarityModel.attribute(rb to 1_000L, listOf(ramp), 90_000)?.kind)
        assertNull(SimilarityModel.attribute(null, listOf(ramp.copy(distanceM = 900)), 0))
    }

    @Test fun passTrackerCountsSectionsThatDisappearNearby() {
        val t = PassTracker()
        val near = AheadEvents.fromTbt(0, RawTbt("TURN_LEFT", 60.0, ""))!!
        val far = AheadEvents.fromTbt(0, RawTbt("TURN_RIGHT", 900.0, ""))!!
        assertTrue(t.update(listOf(near, far)).isEmpty())
        val passed = t.update(listOf(far))
        assertEquals(listOf(near.key), passed.map { it.key })
        assertTrue("a far section vanishing (re-route) is not a pass", t.update(emptyList()).isEmpty())
    }

    @Test fun demoFlowRecordsRoundaboutAndPredictsItOnTheComfortRoad() {
        // trip 1: the roundabout at 1.2 km is passed at ~20 s, 🤢 at 22 s is attributed to it
        val t = PassTracker()
        var passedRb: AheadEvent? = null
        for (sec in 0..22L) t.update(DemoScenario.snapshot(sec, sec * 1000).events).forEach { if (it.kind == EventKind.SHARP_TURN) passedRb = it }
        val cause = SimilarityModel.attribute(passedRb?.let { it to 20_000L }, DemoScenario.snapshot(22, 22_000).events, 22_000)
        assertEquals(EventKind.SHARP_TURN, cause?.kind)
        // trip 3 on the comfort road: its roundabout is flagged as a similar section
        val rec = CareRecord("r", 1, Feeling.SICK, DataSource.DEMO, 37.51, 127.07, 90.0, "demo-trip-1", null, null, null, cause?.title, cause?.kind)
        val flagged = SimilarityModel.flaggedKinds(listOf(rec), mapOf(EventKind.SHARP_TURN to 1), DataSource.DEMO)
        val ev = SimilarityModel.annotate(DemoScenario.snapshot(0, 0, 3, DemoScenario.Road.COMFORT).events, flagged)
        assertTrue(ev.any { it.kind == EventKind.SHARP_TURN && it.similarRisk })
    }

    @Test fun voiceKeywordsMapToFeelings() {
        assertEquals(Feeling.SICK, VoiceInput.match("멀미 나요"))
        assertEquals(Feeling.SICK, VoiceInput.match("속이 안 좋아"))
        assertEquals(Feeling.OK, VoiceInput.match("멀미 안 나"))
        assertEquals(Feeling.OK, VoiceInput.match("괜찮아"))
        assertEquals(Feeling.UNEASY, VoiceInput.match("안 괜찮아"))
        assertNull(VoiceInput.match("배고파"))
    }

    private fun rec(f: Feeling, t: Long, lat: Double?, lon: Double?, h: Double?, trip: String? = null) =
        CareRecord("id$t$lat", t, f, DataSource.DEMO, lat, lon, h, trip, null, null, null, null)

    @Test fun koreanParticles() {
        org.junit.Assert.assertEquals("회전교차로가", josa("회전교차로", "이", "가"))
        org.junit.Assert.assertEquals("급커브가", josa("급커브", "이", "가"))
        org.junit.Assert.assertEquals("과속방지턱이", josa("과속방지턱", "이", "가"))
        org.junit.Assert.assertEquals("내리막길이에요", ieyo("내리막길"))
        org.junit.Assert.assertEquals("회전교차로예요", ieyo("회전교차로"))
    }
}
