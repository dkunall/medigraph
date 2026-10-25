package com.medigraph.service.ledger.adapter.in.web;

import com.medigraph.service.ledger.adapter.wire.RaftWire;
import com.medigraph.service.ledger.application.LedgerReplica;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Raft peer endpoint; {@code /internal/**} requires the SERVICE role (client-credentials token of a peer). */
@Hidden
@RestController
public class RaftMessageController {

    private final LedgerReplica replica;
    private final String nodeId;

    public RaftMessageController(LedgerReplica replica, NodeIdentity identity) {
        this.replica = replica;
        this.nodeId = identity.nodeId();
    }

    @PostMapping("/internal/raft/messages")
    public ResponseEntity<Void> receive(@RequestBody RaftWire message) {
        if (!nodeId.equals(message.to())) {
            return ResponseEntity.unprocessableEntity().build(); // misrouted: wrong peer URL in config
        }
        replica.receive(message.toMessage());
        return ResponseEntity.accepted().build();
    }
}
