package com.medigraph.icu.stream;

import com.medigraph.icu.RiskLevel;
import com.medigraph.icu.VitalType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryVitalsProcessorTest {

    private static final long MIN = 60_000;
    private static final long T0 = 1_800_000_000_000L - (1_800_000_000_000L % (5 * MIN)); // aligned window start

    private InMemoryVitalsProcessor proc;
    private final List<WindowScore> out = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        proc = new InMemoryVitalsProcessor(StreamConfig.defaults());
        proc.subscribe(out::add);
    }

    private void submitAll(List<VitalReading> rs) {
        rs.forEach(proc::submit);
    }

    private List<WindowScore> alerts() {
        return out.stream().filter(WindowScore::alert).toList();
    }

    @Test
    void windowClosesOnlyAfterWatermarkPassesEndPlusLateness() {
        submitAll(Vitals.healthy("p1", T0 + MIN));
        assertThat(out).isEmpty();
        proc.submit(VitalReading.of(Vitals.id(), "p1", VitalType.PULSE, 72, T0 + 6 * MIN)); // wm = 4min
        assertThat(out).isEmpty();
        proc.submit(VitalReading.of(Vitals.id(), "p1", VitalType.PULSE, 72, T0 + 7 * MIN)); // wm = 5min = end
        assertThat(out).singleElement().satisfies(w -> {
            assertThat(w.windowStart()).isEqualTo(T0);
            assertThat(w.complete()).isTrue();
            assertThat(w.score().total()).isZero();
            assertThat(w.alert()).isFalse();
        });
    }

    @Test
    void outOfOrderReadingsUseLatestEventTimeNotArrivalOrder() {
        // newer abnormal pulse arrives first, the older normal one arrives after it
        List<VitalReading> base = new ArrayList<>(Vitals.healthy("p1", T0 + MIN));
        base.removeIf(r -> r.type() == VitalType.PULSE);
        submitAll(base);
        proc.submit(VitalReading.of(Vitals.id(), "p1", VitalType.PULSE, 135, T0 + 4 * MIN)); // 3 points
        proc.submit(VitalReading.of(Vitals.id(), "p1", VitalType.PULSE, 70, T0 + 2 * MIN));  // older, arrives late
        proc.advanceTime(T0 + 7 * MIN);
        assertThat(out).singleElement().satisfies(w -> {
            assertThat(w.score().components()).containsEntry(VitalType.PULSE, 3);
            assertThat(w.score().risk()).isEqualTo(RiskLevel.LOW_MEDIUM);
            assertThat(w.alert()).isTrue();
        });
    }

    @Test
    void readingWithinAllowedLatenessIsIncludedButAfterCloseIsDropped() {
        submitAll(Vitals.healthy("p1", T0 + MIN));
        proc.submit(VitalReading.of(Vitals.id(), "p1", VitalType.PULSE, 72, T0 + 6 * MIN + 30_000)); // wm 4.5min
        // still within lateness for window [0,5): accepted and changes the score
        proc.submit(VitalReading.of(Vitals.id(), "p1", VitalType.RESPIRATION_RATE, 26, T0 + 4 * MIN));
        proc.submit(VitalReading.of(Vitals.id(), "p1", VitalType.PULSE, 72, T0 + 8 * MIN)); // closes [0,5)
        assertThat(out).singleElement().satisfies(w -> assertThat(w.score().components()).containsEntry(VitalType.RESPIRATION_RATE, 3));

        proc.submit(VitalReading.of(Vitals.id(), "p1", VitalType.RESPIRATION_RATE, 40, T0 + 3 * MIN)); // too late
        assertThat(proc.lateDropped("p1")).isEqualTo(1);
        assertThat(out).hasSize(1);
    }

    @Test
    void duplicateDeliveriesAreIgnored() {
        List<VitalReading> rs = Vitals.healthy("p1", T0 + MIN);
        submitAll(rs);
        submitAll(rs);
        assertThat(proc.duplicatesDropped("p1")).isEqualTo(7);
    }

    @Test
    void slowChartedValuesCarryForwardWhileFreshThenWindowIsIncomplete() {
        submitAll(Vitals.healthy("p1", T0 + MIN));
        // next windows only get monitor vitals; temperature/ACVPU/O2 carried forward
        for (int w = 1; w <= 15; w++) {
            long t = T0 + w * 5 * MIN + MIN;
            proc.submit(VitalReading.of(Vitals.id(), "p1", VitalType.RESPIRATION_RATE, 16, t));
            proc.submit(VitalReading.of(Vitals.id(), "p1", VitalType.SPO2, 98, t));
            proc.submit(VitalReading.of(Vitals.id(), "p1", VitalType.SYSTOLIC_BP, 120, t));
            proc.submit(VitalReading.of(Vitals.id(), "p1", VitalType.PULSE, 70, t));
        }
        proc.advanceTime(T0 + 200 * MIN);
        assertThat(out).hasSize(16);
        assertThat(out.subList(0, 12)).allMatch(WindowScore::complete); // within 60 min freshness
        WindowScore last = out.get(15);
        assertThat(last.complete()).isFalse();
        assertThat(last.missing()).containsExactlyInAnyOrder(VitalType.TEMPERATURE, VitalType.CONSCIOUSNESS, VitalType.SUPPLEMENTAL_O2);
    }

    @Test
    void cooldownSuppressesRepeatsButEscalationPagesImmediately() {
        // MEDIUM in windows 0..3, HIGH in window 4, HIGH in 5..11, then HIGH after cooldown
        for (int w = 0; w < 4; w++) submitAll(Vitals.medium("p1", T0 + w * 5 * MIN + MIN));
        submitAll(Vitals.high("p1", T0 + 4 * 5 * MIN + MIN));
        for (int w = 5; w < 12; w++) submitAll(Vitals.high("p1", T0 + w * 5 * MIN + MIN));
        proc.advanceTime(T0 + 100 * MIN);
        assertThat(out).hasSize(12);
        assertThat(alerts()).extracting(WindowScore::alertReason)
                .containsExactly("NEW", "ESCALATION", "REPEAT_AFTER_COOLDOWN");
        assertThat(alerts().get(1).score().risk()).isEqualTo(RiskLevel.HIGH);
        assertThat(alerts().get(2).windowEnd() - alerts().get(1).windowEnd()).isGreaterThanOrEqualTo(30 * MIN);
    }

    @Test
    void silentDeviceWindowIsClosedByTimeAdvance() {
        submitAll(Vitals.high("p1", T0 + MIN));
        assertThat(out).isEmpty();
        proc.advanceTime(T0 + 7 * MIN);
        assertThat(alerts()).hasSize(1);
    }

    @Test
    void shuffledArrivalProducesSameResultAsOrderedArrival() {
        List<VitalReading> all = new ArrayList<>();
        Random r = new Random(3);
        for (int w = 0; w < 20; w++) {
            long t = T0 + w * 5 * MIN + r.nextInt((int) (4 * MIN));
            all.addAll(w % 3 == 0 ? Vitals.high("p1", t) : w % 3 == 1 ? Vitals.medium("p1", t) : Vitals.healthy("p1", t));
        }
        InMemoryVitalsProcessor ordered = new InMemoryVitalsProcessor(cfgWithLateness(Duration.ofMinutes(10)));
        List<WindowScore> a = new ArrayList<>();
        ordered.subscribe(a::add);
        all.stream().sorted((x, y) -> Long.compare(x.eventTimeMillis(), y.eventTimeMillis())).forEach(ordered::submit);
        ordered.advanceTime(T0 + 500 * MIN);
        // bounded disorder (each reading delayed up to 8 minutes, under the 10 minute lateness)
        List<VitalReading> disordered = new ArrayList<>(all);
        List<long[]> arrival = new ArrayList<>();
        for (int i = 0; i < disordered.size(); i++) {
            arrival.add(new long[]{disordered.get(i).eventTimeMillis() + r.nextInt((int) (8 * MIN)), i});
        }
        arrival.sort((x, y) -> Long.compare(x[0], y[0]));
        InMemoryVitalsProcessor shuffled = new InMemoryVitalsProcessor(cfgWithLateness(Duration.ofMinutes(10)));
        List<WindowScore> b = new ArrayList<>();
        shuffled.subscribe(b::add);
        arrival.forEach(x -> shuffled.submit(disordered.get((int) x[1])));
        shuffled.advanceTime(T0 + 500 * MIN);

        assertThat(b).isEqualTo(a);
        assertThat(shuffled.lateDropped("p1")).isZero();
    }

    @Test
    void patientsAreIndependentUnderConcurrency() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        for (int p = 0; p < 40; p++) {
            String id = "p" + p;
            boolean sick = p % 2 == 0;
            pool.submit(() -> {
                for (int w = 0; w < 5; w++) {
                    List<VitalReading> rs = sick ? Vitals.high(id, T0 + w * 5 * MIN + MIN) : Vitals.healthy(id, T0 + w * 5 * MIN + MIN);
                    Collections.shuffle(new ArrayList<>(rs));
                    rs.forEach(proc::submit);
                }
            });
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        proc.advanceTime(T0 + 100 * MIN);
        assertThat(out).hasSize(200);
        assertThat(alerts()).hasSize(20).allMatch(w -> Integer.parseInt(w.patientId().substring(1)) % 2 == 0);
    }

    private static StreamConfig cfgWithLateness(Duration lateness) {
        StreamConfig d = StreamConfig.defaults();
        return new StreamConfig(d.windowSize(), lateness, d.freshness(), d.cooldown(), d.alertAt());
    }
}
