package deploygate.controller;

import deploygate.config.DemoDataSeeder;
import deploygate.dto.ApprovalActionResponse;
import deploygate.dto.ApprovalCheckRequest;
import deploygate.dto.ApprovalCheckResponse;
import deploygate.dto.ApprovalHistoryEntry;
import deploygate.dto.ApprovalRequestCreateRequest;
import deploygate.dto.ApprovalRequestResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApprovalControllerTest {

    private static final String CI_TOKEN = "test-ci-service-token";
    private static final String DUAL_SHA = "9f1c2ab";

    @Autowired
    private TestRestTemplate restTemplate;

    private static HttpHeaders headers(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private <T> ResponseEntity<T> post(String path, String token, Object body, Class<T> responseType) {
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers(token)), responseType);
    }

    private <T> ResponseEntity<T> postNoBody(String path, String token, Class<T> responseType) {
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(headers(token)), responseType);
    }

    private <T> ResponseEntity<T> get(String path, String token, Class<T> responseType) {
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(token)), responseType);
    }

    /** Creates a fresh pending request as jiye via the CI token. */
    private ApprovalRequestResponse createRequest(String stack, String action) {
        return post("/approval/request", CI_TOKEN,
                new ApprovalRequestCreateRequest("jiye", stack, action), ApprovalRequestResponse.class).getBody();
    }

    /** ProdAlbStack is seeded with requireCommitSha, so its requests must name a commit. */
    private ApprovalRequestResponse createProdRequest(String action, String commitSha) {
        return post("/approval/request", CI_TOKEN,
                new ApprovalRequestCreateRequest("jiye", "ProdAlbStack", action, commitSha),
                ApprovalRequestResponse.class).getBody();
    }

    private ResponseEntity<ApprovalCheckResponse> checkProd(String action, String commitSha) {
        return post("/approval/check", CI_TOKEN,
                new ApprovalCheckRequest("jiye", "ProdAlbStack", action, commitSha), ApprovalCheckResponse.class);
    }

    // --- authentication / authorization ---------------------------------------

    @Test
    void rejectsRequestWithoutToken() {
        ResponseEntity<String> response = restTemplate.postForEntity("/approval/check",
                new ApprovalCheckRequest("jiye", "SmallAppStack", "DEPLOY"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsUnknownToken() {
        ResponseEntity<String> response = post("/approval/check", "dgt_not_a_real_token",
                new ApprovalCheckRequest("jiye", "SmallAppStack", "DEPLOY"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void ciServiceTokenCannotCastApprovalVote() {
        ApprovalRequestResponse created = createProdRequest("DEPLOY_CI_VOTE", "aaa1111");

        ResponseEntity<String> response = postNoBody("/approval/" + created.id() + "/approve", CI_TOKEN, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void personalTokenCannotActOnBehalfOfAnotherDeployer() {
        ResponseEntity<String> response = post("/approval/check", DemoDataSeeder.ALICE_TOKEN,
                new ApprovalCheckRequest("jiye", "SmallAppStack", "DEPLOY"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void deployerCannotApproveTheirOwnRequest() {
        // jiye holds ProdAlbStack:deploy but must not be able to wave through their own deploy.
        ApprovalRequestResponse created = createProdRequest("DEPLOY_SELF", "bbb2222");

        ResponseEntity<String> response =
                postNoBody("/approval/" + created.id() + "/approve", DemoDataSeeder.JIYE_TOKEN, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void historyRequiresAdminReadClaim() {
        // bob authenticates fine but holds no admin:read claim.
        ResponseEntity<String> response = get("/approval/history", DemoDataSeeder.BOB_TOKEN, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // --- decision flow ---------------------------------------------------------

    @Test
    void allowsSmallAppStackDeployForJiye() {
        ResponseEntity<ApprovalCheckResponse> response = post("/approval/check", CI_TOKEN,
                new ApprovalCheckRequest("jiye", "SmallAppStack", "DEPLOY"), ApprovalCheckResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().result()).isEqualTo("ALLOWED");
    }

    @Test
    void checkReturnsPendingWhenApprovalRequired() {
        ResponseEntity<ApprovalCheckResponse> response = checkProd("DEPLOY", "ccc3333");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().result()).isEqualTo("PENDING");
    }

    @Test
    void blocksUnknownDeployer() {
        ResponseEntity<ApprovalCheckResponse> response = post("/approval/check", CI_TOKEN,
                new ApprovalCheckRequest("ghost", "SmallAppStack", "DEPLOY"), ApprovalCheckResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void blocksUnknownStack() {
        ResponseEntity<ApprovalCheckResponse> response = post("/approval/check", CI_TOKEN,
                new ApprovalCheckRequest("jiye", "UnknownStack", "DEPLOY"), ApprovalCheckResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void singleApprover_fullFlow_requestApproveRecheckReturns200() {
        ApprovalRequestResponse created = createRequest("StagingApiStack", "DEPLOY_S1");
        assertThat(created.status()).isEqualTo("PENDING");

        ResponseEntity<ApprovalActionResponse> approveResponse =
                postNoBody("/approval/" + created.id() + "/approve", DemoDataSeeder.ALICE_TOKEN, ApprovalActionResponse.class);
        assertThat(approveResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(approveResponse.getBody().status()).isEqualTo("APPROVED");

        ResponseEntity<ApprovalCheckResponse> recheck = post("/approval/check", CI_TOKEN,
                new ApprovalCheckRequest("jiye", "StagingApiStack", "DEPLOY_S1"), ApprovalCheckResponse.class);
        assertThat(recheck.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(recheck.getBody().result()).isEqualTo("ALLOWED");
    }

    @Test
    void dualApprover_staysPendingAfterOneOfTwoApprovals() {
        ApprovalRequestResponse created = createProdRequest("DEPLOY_D1", DUAL_SHA);

        ResponseEntity<ApprovalActionResponse> firstApproval =
                postNoBody("/approval/" + created.id() + "/approve", DemoDataSeeder.ALICE_TOKEN, ApprovalActionResponse.class);
        assertThat(firstApproval.getBody().status()).isEqualTo("PENDING");

        ResponseEntity<ApprovalCheckResponse> recheck = checkProd("DEPLOY_D1", DUAL_SHA);
        assertThat(recheck.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        ResponseEntity<ApprovalActionResponse> secondApproval =
                postNoBody("/approval/" + created.id() + "/approve", DemoDataSeeder.BOB_TOKEN, ApprovalActionResponse.class);
        assertThat(secondApproval.getBody().status()).isEqualTo("APPROVED");

        ResponseEntity<ApprovalCheckResponse> finalCheck = checkProd("DEPLOY_D1", DUAL_SHA);
        assertThat(finalCheck.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void reject_immediatelyDeniesRecheck() {
        ApprovalRequestResponse created = createProdRequest("DEPLOY_D2", "ddd4444");

        ResponseEntity<ApprovalActionResponse> rejectResponse =
                postNoBody("/approval/" + created.id() + "/reject", DemoDataSeeder.ALICE_TOKEN, ApprovalActionResponse.class);
        assertThat(rejectResponse.getBody().status()).isEqualTo("REJECTED");

        ResponseEntity<ApprovalCheckResponse> recheck = checkProd("DEPLOY_D2", "ddd4444");
        assertThat(recheck.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void approve_duplicateVoteReturns409() {
        ApprovalRequestResponse created = createProdRequest("DEPLOY_D3", "eee5555");

        postNoBody("/approval/" + created.id() + "/approve", DemoDataSeeder.ALICE_TOKEN, ApprovalActionResponse.class);
        ResponseEntity<String> duplicate =
                postNoBody("/approval/" + created.id() + "/approve", DemoDataSeeder.ALICE_TOKEN, String.class);

        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void approve_unknownRequestIdReturns404() {
        ResponseEntity<String> response = postNoBody("/approval/999999/approve", DemoDataSeeder.ALICE_TOKEN, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void approve_approverWithoutStackApproveClaimReturns403() {
        // bob may approve ProdAlbStack but holds no StagingApiStack:approve claim.
        ApprovalRequestResponse created = createRequest("StagingApiStack", "DEPLOY_S2");

        ResponseEntity<String> response =
                postNoBody("/approval/" + created.id() + "/approve", DemoDataSeeder.BOB_TOKEN, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // --- approval scope: commit binding, single use, replay ---------------------

    @Test
    void prodCheckWithoutCommitShaIsDenied() {
        // ProdAlbStack is seeded with requireCommitSha, so an unscoped check must fail closed.
        ResponseEntity<ApprovalCheckResponse> response = post("/approval/check", CI_TOKEN,
                new ApprovalCheckRequest("jiye", "ProdAlbStack", "DEPLOY_NOSHA"), ApprovalCheckResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().reason()).contains("commitSha");
    }

    @Test
    void prodRequestWithoutCommitShaIsRejected() {
        ResponseEntity<String> response = post("/approval/request", CI_TOKEN,
                new ApprovalRequestCreateRequest("jiye", "ProdAlbStack", "DEPLOY_NOSHA2"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void singleUseApprovalIsSpentByTheDeployItAuthorises() {
        String sha = "1a2b3c4";
        ApprovalRequestResponse created = createProdRequest("DEPLOY_ONCE", sha);
        postNoBody("/approval/" + created.id() + "/approve", DemoDataSeeder.ALICE_TOKEN, ApprovalActionResponse.class);
        postNoBody("/approval/" + created.id() + "/approve", DemoDataSeeder.BOB_TOKEN, ApprovalActionResponse.class);

        assertThat(checkProd("DEPLOY_ONCE", sha).getStatusCode()).isEqualTo(HttpStatus.OK);

        // The approval authorised one deploy; replaying it must not authorise a second.
        ResponseEntity<ApprovalCheckResponse> replay = checkProd("DEPLOY_ONCE", sha);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(replay.getBody().reason()).contains("already spent");
    }

    @Test
    void approvalDoesNotCarryOverToADifferentCommit() {
        ApprovalRequestResponse created = createProdRequest("DEPLOY_SHA_SCOPE", "cafe111");
        postNoBody("/approval/" + created.id() + "/approve", DemoDataSeeder.ALICE_TOKEN, ApprovalActionResponse.class);
        postNoBody("/approval/" + created.id() + "/approve", DemoDataSeeder.BOB_TOKEN, ApprovalActionResponse.class);

        // Same stack, same action, approved — but a different commit needs its own approval.
        ResponseEntity<ApprovalCheckResponse> otherCommit = checkProd("DEPLOY_SHA_SCOPE", "beef222");

        assertThat(otherCommit.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(otherCommit.getBody().result()).isEqualTo("PENDING");
    }

    @Test
    void nonSingleUseApprovalStillAllowsPipelineRetries() {
        // StagingApiStack is not single-use, so a retried deploy of the same approval works.
        ApprovalRequestResponse created = createRequest("StagingApiStack", "DEPLOY_RETRY");
        postNoBody("/approval/" + created.id() + "/approve", DemoDataSeeder.ALICE_TOKEN, ApprovalActionResponse.class);

        for (int attempt = 0; attempt < 2; attempt++) {
            ResponseEntity<ApprovalCheckResponse> recheck = post("/approval/check", CI_TOKEN,
                    new ApprovalCheckRequest("jiye", "StagingApiStack", "DEPLOY_RETRY"), ApprovalCheckResponse.class);
            assertThat(recheck.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    void twoApproversVotingAtOnceStillReachApproved() throws Exception {
        // The vote tally is read-then-decide: without a guard, two simultaneous votes can
        // each see only their own and leave a fully approved request stuck at PENDING.
        String sha = "race001";
        ApprovalRequestResponse created = createProdRequest("DEPLOY_RACE", sha);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            List<Future<ResponseEntity<String>>> votes = List.of(
                    pool.submit(voteAt(barrier, created.id(), DemoDataSeeder.ALICE_TOKEN)),
                    pool.submit(voteAt(barrier, created.id(), DemoDataSeeder.BOB_TOKEN)));

            List<String> losers = new ArrayList<>();
            if (votes.get(0).get().getStatusCode() == HttpStatus.CONFLICT) {
                losers.add(DemoDataSeeder.ALICE_TOKEN);
            }
            if (votes.get(1).get().getStatusCode() == HttpStatus.CONFLICT) {
                losers.add(DemoDataSeeder.BOB_TOKEN);
            }
            // A loser is told to retry, and the retry sees the winner's committed vote.
            for (String token : losers) {
                postNoBody("/approval/" + created.id() + "/approve", token, ApprovalActionResponse.class);
            }
        } finally {
            pool.shutdownNow();
        }

        // Two distinct approvals happened, so the request must be usable -- never left pending.
        ResponseEntity<ApprovalCheckResponse> check = checkProd("DEPLOY_RACE", sha);
        assertThat(check.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(check.getBody().result()).isEqualTo("ALLOWED");
    }

    private Callable<ResponseEntity<String>> voteAt(CyclicBarrier barrier, Long requestId, String token) {
        return () -> {
            barrier.await();
            return postNoBody("/approval/" + requestId + "/approve", token, String.class);
        };
    }

    @Test
    void historyEndpointReturnsEntriesFilteredByStack() {
        post("/approval/check", CI_TOKEN,
                new ApprovalCheckRequest("jiye", "SmallAppStack", "DEPLOY"), ApprovalCheckResponse.class);

        ResponseEntity<ApprovalHistoryEntry[]> response =
                get("/approval/history?stack=SmallAppStack", DemoDataSeeder.JIYE_TOKEN, ApprovalHistoryEntry[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotEmpty();
        assertThat(response.getBody()[0].stack()).isEqualTo("SmallAppStack");
    }
}
