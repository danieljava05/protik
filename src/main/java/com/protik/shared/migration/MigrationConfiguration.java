package com.protik.shared.migration;

import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class MigrationConfiguration {

    @Bean
    ModuleMigrations moduleMigrations(DataSource dataSource) {
        return new ModuleMigrations(dataSource);
    }
}
