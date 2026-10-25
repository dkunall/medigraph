package com.medigraph.mpi;

import java.util.List;
import java.util.Optional;

/** Port for the data-steward review queue (in-memory here; the app provides a PostgreSQL implementation). */
public interface ReviewQueue {

    void enqueue(ReviewTask task);

    Optional<ReviewTask> find(String taskId);

    List<ReviewTask> pending();

    /** Atomically moves a PENDING task to {@code status}; returns false if it was not pending. */
    boolean complete(String taskId, ReviewTask.Status status);
}
