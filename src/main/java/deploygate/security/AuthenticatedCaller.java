package deploygate.security;

import java.util.Set;

/**
 * The authenticated principal behind a request. For {@link CallerType#DEPLOYER} and
 * {@link CallerType#CI_OIDC} the name is a proven identity; for
 * {@link CallerType#CI_SERVICE} there is no personal identity at all and {@code name}
 * is just a label.
 *
 * @param attestedCommitSha commit the credential itself vouches for, when it carries one
 */
public record AuthenticatedCaller(CallerType type, String name, Set<String> claims, String attestedCommitSha) {

    public AuthenticatedCaller(CallerType type, String name, Set<String> claims) {
        this(type, name, claims, null);
    }

    public boolean isDeployer() {
        return type == CallerType.DEPLOYER;
    }

    /**
     * Whether this caller may only speak for itself. Only the shared CI token gets to
     * name someone else, because it is the one credential that proves no identity.
     */
    public boolean actsOnlyAsSelf() {
        return type != CallerType.CI_SERVICE;
    }
}
