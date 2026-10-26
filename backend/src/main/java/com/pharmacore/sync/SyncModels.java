package com.pharmacore.sync;

import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Wire and in-memory shapes of the change log exchanged between a hub and the cloud. */
public final class SyncModels {

    private SyncModels() {
    }

    /** Position in a node's change log: transactions are ordered by xid, rows by seq within one. */
    public record LogPosition(long xid, long seq) implements Comparable<LogPosition> {

        public static final LogPosition START = new LogPosition(0, 0);

        @Override
        public int compareTo(LogPosition other) {
            int byXid = Long.compare(xid, other.xid);
            return byXid != 0 ? byXid : Long.compare(seq, other.seq);
        }

        public boolean isAfter(LogPosition other) {
            return compareTo(other) > 0;
        }
    }

    /** One captured row change. {@code row} is null for deletes. */
    public record Change(long xid, long seq, UUID tenantId, String table, char op, JsonNode pk, JsonNode row) {

        public LogPosition position() {
            return new LogPosition(xid, seq);
        }
    }

    public record ChangeBatch(List<Change> changes, LogPosition last, boolean hasMore) {

        public static ChangeBatch empty(LogPosition at) {
            return new ChangeBatch(List.of(), at, false);
        }
    }

    public record PushRequest(String nodeId, String appVersion, List<Change> changes) {
    }

    public record PushResponse(long acceptedXid, long acceptedSeq, int applied, int skipped) {
    }

    public record PullResponse(List<Change> changes, long lastXid, long lastSeq, boolean hasMore, boolean resetRequired) {
    }

    /** Full-table page used for the initial bootstrap and for recovery after a purge. */
    public record SnapshotPage(String table, List<JsonNode> rows, String nextAfter, boolean hasMore) {
    }

    public record SnapshotStart(long xid, long seq, List<String> tables) {
    }
}
