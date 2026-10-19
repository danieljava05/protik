package com.protik.shared;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.protik.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** The spec is generated from the code and is limited to the public /v1 API. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OpenApiTests {

    @Autowired
    MockMvc mvc;

    @Test
    void specIsGeneratedWithProjectInfo() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").value(org.hamcrest.Matchers.startsWith("3.")))
                .andExpect(jsonPath("$.info.title").value("ProTik API"))
                .andExpect(jsonPath("$.info.license.name").value("Apache-2.0"));
    }

    @Test
    void specDescribesV1EndpointsOnly() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.paths['/v1/_test/api-error']").exists())
                .andExpect(jsonPath("$.paths['/v1/auth/register']").exists())
                .andExpect(jsonPath("$.paths['/v1/auth/login']").exists())
                .andExpect(jsonPath("$.paths['/v1/me']").exists())
                .andExpect(jsonPath("$.paths['/internal/_test/outside-v1']").doesNotExist())
                .andExpect(jsonPath("$.paths['/actuator/health']").doesNotExist());
    }
}
