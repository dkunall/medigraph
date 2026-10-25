package com.medigraph.icu.stream;

import java.util.function.Consumer;

/** Port for the vitals pipeline. Implementations: {@link InMemoryVitalsProcessor}, Kafka Streams adapter. */
public interface VitalsStreamProcessor extends AutoCloseable {

    void submit(VitalReading reading);

    /** Signal that time has advanced to {@code nowMillis} (closes windows of silent patients). */
    void advanceTime(long nowMillis);

    void subscribe(Consumer<WindowScore> listener);

    /** False once the pipeline has stopped processing (e.g. the Kafka Streams client shut down). */
    default boolean healthy() {
        return true;
    }

    @Override
    default void close() {
    }
}
