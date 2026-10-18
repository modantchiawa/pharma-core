package com.pharmacore.shared.tenancy;

import com.pharmacore.shared.context.Actor;
import com.pharmacore.shared.context.RequestContext;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.context.TenantId;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.ConnectionProxy;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Binds the PostgreSQL session variables read by the Row-Level Security policies
 * ({@code app.tenant_id}, {@code app.platform_admin}) every time a connection is borrowed from
 * the pool, and clears them when it is returned.
 * <p>
 * Applies to every access path (JPA, JdbcTemplate, native queries) so that a missing
 * {@code WHERE tenant_id = ?} can never leak another pharmacy's data. Without a bound context
 * the session sees no tenant data at all (fail closed).
 */
public class TenantAwareDataSource extends DelegatingDataSource {

    private static final String BIND_SQL =
            "SELECT set_config('app.tenant_id', ?, false), set_config('app.platform_admin', ?, false)";
    private static final String RESET_SQL =
            "SELECT set_config('app.tenant_id', '', false), set_config('app.platform_admin', 'off', false)";

    public TenantAwareDataSource(DataSource target) {
        super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return bind(obtainTargetDataSource().getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return bind(obtainTargetDataSource().getConnection(username, password));
    }

    private Connection bind(Connection connection) throws SQLException {
        var actor = TenantContext.current().map(RequestContext::actor);
        String tenant = actor.flatMap(Actor::tenantId).map(TenantId::toString).orElse("");
        boolean crossTenant = actor.map(Actor::crossTenant).orElse(false);
        try (PreparedStatement statement = connection.prepareStatement(BIND_SQL)) {
            statement.setString(1, tenant);
            statement.setString(2, crossTenant ? "on" : "off");
            statement.execute();
            if (!connection.getAutoCommit()) {
                connection.commit();
            }
        } catch (SQLException | RuntimeException e) {
            connection.close();
            throw e;
        }
        return (Connection) Proxy.newProxyInstance(
                ConnectionProxy.class.getClassLoader(),
                new Class<?>[] {ConnectionProxy.class},
                new ResettingHandler(connection));
    }

    private record ResettingHandler(Connection target) implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "getTargetConnection" -> {
                    return target;
                }
                case "equals" -> {
                    return proxy == args[0];
                }
                case "hashCode" -> {
                    return System.identityHashCode(proxy);
                }
                case "unwrap" -> {
                    if (((Class<?>) args[0]).isInstance(proxy)) {
                        return proxy;
                    }
                }
                case "isWrapperFor" -> {
                    if (((Class<?>) args[0]).isInstance(proxy)) {
                        return true;
                    }
                }
                case "close" -> {
                    reset();
                    target.close();
                    return null;
                }
                default -> {
                    // delegated below
                }
            }
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getTargetException();
            }
        }

        private void reset() {
            try {
                if (!target.isClosed() && target.getAutoCommit()) {
                    try (PreparedStatement statement = target.prepareStatement(RESET_SQL)) {
                        statement.execute();
                    }
                }
            } catch (SQLException ignored) {
                // The next borrower always re-binds both variables, so a failed reset cannot leak.
            }
        }
    }
}
