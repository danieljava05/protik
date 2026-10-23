package com.protik.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.protik.TestcontainersConfiguration;
import com.protik.identity.service.TokenService;
import com.protik.video.VideoRemoved;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** ADR 0019: reports, the moderator queue, actions that change other modules, and the append-only audit log. */
@SpringBootTest
@AutoConfigureMockMvc
@RecordApplicationEvents
@Import(TestcontainersConfiguration.class)
class ModerationIntegrationTests {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient db;
    @Autowired
    TokenService tokens;
    @Autowired
    ApplicationEvents events;

    private UUID creator;
    private UUID reporter;
    private UUID moderator;
    private UUID video;

    @BeforeEach
    void setUp() {
        // The audit log is append-only even for tests: leave it, and settle old reports so the queue starts empty.
        db.sql("UPDATE moderation.reports SET status = 'DISMISSED' WHERE status = 'OPEN'").update();
        db.sql("DELETE FROM social.comments").update();
        db.sql("DELETE FROM shared.rate_limits").update();
        db.sql("DELETE FROM identity.accounts WHERE username LIKE 'mod\\_%'").update();
        creator = account("creator");
        reporter = account("reporter");
        moderator = account("moderator");
        db.sql("INSERT INTO identity.account_roles (account_id, role) VALUES (:id, 'MODERATOR')").param("id", moderator).update();
        video = video(creator);
    }

    // ---- helpers ----

    private UUID account(String label) {
        UUID id = UUID.randomUUID();
        db.sql("INSERT INTO identity.accounts (id, username) VALUES (:id, :u)").param("id", id)
                .param("u", "mod_" + label + "_" + id.toString().substring(0, 8)).update();
        return id;
    }

    private UUID video(UUID owner) {
        UUID id = UUID.randomUUID();
        db.sql("INSERT INTO video.videos (id, creator_id, description, status, published_at, duration_ms) "
                + "VALUES (:id, :c, 'mod-test', 'PUBLISHED', now(), 9000)").param("id", id).param("c", owner).update();
        return id;
    }

    private UUID comment(UUID videoId, UUID author, UUID parent) {
        UUID id = UUID.randomUUID();
        db.sql("INSERT INTO social.comments (id, video_id, author_id, parent_id, body) VALUES (:id, :v, :a, :p, 'hello')")
                .param("id", id).param("v", videoId).param("a", author).param("p", parent).update();
        return id;
    }

    private String bearer(UUID user) {
        return "Bearer " + tokens.issue(user, UUID.randomUUID()).value();
    }

    private ResultActions report(UUID by, String type, UUID target, String reason) throws Exception {
        return mvc.perform(post("/v1/reports").header("Authorization", bearer(by)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetType\":\"" + type + "\",\"targetId\":\"" + target + "\",\"reason\":\"" + reason
                        + "\",\"details\":\"see it\"}"));
    }

    private UUID reported(UUID by, String type, UUID target, String reason) throws Exception {
        String body = report(by, type, target, reason).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private ResultActions act(UUID by, UUID reportId, String action, String reason) throws Exception {
        return mvc.perform(post("/v1/moderation/reports/" + reportId + "/actions").header("Authorization", bearer(by))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"" + action + "\",\"reason\":" + (reason == null ? "null" : "\"" + reason + "\"") + "}"));
    }

    private long actionsAbout(UUID target) {
        return db.sql("SELECT count(*) FROM moderation.actions WHERE target_id = :id").param("id", target)
                .query(Long.class).single();
    }

    private String videoStatus(UUID id) {
        return db.sql("SELECT status FROM video.videos WHERE id = :id").param("id", id).query(String.class).single();
    }

    private String reportStatus(UUID id) {
        return db.sql("SELECT status FROM moderation.reports WHERE id = :id").param("id", id).query(String.class).single();
    }

    // ---- reporting ----

    @Test
    void anyoneSignedInCanReportAVideoAndTheReportIsOpen() throws Exception {
        UUID id = reported(reporter, "VIDEO", video, "SPAM");
        assertThat(reportStatus(id)).isEqualTo("OPEN");
    }

    @Test
    void reportingNeedsASignedInUser() throws Exception {
        mvc.perform(post("/v1/reports").contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetType\":\"VIDEO\",\"targetId\":\"" + video + "\",\"reason\":\"SPAM\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aSecondOpenReportByTheSamePersonAboutTheSameThingIsAConflict() throws Exception {
        reported(reporter, "VIDEO", video, "SPAM");
        report(reporter, "VIDEO", video, "VIOLENCE").andExpect(status().isConflict());
    }

    @Test
    void youCannotReportYourOwnVideoCommentOrAccount() throws Exception {
        report(creator, "VIDEO", video, "SPAM").andExpect(status().isBadRequest());
        report(creator, "COMMENT", comment(video, creator, null), "SPAM").andExpect(status().isBadRequest());
        report(creator, "ACCOUNT", creator, "SPAM").andExpect(status().isBadRequest());
    }

    @Test
    void unknownTargetsAndBadInputAreRejected() throws Exception {
        report(reporter, "VIDEO", UUID.randomUUID(), "SPAM").andExpect(status().isNotFound());
        report(reporter, "COMMENT", UUID.randomUUID(), "SPAM").andExpect(status().isNotFound());
        report(reporter, "ACCOUNT", UUID.randomUUID(), "SPAM").andExpect(status().isNotFound());
        report(reporter, "VIDEO", video, "BORING").andExpect(status().isBadRequest());
        report(reporter, "PLAYLIST", video, "SPAM").andExpect(status().isBadRequest());
    }

    @Test
    void aPersonCanFileTenReportsAnHourButNotEleven() throws Exception {
        for (int i = 0; i < 10; i++) {
            reported(reporter, "VIDEO", video(creator), "SPAM");
        }
        report(reporter, "VIDEO", video(creator), "SPAM").andExpect(status().isTooManyRequests());
    }

    // ---- who may moderate ----

    @Test
    void ordinaryUsersAndAnonymousCallersCannotUseTheModeratorApi() throws Exception {
        mvc.perform(get("/v1/moderation/reports").header("Authorization", bearer(reporter))).andExpect(status().isForbidden());
        mvc.perform(get("/v1/moderation/reports")).andExpect(status().isUnauthorized());
        UUID id = reported(reporter, "VIDEO", video, "SPAM");
        act(reporter, id, "TAKEDOWN", "because").andExpect(status().isForbidden());
        assertThat(videoStatus(video)).isEqualTo("PUBLISHED");
    }

    @Test
    void anAdminMayModerateToo() throws Exception {
        UUID admin = account("admin");
        db.sql("INSERT INTO identity.account_roles (account_id, role) VALUES (:id, 'ADMIN')").param("id", admin).update();
        mvc.perform(get("/v1/moderation/reports").header("Authorization", bearer(admin))).andExpect(status().isOk());
    }

    @Test
    void aRoleGrantedAfterTheTokenWasIssuedOnlyCountsFromTheNextToken() throws Exception {
        UUID late = account("late");
        String before = bearer(late);
        db.sql("INSERT INTO identity.account_roles (account_id, role) VALUES (:id, 'MODERATOR')").param("id", late).update();
        mvc.perform(get("/v1/moderation/reports").header("Authorization", before)).andExpect(status().isForbidden());
        mvc.perform(get("/v1/moderation/reports").header("Authorization", bearer(late))).andExpect(status().isOk());
    }

    // ---- the queue ----

    @Test
    void childSafetyComesFirstThenOldestFirstAndPagingNeverRepeatsOrSkips() throws Exception {
        UUID a = reported(reporter, "VIDEO", video(creator), "SPAM");
        UUID b = reported(reporter, "VIDEO", video(creator), "HARASSMENT");
        UUID c = reported(reporter, "VIDEO", video(creator), "CHILD_SAFETY");
        UUID d = reported(reporter, "VIDEO", video(creator), "OTHER");
        List<String> seen = new java.util.ArrayList<>();
        String cursor = null;
        do {
            String body = mvc.perform(get("/v1/moderation/reports?limit=3" + (cursor == null ? "" : "&cursor=" + cursor))
                            .header("Authorization", bearer(moderator)))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            seen.addAll(JsonPath.<List<String>>read(body, "$.items[*].id"));
            cursor = JsonPath.read(body, "$.nextCursor");
        } while (cursor != null);
        assertThat(seen).containsExactly(c.toString(), a.toString(), b.toString(), d.toString());
    }

    @Test
    void resolvedReportsLeaveTheQueue() throws Exception {
        UUID id = reported(reporter, "VIDEO", video, "SPAM");
        act(moderator, id, "DISMISS", "fine").andExpect(status().isCreated());
        mvc.perform(get("/v1/moderation/reports").header("Authorization", bearer(moderator)))
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void aBrokenCursorIsRejected() throws Exception {
        mvc.perform(get("/v1/moderation/reports?cursor=nonsense").header("Authorization", bearer(moderator)))
                .andExpect(status().isBadRequest());
    }

    // ---- actions ----

    @Test
    void aTakedownHidesTheVideoAnnouncesItSettlesOtherReportsAndIsAudited() throws Exception {
        UUID first = reported(reporter, "VIDEO", video, "SPAM");
        UUID second = reported(account("other"), "VIDEO", video, "VIOLENCE");
        act(moderator, first, "TAKEDOWN", "clear violation").andExpect(status().isCreated())
                .andExpect(jsonPath("$.action").value("TAKEDOWN")).andExpect(jsonPath("$.actorId").value(moderator.toString()))
                .andExpect(jsonPath("$.reason").value("clear violation"));
        assertThat(videoStatus(video)).isEqualTo("TAKEN_DOWN");
        assertThat(events.stream(VideoRemoved.class).filter(e -> e.videoId().equals(video))).hasSize(1);
        assertThat(reportStatus(first)).isEqualTo("ACTIONED");
        assertThat(reportStatus(second)).isEqualTo("ACTIONED");
        mvc.perform(get("/v1/moderation/audit?targetType=VIDEO&targetId=" + video).header("Authorization", bearer(moderator)))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].reportId").value(first.toString()));
    }

    @Test
    void aReportCanBeResolvedOnlyOnce() throws Exception {
        UUID id = reported(reporter, "VIDEO", video, "SPAM");
        act(moderator, id, "TAKEDOWN", "x").andExpect(status().isCreated());
        act(moderator, id, "DISMISS", "changed my mind").andExpect(status().isConflict());
        assertThat(actionsAbout(video)).isEqualTo(1);
    }

    @Test
    void anActionNeedsAReasonAndMustFitTheTarget() throws Exception {
        UUID id = reported(reporter, "VIDEO", video, "SPAM");
        act(moderator, id, "TAKEDOWN", null).andExpect(status().isBadRequest());
        act(moderator, id, "TAKEDOWN", "   ").andExpect(status().isBadRequest());
        act(moderator, id, "HIDE_COMMENT", "wrong kind").andExpect(status().isBadRequest());
        act(moderator, id, "SUSPEND_ACCOUNT", "wrong kind").andExpect(status().isBadRequest());
        act(moderator, id, "DELETE_EVERYTHING", "no").andExpect(status().isBadRequest());
        act(moderator, UUID.randomUUID(), "DISMISS", "no such report").andExpect(status().isNotFound());
        assertThat(videoStatus(video)).isEqualTo("PUBLISHED");
        assertThat(reportStatus(id)).isEqualTo("OPEN");
        assertThat(actionsAbout(video)).isZero();
    }

    @Test
    void dismissingLeavesTheVideoAlone() throws Exception {
        UUID id = reported(reporter, "VIDEO", video, "SPAM");
        act(moderator, id, "DISMISS", "not a violation").andExpect(status().isCreated());
        assertThat(videoStatus(video)).isEqualTo("PUBLISHED");
        assertThat(reportStatus(id)).isEqualTo("DISMISSED");
        assertThat(events.stream(VideoRemoved.class)).isEmpty();
    }

    @Test
    void hidingACommentHidesItsRepliesToo() throws Exception {
        UUID top = comment(video, creator, null);
        UUID reply = comment(video, account("replier"), top);
        UUID unrelated = comment(video, creator, null);
        UUID id = reported(reporter, "COMMENT", top, "HARASSMENT");
        act(moderator, id, "HIDE_COMMENT", "abusive").andExpect(status().isCreated());
        assertThat(db.sql("SELECT status FROM social.comments WHERE id = :id").param("id", top).query(String.class).single())
                .isEqualTo("HIDDEN");
        assertThat(db.sql("SELECT status FROM social.comments WHERE id = :id").param("id", reply).query(String.class).single())
                .isEqualTo("HIDDEN");
        assertThat(db.sql("SELECT status FROM social.comments WHERE id = :id").param("id", unrelated).query(String.class)
                .single()).isEqualTo("VISIBLE");
        mvc.perform(get("/v1/videos/" + video + "/comments").header("Authorization", bearer(reporter)))
                .andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void suspendingAnAccountEndsItsSessions() throws Exception {
        UUID family = UUID.randomUUID();
        db.sql("INSERT INTO identity.session_families (id, account_id) VALUES (:f, :a)").param("f", family)
                .param("a", creator).update();
        UUID id = reported(reporter, "ACCOUNT", creator, "HARASSMENT");
        act(moderator, id, "SUSPEND_ACCOUNT", "repeat abuse").andExpect(status().isCreated());
        assertThat(db.sql("SELECT status FROM identity.accounts WHERE id = :id").param("id", creator).query(String.class)
                .single()).isEqualTo("SUSPENDED");
        assertThat(db.sql("SELECT revoked_reason FROM identity.session_families WHERE id = :f").param("f", family)
                .query(String.class).single()).isEqualTo("ADMIN");
    }

    // ---- the audit log ----

    @Test
    void theAuditLogCannotBeChangedOrEmptied() throws Exception {
        UUID id = reported(reporter, "VIDEO", video, "SPAM");
        act(moderator, id, "DISMISS", "ok").andExpect(status().isCreated());
        assertThatThrownBy(() -> db.sql("UPDATE moderation.actions SET reason = 'edited'").update())
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> db.sql("DELETE FROM moderation.actions").update()).hasMessageContaining("append-only");
        assertThatThrownBy(() -> db.sql("TRUNCATE moderation.actions").update()).hasMessageContaining("append-only");
    }

    @Test
    void theAuditHistoryNeedsAModeratorAndAValidTarget() throws Exception {
        mvc.perform(get("/v1/moderation/audit?targetType=VIDEO&targetId=" + video).header("Authorization", bearer(reporter)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/v1/moderation/audit?targetType=NOPE&targetId=" + video).header("Authorization", bearer(moderator)))
                .andExpect(status().isBadRequest());
    }
}
