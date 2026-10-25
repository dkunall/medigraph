package com.medigraph.service.ledger.adapter.in.web;

import java.util.Map;

/** This node's Raft id and the peers' base URLs (to turn a leader id into a redirect hint). */
public record NodeIdentity(String nodeId, Map<String, String> peerUrls) {
}
