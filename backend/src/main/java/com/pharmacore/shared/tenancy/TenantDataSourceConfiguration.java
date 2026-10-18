package com.pharmacore.shared.tenancy;

import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class TenantDataSourceConfiguration {

    /** Wraps the auto-configured pool so that every connection carries the RLS session variables. */
    @Bean
    static BeanPostProcessor tenantAwareDataSourcePostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource dataSource && !(bean instanceof TenantAwareDataSource)
                        && "dataSource".equals(beanName)) {
                    return new TenantAwareDataSource(dataSource);
                }
                return bean;
            }
        };
    }
}
