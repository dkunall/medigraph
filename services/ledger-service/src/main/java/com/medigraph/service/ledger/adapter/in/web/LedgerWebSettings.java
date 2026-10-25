package com.medigraph.service.ledger.adapter.in.web;

import java.time.Duration;

public record LedgerWebSettings(Duration commitTimeout) {
}
