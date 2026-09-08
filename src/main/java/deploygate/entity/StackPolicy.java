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

    public String approverClaimOrDefault() {
        return approverClaim != null && !approverClaim.isBlank()
                ? approverClaim
                : "stack:" + stackName + ":approve";
    }
}
