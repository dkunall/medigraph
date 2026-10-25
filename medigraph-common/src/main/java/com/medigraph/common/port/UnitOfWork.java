package com.medigraph.common.port;

import java.util.function.Supplier;

/**
 * Transaction boundary as an application-layer port, so use cases stay framework-free. The adapter (Spring's
 * TransactionTemplate) runs the block in one database transaction and rolls back on any runtime exception.
 */
@FunctionalInterface
public interface UnitOfWork {

    <T> T inTransaction(Supplier<T> work);

    default void run(Runnable work) {
        inTransaction(() -> {
            work.run();
            return null;
        });
    }

    /** For pure unit tests: no transaction at all. */
    UnitOfWork NONE = new UnitOfWork() {
        @Override
        public <T> T inTransaction(Supplier<T> work) {
            return work.get();
        }
    };
}
