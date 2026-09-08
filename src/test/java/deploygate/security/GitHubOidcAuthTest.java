package deploygate.security;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import deploygate.config.DemoDataSeeder;
import deploygate.dto.ApprovalCheckRequest;
import deploygate.dto.ApprovalCheckResponse;
import deploygate.dto.ApprovalRequestCreateRequest;
import deploygate.dto.ApprovalRequestResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.TestPropertySource;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the OIDC path with real signed tokens: the pipeline authenticates with a
 * GitHub-signed assertion rather than a shared secret, and the actor and commit it
 * carries are binding.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "deploygate.ci.oidc.issuer=https://token.actions.githubusercontent.com",
        "deploygate.ci.oidc.audience=deploy-gate",
        "deploygate.ci.oidc.allowed-repositories=dlwldP/Deploy-gate"
})
class GitHubOidcAuthTest {

    private static final String ISSUER = "https://token.actions.githubusercontent.com";
    private static final String AUDIENCE = "deploy-gate";
    private static final String REPOSITORY = "dlwldP/Deploy-gate";

    private static KeyPair keyPair;

    @Autowired
    private TestRestTemplate restTemplate;

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
    }

    /** Stands in for GitHub's published JWKS, so no network call is needed. */
    @TestConfiguration
    static class TestDecoderConfig {
        @Bean
        JwtDecoder testJwtDecoder() {
            return NimbusJwtDecoder.withPublicKey((RSAPublicKey) keyPair.getPublic()).build();
        }
    }

    private String token(String actor, String repository, String sha, String audience) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(audience)
                .subject("repo:" + repository + ":ref:refs/heads/main")
                .claim("actor", actor)
                .claim("repository", repository)
                .claim("sha", sha)
                .claim("workflow", "deploy")
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).build(), claims);
        jwt.sign(new RSASSASigner((RSAPrivateKey) keyPair.getPrivate()));
        return jwt.serialize();
    }

    private String validToken(String actor, String sha) throws Exception {
        return token(actor, REPOSITORY, sha, AUDIENCE);
    }

    private <T> ResponseEntity<T> post(String path, String token, Object body, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), type);
    }

    @Test
    void oidcTokenAuthenticatesThePipeline() throws Exception {
        ResponseEntity<ApprovalCheckResponse> response = post("/approval/check", validToken("jiye", "abc1234"),
                new ApprovalCheckRequest("jiye", "SmallAppStack", "DEPLOY"), ApprovalCheckResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().result()).isEqualTo("ALLOWED");
    }

    @Test
    void oidcCallerCannotActAsSomeoneElse() throws Exception {
        // The shared CI token may name anyone; a GitHub-attested actor may not.
        ResponseEntity<String> response = post("/approval/check", validToken("alice", "abc1234"),
                new ApprovalCheckRequest("jiye", "SmallAppStack", "DEPLOY"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void oidcCallerCannotClaimADifferentCommit() throws Exception {
        ResponseEntity<String> response = post("/approval/request", validToken("jiye", "attested1"),
                new ApprovalRequestCreateRequest("jiye", "ProdAlbStack", "DEPLOY", "someothersha"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void attestedCommitIsUsedWhenTheBodyOmitsIt() throws Exception {
        // ProdAlbStack requires a commit sha; the token supplies it, so this succeeds.
        ResponseEntity<ApprovalCheckResponse> response = post("/approval/check", validToken("jiye", "attested2"),
                new ApprovalCheckRequest("jiye", "ProdAlbStack", "DEPLOY"), ApprovalCheckResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().result()).isEqualTo("PENDING");
    }

    @Test
    void oidcCallerCannotCastAnApprovalVote() throws Exception {
        ResponseEntity<ApprovalRequestResponse> requested = post("/approval/request", validToken("jiye", "vote1234"),
                new ApprovalRequestCreateRequest("jiye", "ProdAlbStack", "DEPLOY"),
                ApprovalRequestResponse.class);
        assertThat(requested.getStatusCode()).isEqualTo(HttpStatus.OK);

        // alice may approve ProdAlbStack with her personal token, but not through OIDC:
        // a pipeline credential must never be able to vote.
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(validToken("alice", "vote1234"));
        ResponseEntity<String> vote = restTemplate.exchange(
                "/approval/" + requested.getBody().id() + "/approve", HttpMethod.POST,
                new HttpEntity<>(headers), String.class);

        assertThat(vote.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void rejectsTokenFromAnotherRepository() throws Exception {
        ResponseEntity<String> response = post("/approval/check", token("jiye", "someone/else", "abc1234", AUDIENCE),
                new ApprovalCheckRequest("jiye", "SmallAppStack", "DEPLOY"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsTokenMintedForAnotherAudience() throws Exception {
        ResponseEntity<String> response = post("/approval/check", token("jiye", REPOSITORY, "abc1234", "other-service"),
                new ApprovalCheckRequest("jiye", "SmallAppStack", "DEPLOY"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsTokenSignedByAnotherKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair attacker = generator.generateKeyPair();

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER).audience(List.of(AUDIENCE))
                .claim("actor", "jiye").claim("repository", REPOSITORY).claim("sha", "abc1234")
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .build();
        SignedJWT forged = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).build(), claims);
        forged.sign(new RSASSASigner((RSAPrivateKey) attacker.getPrivate()));

        ResponseEntity<String> response = post("/approval/check", forged.serialize(),
                new ApprovalCheckRequest("jiye", "SmallAppStack", "DEPLOY"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void staticTokensKeepWorkingWhileOidcIsEnabled() {
        ResponseEntity<ApprovalCheckResponse> response = post("/approval/check", DemoDataSeeder.JIYE_TOKEN,
                new ApprovalCheckRequest("jiye", "SmallAppStack", "DEPLOY"), ApprovalCheckResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
