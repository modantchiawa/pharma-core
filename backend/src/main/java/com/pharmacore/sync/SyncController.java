package com.pharmacore.sync;

import com.pharmacore.sync.SyncModels.PullResponse;
import com.pharmacore.sync.SyncModels.PushRequest;
import com.pharmacore.sync.SyncModels.PushResponse;
import com.pharmacore.sync.SyncModels.SnapshotPage;
import com.pharmacore.sync.SyncModels.SnapshotStart;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Cloud endpoints called by paired hubs (authority ROLE_HUB_NODE). */
@RestController
@RequestMapping("/api/sync")
class SyncController {

    private final CloudSyncService sync;

    SyncController(CloudSyncService sync) {
        this.sync = sync;
    }

    @PostMapping("/push")
    PushResponse push(@Valid @RequestBody PushBody body) {
        return sync.push(new PushRequest(body.nodeId(), body.appVersion(), body.changes()));
    }

    @GetMapping("/pull")
    PullResponse pull(@RequestParam(defaultValue = "0") long afterXid,
                      @RequestParam(defaultValue = "0") long afterSeq,
                      @RequestParam(defaultValue = "500") int limit) {
        return sync.pull(new SyncModels.LogPosition(afterXid, afterSeq), limit);
    }

    @GetMapping("/snapshot")
    SnapshotStart snapshotStart(@RequestParam(defaultValue = "false") boolean includeHubOwned) {
        return sync.snapshotStart(includeHubOwned);
    }

    @GetMapping("/snapshot/{table}")
    SnapshotPage snapshot(@PathVariable String table,
                          @RequestParam(required = false) String after,
                          @RequestParam(defaultValue = "500") int limit) {
        return sync.snapshot(table, after, limit);
    }

    /** A batch split across a foreign-key dependency: the hub retries with a larger batch. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail integrity(DataIntegrityViolationException e) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMostSpecificCause().getMessage());
        problem.setType(URI.create("https://pharmacore.io/problems/sync-integrity"));
        problem.setProperty("code", "sync-integrity");
        return problem;
    }

    record PushBody(String nodeId, @Size(max = 40) String appVersion,
                    @NotNull @Size(max = CloudSyncService.MAX_BATCH) List<SyncModels.Change> changes) {
    }
}
