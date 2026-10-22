package com.protik.shared.time;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * One injectable clock, so code that depends on "now" (billing periods, report bounds) can be tested at any
 * moment. Always UTC; the database clock is still used for comparisons done in SQL.
 */
@Configuration(proxyBeanMethods = false)
class ClockConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
