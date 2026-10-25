package com.medigraph.mpi;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MasterPatientIndexTest {

    private final InMemoryReviewQueue queue = new InMemoryReviewQueue();
    private final MasterPatientIndex mpi = new MasterPatientIndex(MatchConfig.defaults(), queue, java.time.Clock.systemUTC());

    private static PatientDemographics p(String id, String given, String family, String dob, String phone, String city, String facility) {
        return new PatientDemographics(id, given, family, LocalDate.parse(dob), "female", phone, city, facility);
    }

    @Test
    void firstRegistrationIsNew() {
        MatchOutcome o = mpi.register(p("A-1", "Asha", "Verma", "1990-04-12", "9810000001", "Delhi", "A"));
        assertThat(o.decision()).isEqualTo(MatchDecision.NEW);
        assertThat(o.enterpriseId()).isEqualTo("A-1");
        assertThat(o.candidatesScored()).isZero();
    }

    @Test
    void samePersonAtSecondHospitalAutoLinks() {
        mpi.register(p("A-1", "Asha", "Verma", "1990-04-12", "9810000001", "Delhi", "A"));
        MatchOutcome o = mpi.register(p("B-7", "Aasha", "Varma", "1990-04-12", "+91 98100 00001", "New Delhi", "B"));
        assertThat(o.decision()).isEqualTo(MatchDecision.AUTO_LINK);
        assertThat(mpi.sameEnterprise("A-1", "B-7")).isTrue();
        assertThat(o.bestCandidate()).isEqualTo("A-1");
        assertThat(o.explanation()).containsKeys(MatchField.values());
    }

    @Test
    void phoneBlockFindsMarriedNameChange() {
        mpi.register(p("A-1", "Asha", "Verma", "1990-04-12", "9810000001", "Delhi", "A"));
        // family name changed -> different Soundex block; the phone block still finds the candidate
        PatientDemographics married = p("C-3", "Asha", "Kapoor", "1990-04-12", "9810000001", "Delhi", "C");
        assertThat(mpi.candidateCount(married)).isEqualTo(1);
        MatchOutcome o = mpi.register(married);
        assertThat(o.candidatesScored()).isEqualTo(1);
        assertThat(o.decision()).isIn(MatchDecision.AUTO_LINK, MatchDecision.REVIEW);
    }

    @Test
    void ambiguousMatchGoesToReviewQueueAndStewardMerges() {
        mpi.register(p("A-1", "Asha", "Verma", "1990-04-12", "9810000001", "Delhi", "A"));
        MatchOutcome o = mpi.register(p("D-1", "Asha", "Verma", "1990-04-12", "9000000000", "Mumbai", "D"));
        assertThat(o.decision()).isEqualTo(MatchDecision.REVIEW);
        assertThat(o.reviewTaskIds()).hasSize(1);
        assertThat(mpi.sameEnterprise("A-1", "D-1")).isFalse();
        List<ReviewTask> pending = mpi.pendingReviews();
        assertThat(pending).singleElement().satisfies(t -> {
            assertThat(t.recordId()).isEqualTo("D-1");
            assertThat(t.candidateRecordId()).isEqualTo("A-1");
        });
        mpi.resolveReview(pending.get(0).id(), true);
        assertThat(mpi.sameEnterprise("A-1", "D-1")).isTrue();
        assertThat(mpi.pendingReviews()).isEmpty();
        assertThatThrownBy(() -> mpi.resolveReview(pending.get(0).id(), true)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void stewardRejectKeepsClustersApart() {
        mpi.register(p("A-1", "Asha", "Verma", "1990-04-12", "9810000001", "Delhi", "A"));
        MatchOutcome o = mpi.register(p("D-1", "Asha", "Verma", "1990-04-12", "9000000000", "Mumbai", "D"));
        mpi.resolveReview(o.reviewTaskIds().get(0), false);
        assertThat(mpi.sameEnterprise("A-1", "D-1")).isFalse();
        assertThat(queue.find(o.reviewTaskIds().get(0)).orElseThrow().status()).isEqualTo(ReviewTask.Status.REJECTED);
    }

    @Test
    void unionFindKeepsClustersTransitive() {
        mpi.register(p("A-1", "Asha", "Verma", "1990-04-12", "9810000001", "Delhi", "A"));
        mpi.register(p("B-1", "Asha", "Verma", "1990-04-12", "9810000001", "Delhi", "B"));
        mpi.register(p("C-1", "Asha", "Verma", "1990-04-12", null, "Delhi", "C"));
        assertThat(mpi.clusterMembers("C-1")).containsExactlyInAnyOrder("A-1", "B-1", "C-1");
        assertThat(mpi.clusterCount()).isEqualTo(1);
    }

    @Test
    void forgottenRecordIsNeverACandidateAgain() {
        mpi.register(p("A-1", "Asha", "Verma", "1990-04-12", "9810000001", "Delhi", "A"));
        assertThat(mpi.forget("A-1")).isTrue();
        assertThat(mpi.forget("A-1")).isFalse();
        assertThat(mpi.record("A-1")).isEmpty();
        MatchOutcome o = mpi.register(p("B-7", "Asha", "Verma", "1990-04-12", "9810000001", "Delhi", "B"));
        assertThat(o.decision()).isEqualTo(MatchDecision.NEW);
        assertThat(o.candidatesScored()).isZero();
    }

    @Test
    void duplicateRecordIdRejected() {
        mpi.register(p("A-1", "Asha", "Verma", "1990-04-12", "9810000001", "Delhi", "A"));
        assertThatThrownBy(() -> mpi.register(p("A-1", "Asha", "Verma", "1990-04-12", "9810000001", "Delhi", "A")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void restoreRebuildsClustersWithoutRescoring() {
        MasterPatientIndex fresh = new MasterPatientIndex(MatchConfig.defaults());
        fresh.restore(List.of(p("x", "A", "B", "1990-01-01", null, null, "A"), p("y", "Q", "Z", "1970-01-01", null, null, "B")),
                List.of(Map.entry("x", "y")));
        assertThat(fresh.sameEnterprise("x", "y")).isTrue();
    }

    @Test
    void syntheticPopulationRecallAndBlockingReduction() {
        // 500 people, each registered twice with realistic noise; checks recall and that blocking prunes comparisons.
        String[] given = {"Asha", "Rohan", "Priya", "Vikram", "Neha", "Arjun", "Kavya", "Ishaan", "Meera", "Kabir"};
        String[] family = {"Verma", "Sharma", "Iyer", "Reddy", "Nair", "Gupta", "Khan", "Das", "Patel", "Singh",
                "Mehta", "Bose", "Joshi", "Rao", "Kulkarni", "Chopra", "Malhotra", "Pillai", "Saxena", "Bhat"};
        Random r = new Random(11);
        int people = 500;
        int autoLinked = 0;
        long comparisons = 0;
        for (int i = 0; i < people; i++) {
            String g = given[r.nextInt(given.length)];
            String f = family[r.nextInt(family.length)];
            LocalDate dob = LocalDate.of(1950 + r.nextInt(50), 1 + r.nextInt(12), 1 + r.nextInt(28));
            String phone = String.valueOf(9_000_000_000L + i * 7919L);
            mpi.register(new PatientDemographics("A-" + i, g, f, dob, "female", phone, "Delhi", "A"));
            String noisyGiven = r.nextBoolean() ? g : g + "a";
            MatchOutcome o = mpi.register(new PatientDemographics("B-" + i, noisyGiven, f, dob, "female",
                    "+91 " + phone, "New Delhi", "B"));
            comparisons += o.candidatesScored();
            if (mpi.sameEnterprise("A-" + i, "B-" + i)) {
                autoLinked++;
            }
        }
        assertThat(autoLinked).isEqualTo(people);
        // naive all-pairs would be ~ people^2 comparisons; blocking keeps it near-linear
        assertThat(comparisons).isLessThan((long) people * 20);
    }
}
