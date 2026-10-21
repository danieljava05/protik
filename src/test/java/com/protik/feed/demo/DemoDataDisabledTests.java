package com.protik.feed.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.protik.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

/** Sample videos must be impossible to get by accident: they exist only behind an explicit switch. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DemoDataDisabledTests {

    @Autowired
    ApplicationContext context;

    @Test
    void withoutTheSwitchThereIsNoDemoDataComponentAtAll() {
        assertThat(context.getBeanNamesForType(DemoData.class)).isEmpty();
    }
}
