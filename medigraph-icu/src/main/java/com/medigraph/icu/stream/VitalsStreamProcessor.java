package com.medigraph.icu.stream;

import java.util.function.Consumer;

/** Port for the vitals pipeline. Implementations: {@link InMemoryVitalsProcessor}, Kafka Streams adapter. */
public interface VitalsStreamProcessor extends AutoCloseable {

    void submit(VitalReading reading);

    /** Signal that time has advanced to {@code nowMillis} (closes windows of silent patients). */
    void advanceTime(long nowMillis);

    void subscribe(Consumer<WindowScore> listener);

    @Override
    default void close() {
    }
}
