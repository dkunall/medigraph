package com.medigraph.service.identity.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medigraph.mpi.MatchField;
import com.medigraph.mpi.ReviewQueue;
import com.medigraph.mpi.ReviewTask;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;


public final class JdbcReviewQueue implements ReviewQueue {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcReviewQueue(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    private final RowMapper<ReviewTask> mapper = (rs, n) -> new ReviewTask(rs.getString("id"), rs.getString("record_id"),
            rs.getString("candidate_record_id"), rs.getDouble("weight"), perField(rs.getString("per_field")),
            ReviewTask.Status.valueOf(rs.getString("status")), rs.getTimestamp("created_at").toInstant());

    private Map<MatchField, Double> perField(String s) {
        try {
            return new EnumMap<>(json.readValue(s, new TypeReference<Map<MatchField, Double>>() { }));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void enqueue(ReviewTask t) {
        try {
            jdbc.update("INSERT INTO review_task (id, record_id, candidate_record_id, weight, per_field, status, created_at) "
                            + "VALUES (?,?,?,?,?,?,?)", t.id(), t.recordId(), t.candidateRecordId(), t.weight(),
                    json.writeValueAsString(t.perField()), t.status().name(), java.sql.Timestamp.from(t.createdAt()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Optional<ReviewTask> find(String taskId) {
        return jdbc.query("SELECT * FROM review_task WHERE id = ?", mapper, taskId).stream().findFirst();
    }

    @Override
    public List<ReviewTask> pending() {
        return jdbc.query("SELECT * FROM review_task WHERE status = 'PENDING' ORDER BY created_at", mapper);
    }

    @Override
    public boolean complete(String taskId, ReviewTask.Status status) {
        return jdbc.update("UPDATE review_task SET status = ?, resolved_at = now() WHERE id = ? AND status = 'PENDING'",
                status.name(), taskId) == 1;
    }
}
