package com.medigraph.mpi;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class InMemoryReviewQueue implements ReviewQueue {

    private final ConcurrentMap<String, ReviewTask> tasks = new ConcurrentHashMap<>();

    @Override
    public void enqueue(ReviewTask task) {
        tasks.put(task.id(), task);
    }

    @Override
    public Optional<ReviewTask> find(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    @Override
    public List<ReviewTask> pending() {
        return tasks.values().stream().filter(t -> t.status() == ReviewTask.Status.PENDING)
                .sorted(Comparator.comparing(ReviewTask::createdAt)).toList();
    }

    @Override
    public boolean complete(String taskId, ReviewTask.Status status) {
        ReviewTask[] done = new ReviewTask[1];
        tasks.computeIfPresent(taskId, (k, t) -> {
            if (t.status() != ReviewTask.Status.PENDING) {
                return t;
            }
            done[0] = t.withStatus(status);
            return done[0];
        });
        return done[0] != null;
    }
}
