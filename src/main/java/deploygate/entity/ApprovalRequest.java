package deploygate.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "approval_request")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApprovalRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long deployerId;

    @Column(nullable = false)
    private String stackName;

    @Column(nullable = false)
    private String action;

    /**
     * The commit the approvers were looking at, when the caller supplied one. An
     * approval is only honoured for this exact commit — otherwise a single approval
     * would keep authorising every later push to the same stack.
     */
    private String commitSha;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ApprovalRequestStatus status;

    @Column(nullable = false)
    private int requiredApprovals;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant decidedAt;

    /** When the granted approval stops being honoured. Set at approval time. */
    private Instant expiresAt;

    private Instant consumedAt;

    public void approve(Instant now, Instant expiresAt) {
        this.status = ApprovalRequestStatus.APPROVED;
        this.decidedAt = now;
        this.expiresAt = expiresAt;
    }

    public void reject(Instant now) {
        this.status = ApprovalRequestStatus.REJECTED;
        this.decidedAt = now;
    }

    /** Spends a granted approval so it cannot authorise a second deploy. */
    public void consume(Instant now) {
        this.status = ApprovalRequestStatus.CONSUMED;
        this.consumedAt = now;
    }

    public boolean isExpiredAt(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    /**
     * An approval bound to a commit only covers that commit; an unbound approval
     * covers any commit, which is why {@code requireCommitSha} exists on the policy.
     */
    public boolean coversCommit(String candidateSha) {
        if (commitSha == null || commitSha.isBlank()) {
            return true;
        }
        return commitSha.equalsIgnoreCase(candidateSha == null ? null : candidateSha.trim());
    }
}
