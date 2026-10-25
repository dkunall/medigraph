package com.medigraph.service.ledger.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medigraph.ledger.BlockProposal;
import com.medigraph.ledger.chain.LedgerEntry;

import java.util.List;

/** Column codec for the TEXT/JSON columns of the ledger tables. */
final class LedgerJson {

    private static final TypeReference<List<LedgerEntry>> ENTRIES = new TypeReference<>() {
    };

    private final ObjectMapper json;

    LedgerJson(ObjectMapper json) {
        this.json = json;
    }

    String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise " + value.getClass().getSimpleName(), e);
        }
    }

    BlockProposal proposal(String text) {
        try {
            return text == null ? null : json.readValue(text, BlockProposal.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("corrupt raft_log command", e);
        }
    }

    List<LedgerEntry> entries(String text) {
        try {
            return json.readValue(text, ENTRIES);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("corrupt ledger_block entries", e);
        }
    }
}
