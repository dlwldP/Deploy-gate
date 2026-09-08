package deploygate.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

/**
 * Authenticates a pipeline from its GitHub Actions OIDC token.
 *
 * <p>The shared CI token has to be trusted for whatever it claims: it says "I am the
 * pipeline, acting for jiye" and nothing proves either half. An OIDC token is signed by
 * GitHub and carries the repository, the actor who triggered the run and the commit
 * being built, so the caller can no longer name someone else or a different commit.
 */
@Component
public class GitHubOidcAuthenticator {

    private final JwtDecoder decoder;
    private final GitHubOidcProperties properties;

    public GitHubOidcAuthenticator(ObjectProvider<JwtDecoder> decoders, GitHubOidcProperties properties) {
        this.decoder = decoders.getIfAvailable();
        this.properties = properties;
    }

    public boolean isEnabled() {
        return decoder != null && properties.isConfigured();
    }

    public Optional<AuthenticatedCaller> authenticate(String token) {
        if (!isEnabled() || !looksLikeJwt(token)) {
            return Optional.empty();
        }

        Jwt jwt;
        try {
            jwt = decoder.decode(token);
        } catch (JwtException e) {
            // A bad signature is just a failed authentication, not a server error.
            return Optional.empty();
        }

        if (!issuerMatches(jwt) || !audienceMatches(jwt) || !repositoryAllowed(jwt)) {
            return Optional.empty();
        }

        String actor = jwt.getClaimAsString("actor");
        if (actor == null || actor.isBlank()) {
            return Optional.empty();
        }

        return Optional.of(new AuthenticatedCaller(
                CallerType.CI_OIDC, actor, Set.of(), jwt.getClaimAsString("sha")));
    }

    private boolean looksLikeJwt(String token) {
        return token.chars().filter(c -> c == '.').count() == 2;
    }

    private boolean issuerMatches(Jwt jwt) {
        return jwt.getIssuer() != null && properties.getIssuer().equals(jwt.getIssuer().toString());
    }

    private boolean audienceMatches(Jwt jwt) {
        String expected = properties.getAudience();
        if (expected == null || expected.isBlank()) {
            return true;
        }
        return jwt.getAudience() != null && jwt.getAudience().contains(expected);
    }

    private boolean repositoryAllowed(Jwt jwt) {
        Set<String> allowed = properties.getAllowedRepositories();
        if (allowed == null || allowed.isEmpty()) {
            return true;
        }
        return allowed.contains(jwt.getClaimAsString("repository"));
    }
}
