package deploygate.dao;

import deploygate.entity.ApprovalRequest;
import deploygate.entity.ApprovalRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApprovalRequestRepository extends JpaRepository<ApprovalRequest, Long> {

    Optional<ApprovalRequest> findByDeployerIdAndStackNameAndActionAndStatus(
            Long deployerId, String stackName, String action, ApprovalRequestStatus status);

    Optional<ApprovalRequest> findByDeployerIdAndStackNameAndActionAndCommitShaAndStatus(
            Long deployerId, String stackName, String action, String commitSha, ApprovalRequestStatus status);

    Optional<ApprovalRequest> findTopByDeployerIdAndStackNameAndActionOrderByCreatedAtDesc(
            Long deployerId, String stackName, String action);

    /**
     * Approvals are commit-scoped, so a check for one commit must not pick up the
     * request that belongs to another.
     */
    Optional<ApprovalRequest> findTopByDeployerIdAndStackNameAndActionAndCommitShaOrderByCreatedAtDesc(
            Long deployerId, String stackName, String action, String commitSha);

    List<ApprovalRequest> findAllByStatusOrderByCreatedAtDesc(ApprovalRequestStatus status);
}
