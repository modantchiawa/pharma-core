package com.pharmacore.shared.context;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Holder of the current {@link RequestContext} based on {@link ScopedValue} (final since Java 25).
 * <p>
 * Unlike a {@code ThreadLocal}, the binding is immutable, bounded to a lexical scope, cannot leak
 * between pooled threads and is inherited by virtual threads forked through structured concurrency.
 */
public final class TenantContext {

    private static final ScopedValue<RequestContext> CURRENT = ScopedValue.newInstance();

    private TenantContext() {
    }

    public static Optional<RequestContext> current() {
        return CURRENT.isBound() ? Optional.of(CURRENT.get()) : Optional.empty();
    }

    public static RequestContext require() {
        if (!CURRENT.isBound()) {
            throw new MissingTenantContextException("No request context bound to the current scope");
        }
        return CURRENT.get();
    }

    public static Actor actor() {
        return require().actor();
    }

    /** Tenant of the current actor; fails for cross-tenant actors. */
    public static TenantId requireTenant() {
        return actor().tenantId()
                .orElseThrow(() -> new MissingTenantContextException("Current actor is not bound to a tenant"));
    }

    /** Current actor as a pharmacy staff member; fails for platform or system actors. */
    public static Actor.TenantUser requireTenantUser() {
        return switch (actor()) {
            case Actor.TenantUser user -> user;
            case Actor.PlatformOperator operator ->
                    throw new MissingTenantContextException("Platform operator " + operator.subject() + " is not a pharmacy user");
            case Actor.SystemProcess system ->
                    throw new MissingTenantContextException("System process " + system.process() + " is not a pharmacy user");
        };
    }

    public static void run(RequestContext context, Runnable action) {
        ScopedValue.where(CURRENT, context).run(action);
    }

    public static <T> T call(RequestContext context, Supplier<T> action) {
        return ScopedValue.where(CURRENT, context).call(action::get);
    }

    public static <T, X extends Throwable> T callChecked(RequestContext context,
                                                         ScopedValue.CallableOp<T, X> action) throws X {
        return ScopedValue.where(CURRENT, context).call(action);
    }
}
