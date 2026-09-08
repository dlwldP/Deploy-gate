package deploygate.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Settings for authenticating a pipeline by its GitHub Actions OIDC token.
 *
 * <p>Leaving {@code issuer} unset keeps OIDC off, so a deployment that still uses the
 * shared CI token is unaffected.
 */
@Component
@ConfigurationProperties(prefix = "deploygate.ci.oidc")
public class GitHubOidcProperties {

    /** Expected {@code iss}. Enabling flag: blank means OIDC authentication is off. */
    private String issuer = "";

    /** Where the signing keys are published. Set this to build the production decoder. */
    private String jwkSetUri = "";

    /**
     * Expected {@code aud}. GitHub lets the workflow choose this, so pinning it stops a
     * token minted for some other service being replayed here.
     */
    private String audience = "";

    /** Repositories allowed to authenticate, as {@code owner/repo}. Empty means any. */
    private Set<String> allowedRepositories = new LinkedHashSet<>();

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getJwkSetUri() {
        return jwkSetUri;
    }

    public void setJwkSetUri(String jwkSetUri) {
        this.jwkSetUri = jwkSetUri;
    }

    public String getAudience() {
        return audience;
    }

    public void setAudience(String audience) {
        this.audience = audience;
    }

    public Set<String> getAllowedRepositories() {
        return allowedRepositories;
    }

    public void setAllowedRepositories(Set<String> allowedRepositories) {
        this.allowedRepositories = allowedRepositories;
    }

    public boolean isConfigured() {
        return issuer != null && !issuer.isBlank();
    }
}
