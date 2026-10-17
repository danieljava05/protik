package com.protik.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.protik.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Correlation IDs (CONTRIBUTING section 5) and RFC 9457 errors, including failure paths. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class ApiBehaviourTests {

    private static final String UUID_REGEX =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    @Autowired
    MockMvc mvc;

    // ---- correlation IDs ----

    @Test
    void generatesCorrelationIdWhenAbsent() throws Exception {
        mvc.perform(get("/v1/_test/log"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-Id", org.hamcrest.Matchers.matchesPattern(UUID_REGEX)));
    }

    @Test
    void echoesAWellFormedIncomingCorrelationId() throws Exception {
        mvc.perform(get("/v1/_test/log").header("X-Correlation-Id", "client-abc_123.7"))
                .andExpect(header().string("X-Correlation-Id", "client-abc_123.7"));
    }

    @Test
    void replacesAMalformedCorrelationIdInsteadOfLoggingIt() throws Exception {
        // spaces and an over-long value would allow log injection / flooding
        mvc.perform(get("/v1/_test/log").header("X-Correlation-Id", "bad id with spaces"))
                .andExpect(header().string("X-Correlation-Id", org.hamcrest.Matchers.matchesPattern(UUID_REGEX)));
        mvc.perform(get("/v1/_test/log").header("X-Correlation-Id", "a".repeat(65)))
                .andExpect(header().string("X-Correlation-Id", org.hamcrest.Matchers.matchesPattern(UUID_REGEX)));
    }

    @Test
    void correlationIdAppearsInLogLines(CapturedOutput output) throws Exception {
        mvc.perform(get("/v1/_test/log").header("X-Correlation-Id", "trace-me-42")).andExpect(status().isOk());
        assertThat(output.getAll()).contains("[trace-me-42]").contains("handler-log-line");
    }

    // ---- RFC 9457 problem details ----

    @Test
    void unknownPathIsAProblemDocumentWithCorrelationId() throws Exception {
        mvc.perform(get("/v1/does-not-exist").header("X-Correlation-Id", "abc-1"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.correlationId").value("abc-1"));
    }

    @Test
    void apiExceptionMapsToItsProblemType() throws Exception {
        mvc.perform(get("/v1/_test/api-error"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:protik:problem:conflict"))
                .andExpect(jsonPath("$.title").value("Conflict"))
                .andExpect(jsonPath("$.detail").value("Username already taken."))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void unexpectedExceptionNeverLeaksInternals(CapturedOutput output) throws Exception {
        mvc.perform(get("/v1/_test/boom").header("X-Correlation-Id", "boom-1"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:protik:problem:internal-error"))
                .andExpect(jsonPath("$.correlationId").value("boom-1"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("jdbc"))));
        // ...but the operator can still find it in the log, tied to the same ID
        assertThat(output.getAll()).contains("secret internal detail").contains("[boom-1]");
    }

    @Test
    void validationFailureListsFields() throws Exception {
        mvc.perform(post("/v1/_test/validate").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:protik:problem:validation-failed"))
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    @Test
    void malformedJsonIsA400Problem() throws Exception {
        mvc.perform(post("/v1/_test/validate").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void wrongMethodIsA405Problem() throws Exception {
        mvc.perform(post("/v1/_test/log"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void unsupportedMediaTypeIsA415Problem() throws Exception {
        mvc.perform(post("/v1/_test/validate").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }
}
