package com.medigraph.platform.outbox;

import com.medigraph.common.port.UnitOfWork;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/** {@link UnitOfWork} adapter over Spring's TransactionTemplate (REQUIRED propagation, rollback on runtime errors). */
public final class SpringUnitOfWork implements UnitOfWork {

    private final TransactionTemplate tx;

    public SpringUnitOfWork(TransactionTemplate tx) {
        this.tx = tx;
    }

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        return tx.execute(status -> work.get());
    }
}
