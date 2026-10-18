package com.pharmacore.shared.context;

import java.util.Objects;
import java.util.UUID;

/** Immutable context propagated through {@link TenantContext} for the duration of a unit of work. */
public record RequestContext(String correlationId, Actor actor) {

    public RequestContext {
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(actor, "actor");
    }

    public static RequestContext of(Actor actor) {
        return new RequestContext(UUID.randomUUID().toString(), actor);
    }
}
