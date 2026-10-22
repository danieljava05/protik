package com.protik;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Sample data must be impossible to get by accident: with no switch, nothing is created anywhere. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DemoDataSwitchTests {

    @Autowired
    ApplicationContext context;
    @Autowired
    JdbcClient db;

    @Test
    void withoutTheSwitchNoDemoComponentExistsInAnyModule() {
        List<String> demoRunners = context.getBeansOfType(ApplicationRunner.class).values().stream()
                .map(r -> r.getClass().getSimpleName()).filter(n -> n.startsWith("Demo")).toList();
        assertThat(demoRunners).isEmpty();
    }

    @Test
    void theSwitchIsOffInTheDefaultConfiguration() {
        assertThat(context.getEnvironment().getProperty("protik.demo.seed")).isNull();
    }
}
