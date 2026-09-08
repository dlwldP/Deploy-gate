package deploygate.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Entity
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StackPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String stackName;

    @Column(nullable = false)
    private String requiredClaim;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ApprovalLevel approvalLevel;

    /** How long a granted approval stays valid. Null falls back to {@link #DEFAULT_APPROVAL_TTL_MINUTES}. */
    private Integer approvalTtlMinutes;

    /** When true, an approval authorises exactly one deploy and is spent on use. */
    private Boolean singleUse;

    /** When true, check/request must name the commit being deployed. */
    private Boolean requireCommitSha;

    /**
     * Claim an approver must hold to sign off on this stack. Null keeps the
     * {@code stack:{name}:approve} convention; setting it lets a stack be signed off by
     * a group that owns it rather than by whoever happens to follow the naming rule.
     */
    private String approverClaim;

    /**
     * Actions this stack accepts, comma separated. Null means {@code DEPLOY} only, so a
     * stack never answers for an operation nobody configured it for.
     */
    private String allowedActions;

    public static final int DEFAULT_APPROVAL_TTL_MINUTES = 60;

    public int approvalTtlMinutesOrDefault() {
        return approvalTtlMinutes != null && approvalTtlMinutes > 0
                ? approvalTtlMinutes
                : DEFAULT_APPROVAL_TTL_MINUTES;
    }

    public boolean isSingleUse() {
        return Boolean.TRUE.equals(singleUse);
    }

    public boolean isRequireCommitSha() {
        return Boolean.TRUE.equals(requireCommitSha);
    }

    public static final String DEFAULT_ACTION = "DEPLOY";

    /** Placeholder in {@code requiredClaim} that expands to the requested action. */
    private static final String ACTION_PLACEHOLDER = "{action}";

    public Set<String> allowedActionsOrDefault() {
        if (allowedActions == null || allowedActions.isBlank()) {
            return Set.of(DEFAULT_ACTION);
        }
        return Arrays.stream(allowedActions.split(","))
                .map(String::trim)
                .filter(action -> !action.isEmpty())
                .map(action -> action.toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean allowsAction(String action) {
        return action != null && allowedActionsOrDefault().contains(action);
    }

    /**
     * The claim a deployer needs for this action. A policy that writes
     * {@code stack:Foo:{action}} gates each operation on its own claim, so being allowed
     * to deploy does not also mean being allowed to destroy.
     */
    public String requiredClaimFor(String action) {
        if (requiredClaim == null || !requiredClaim.contains(ACTION_PLACEHOLDER)) {
            return requiredClaim;
        }
        return requiredClaim.replace(ACTION_PLACEHOLDER,
                action == null ? "" : action.toLowerCase(Locale.ROOT));
    }

    public String approverClaimOrDefault() {
        return approverClaim != null && !approverClaim.isBlank()
                ? approverClaim
                : "stack:" + stackName + ":approve";
    }
}
