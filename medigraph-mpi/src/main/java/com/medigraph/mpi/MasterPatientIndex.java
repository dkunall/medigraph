package com.medigraph.mpi;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.medigraph.mpi.algo.UnionFind;

/**
 * Master Patient Index: blocking -> Fellegi-Sunter scoring -> decision -> Union-Find clustering.
 *
 * <p>Registration flow for a new record R:
 * <ol>
 *   <li>Collect candidates sharing any blocking key with R (inverted index: key -> record ids).</li>
 *   <li>Score each candidate; keep the best score per existing <i>cluster</i>.</li>
 *   <li>Best cluster >= upper: AUTO_LINK (union). Every other cluster >= lower opens a REVIEW task, so a
 *       record never silently bridges two clusters on weaker evidence.</li>
 *   <li>Best between thresholds: REVIEW task, R starts as its own cluster until a steward merges.</li>
 * </ol>
 * The index is an in-memory derived structure; the app rebuilds it from PostgreSQL at start-up via
 * {@link #restore}. Guarded by a read-write lock. Anything that calls {@code find()} takes the write lock because
 * path compression mutates; plain record/count lookups share the read lock.
 */
public final class MasterPatientIndex {

    private final FellegiSunterScorer scorer;
    private final ReviewQueue reviewQueue;
    private final Clock clock;

    private final Map<String, PatientDemographics> records = new HashMap<>();
    private final Map<String, Set<String>> blocks = new HashMap<>();
    private final UnionFind<String> clusters = new UnionFind<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public MasterPatientIndex(MatchConfig config, ReviewQueue reviewQueue, Clock clock) {
        this.scorer = new FellegiSunterScorer(config);
        this.reviewQueue = reviewQueue;
        this.clock = clock;
    }

    public MasterPatientIndex(MatchConfig config) {
        this(config, new InMemoryReviewQueue(), Clock.systemUTC());
    }

    public MatchOutcome register(PatientDemographics p) {
        lock.writeLock().lock();
        try {
            if (records.containsKey(p.recordId())) {
                throw new IllegalArgumentException("record already registered: " + p.recordId());
            }
            Set<String> candidates = candidatesFor(p);
            // best score per existing cluster
            Map<String, Best> perCluster = new LinkedHashMap<>();
            for (String cid : candidates) {
                FellegiSunterScorer.Score s = scorer.score(p, records.get(cid));
                String root = clusters.find(cid);
                Best prev = perCluster.get(root);
                if (prev == null || s.total() > prev.score.total()) {
                    perCluster.put(root, new Best(cid, s));
                }
            }
            index(p);
            List<Best> ranked = new ArrayList<>(perCluster.values());
            ranked.sort(Comparator.comparingDouble((Best b) -> b.score.total()).reversed());

            if (ranked.isEmpty()) {
                return new MatchOutcome(p.recordId(), MatchDecision.NEW, clusters.find(p.recordId()), null,
                        Double.NEGATIVE_INFINITY, Map.of(), 0, List.of());
            }
            Best best = ranked.get(0);
            MatchDecision decision = scorer.decide(best.score.total());
            List<String> tasks = new ArrayList<>();
            if (decision == MatchDecision.AUTO_LINK) {
                clusters.union(best.recordId, p.recordId());
            } else if (decision == MatchDecision.REVIEW) {
                tasks.add(openReview(p.recordId(), best));
            }
            for (int i = 1; i < ranked.size(); i++) {
                Best other = ranked.get(i);
                if (scorer.decide(other.score.total()) != MatchDecision.NEW) {
                    tasks.add(openReview(p.recordId(), other));
                }
            }
            return new MatchOutcome(p.recordId(), decision, clusters.find(p.recordId()), best.recordId,
                    best.score.total(), best.score.perField(), candidates.size(), List.copyOf(tasks));
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Rebuilds state from persistence without re-scoring: records plus already-decided links. */
    public void restore(List<PatientDemographics> all, List<Map.Entry<String, String>> links) {
        lock.writeLock().lock();
        try {
            all.forEach(this::index);
            links.forEach(e -> clusters.union(e.getKey(), e.getValue()));
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Steward decision on a review task. Merge unions the two clusters (Union-Find keeps transitivity).
     *
     * @return the enterprise id after the decision
     */
    public String resolveReview(String taskId, boolean merge) {
        lock.writeLock().lock();
        try {
            ReviewTask task = reviewQueue.find(taskId)
                    .orElseThrow(() -> new IllegalArgumentException("unknown review task " + taskId));
            if (!reviewQueue.complete(taskId, merge ? ReviewTask.Status.MERGED : ReviewTask.Status.REJECTED)) {
                throw new IllegalStateException("review task already resolved: " + taskId);
            }
            if (merge) {
                return clusters.union(task.candidateRecordId(), task.recordId());
            }
            return clusters.find(task.recordId());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Explicit link (e.g. replaying a persisted decision). */
    public String link(String recordA, String recordB) {
        lock.writeLock().lock();
        try {
            return clusters.union(recordA, recordB);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public String enterpriseIdOf(String recordId) {
        lock.writeLock().lock(); // find() compresses paths, i.e. it writes
        try {
            return clusters.find(recordId);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<String> clusterMembers(String recordId) {
        // UnionFind.find mutates (path compression), so even reads take the write lock
        lock.writeLock().lock();
        try {
            return clusters.members(recordId);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean sameEnterprise(String a, String b) {
        lock.writeLock().lock();
        try {
            return clusters.connected(a, b);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public int clusterCount() {
        lock.readLock().lock();
        try {
            return clusters.componentCount();
        } finally {
            lock.readLock().unlock();
        }
    }

    public Optional<PatientDemographics> record(String recordId) {
        lock.readLock().lock();
        try {
            return Optional.ofNullable(records.get(recordId));
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<ReviewTask> pendingReviews() {
        return reviewQueue.pending();
    }

    /**
     * Removes a record's demographics from matching (crypto-shred / right to erasure, or rolling back a registration
     * whose persistence failed). The id stays in the Union-Find structure so existing links remain explainable, but
     * it can never again be a candidate.
     *
     * @return true if the record was present
     */
    public boolean forget(String recordId) {
        lock.writeLock().lock();
        try {
            PatientDemographics p = records.remove(recordId);
            if (p == null) {
                return false;
            }
            for (String key : BlockingKeys.of(p)) {
                Set<String> ids = blocks.get(key);
                if (ids != null) {
                    ids.remove(recordId);
                    if (ids.isEmpty()) {
                        blocks.remove(key);
                    }
                }
            }
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Number of record ids sharing a blocking key with {@code p} (for blocking-efficiency diagnostics). */
    public int candidateCount(PatientDemographics p) {
        lock.readLock().lock();
        try {
            return candidatesFor(p).size();
        } finally {
            lock.readLock().unlock();
        }
    }

    private Set<String> candidatesFor(PatientDemographics p) {
        Set<String> out = new HashSet<>();
        for (String key : BlockingKeys.of(p)) {
            Set<String> ids = blocks.get(key);
            if (ids != null) {
                out.addAll(ids);
            }
        }
        out.remove(p.recordId());
        return out;
    }

    private void index(PatientDemographics p) {
        records.put(p.recordId(), p);
        clusters.add(p.recordId());
        for (String key : BlockingKeys.of(p)) {
            blocks.computeIfAbsent(key, k -> new HashSet<>()).add(p.recordId());
        }
    }

    private String openReview(String recordId, Best candidate) {
        String id = UUID.randomUUID().toString();
        reviewQueue.enqueue(new ReviewTask(id, recordId, candidate.recordId, candidate.score.total(),
                candidate.score.perField(), ReviewTask.Status.PENDING, clock.instant()));
        return id;
    }

    private record Best(String recordId, FellegiSunterScorer.Score score) {
    }
}
