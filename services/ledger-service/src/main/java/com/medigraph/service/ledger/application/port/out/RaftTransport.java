package com.medigraph.service.ledger.application.port.out;

import com.medigraph.ledger.raft.RaftMessage;

/**
 * Fire-and-forget delivery of a Raft RPC to {@code message.to()}. Must not block the caller: the Raft core holds
 * its lock while sending. Loss, duplication and reordering are tolerated by the protocol.
 */
public interface RaftTransport {

    void send(RaftMessage message);
}
