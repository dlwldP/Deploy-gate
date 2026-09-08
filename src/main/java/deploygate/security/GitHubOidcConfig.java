package deploygate.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Builds the decoder that checks GitHub's signature on a pipeline's OIDC token.
 *
 * <p>The decoder is only created once a JWKS location is configured, which keeps the
 * app bootable offline and lets a test supply its own decoder instead.
 */
@Configuration
public class GitHubOidcConfig {

    @Bean
    @ConditionalOnProperty(prefix = "deploygate.ci.oidc", name = "jwk-set-uri")
    public JwtDecoder githubOidcJwtDecoder(GitHubOidcProperties properties) {
        // Keys are fetched lazily on first use, not at startup.
        return NimbusJwtDecoder.withJwkSetUri(properties.getJwkSetUri()).build();
    }
}
