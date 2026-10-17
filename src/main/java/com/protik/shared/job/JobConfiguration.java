package com.protik.shared.job;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JobProperties.class)
class JobConfiguration {

    @Bean
    JobStore jobStore(JdbcClient jdbcClient) {
        return new JobStore(jdbcClient);
    }

    /** Off in processes that should only enqueue (protik.jobs.enabled=false). */
    @Bean
    @ConditionalOnProperty(name = "protik.jobs.enabled", havingValue = "true", matchIfMissing = true)
    JobWorker jobWorker(JobStore store, ObjectProvider<JobHandler> handlers, JobProperties props) {
        return new JobWorker(store, handlers.orderedStream().toList(), props);
    }
}
