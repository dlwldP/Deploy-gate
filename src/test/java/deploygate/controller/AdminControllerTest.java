package deploygate.controller;

import deploygate.config.DemoDataSeeder;
import deploygate.dto.DeployerSummary;
import deploygate.dto.StackPolicySummary;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminControllerTest {

    @Autowired
    private TestRestTemplate restTemplate;

    private <T> ResponseEntity<T> get(String path, String token, Class<T> responseType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), responseType);
    }

    @Test
    void listsSeededDeployersWithClaims() {
        ResponseEntity<DeployerSummary[]> response =
                get("/admin/deployers", DemoDataSeeder.JIYE_TOKEN, DeployerSummary[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(Arrays.stream(response.getBody()).map(DeployerSummary::name)).contains("jiye", "alice", "bob");
        DeployerSummary jiye = Arrays.stream(response.getBody())
                .filter(deployer -> deployer.name().equals("jiye"))
                .findFirst().orElseThrow();
        assertThat(jiye.claims()).contains("stack:SmallAppStack:deploy");
    }

    @Test
    void listsSeededStackPolicies() {
        ResponseEntity<StackPolicySummary[]> response =
                get("/admin/stack-policies", DemoDataSeeder.JIYE_TOKEN, StackPolicySummary[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(Arrays.stream(response.getBody()).map(StackPolicySummary::stackName))
                .contains("SmallAppStack", "StagingApiStack", "ProdAlbStack");
        StackPolicySummary prodAlb = Arrays.stream(response.getBody())
                .filter(policy -> policy.stackName().equals("ProdAlbStack"))
                .findFirst().orElseThrow();
        assertThat(prodAlb.approvalLevel()).isEqualTo("DUAL_APPROVER");
    }

    @Test
    void rejectsCallerWithoutAdminReadClaim() {
        ResponseEntity<String> response = get("/admin/deployers", DemoDataSeeder.BOB_TOKEN, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void rejectsAnonymousCaller() {
        ResponseEntity<String> response = restTemplate.getForEntity("/admin/deployers", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void neverExposesApiTokenHash() {
        // DeployerSummary must not leak credential material, even hashed.
        ResponseEntity<String> response = get("/admin/deployers", DemoDataSeeder.JIYE_TOKEN, String.class);

        assertThat(response.getBody()).doesNotContain("apiTokenHash");
    }
}
