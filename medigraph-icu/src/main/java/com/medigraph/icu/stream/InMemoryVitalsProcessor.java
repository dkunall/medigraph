package com.medigraph.icu.stream;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Single-process implementation: per-patient state in a concurrent map, each patient's state guarded by its own
 * monitor (readings for different patients proceed in parallel; same patient is serialised, as a Kafka partition
 * keyed by patient id would be).
 */
public final class InMemoryVitalsProcessor implements VitalsStreamProcessor {

    private final News2WindowEngine engine;
    private final ConcurrentMap<String, PatientWindowState> states = new ConcurrentHashMap<>();
    private final List<Consumer<WindowScore>> listeners = new CopyOnWriteArrayList<>();

    public InMemoryVitalsProcessor(StreamConfig cfg) {
        this.engine = new News2WindowEngine(cfg);
    }

    @Override
    public void submit(VitalReading r) {
        PatientWindowState s = states.computeIfAbsent(r.patientId(), PatientWindowState::new);
        List<WindowScore> out;
        synchronized (s) {
            out = engine.onReading(s, r);
        }
        out.forEach(this::emit);
    }

    @Override
    public void advanceTime(long nowMillis) {
        for (PatientWindowState s : states.values()) {
            List<WindowScore> out;
            synchronized (s) {
                out = engine.onTime(s, nowMillis);
            }
            out.forEach(this::emit);
        }
    }

    @Override
    public void subscribe(Consumer<WindowScore> listener) {
        listeners.add(listener);
    }

    private void emit(WindowScore w) {
        listeners.forEach(l -> l.accept(w));
    }

    public long lateDropped(String patientId) {
        PatientWindowState s = states.get(patientId);
        return s == null ? 0 : s.lateDropped;
    }

    public long duplicatesDropped(String patientId) {
        PatientWindowState s = states.get(patientId);
        return s == null ? 0 : s.duplicatesDropped;
    }
}
