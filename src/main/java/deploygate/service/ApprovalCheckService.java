package deploygate.service;

import deploygate.dao.ApprovalRequestRepository;
import deploygate.dao.ApprovalVoteRepository;
import deploygate.dao.DeployerRepository;
import deploygate.dao.StackPolicyRepository;
import deploygate.entity.ApprovalLevel;
import deploygate.entity.ApprovalRequest;
import deploygate.entity.Deployer;
import deploygate.entity.StackPolicy;
import deploygate.entity.VoteDecision;
import deploygate.policy.ApprovalPolicy;
import deploygate.policy.ApprovalPolicyResolver;
import deploygate.policy.ApprovalResult;
import deploygate.policy.Decision;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@Transactional
public class ApprovalCheckService {

    private final DeployerRepository deployerRepository;
    private final StackPolicyRepository stackPolicyRepository;
    private final ApprovalPolicyResolver approvalPolicyResolver;
    private final ApprovalRequestRepository approvalRequestRepository;
    private final ApprovalVoteRepository approvalVoteRepository;
    private final ApprovalLogService approvalLogService;

    public ApprovalCheckService(DeployerRepository deployerRepository,
                                StackPolicyRepository stackPolicyRepository,
                                ApprovalPolicyResolver approvalPolicyResolver,
                                ApprovalRequestRepository approvalRequestRepository,
                                ApprovalVoteRepository approvalVoteRepository,
                                ApprovalLogService approvalLogService) {
        this.deployerRepository = deployerRepository;
        this.stackPolicyRepository = stackPolicyRepository;
        this.approvalPolicyResolver = approvalPolicyResolver;
        this.approvalRequestRepository = approvalRequestRepository;
        this.approvalVoteRepository = approvalVoteRepository;
        this.approvalLogService = approvalLogService;
    }

    public ApprovalResult check(String user, String stack, String action) {
        return check(user, stack, action, null);
    }

    public ApprovalResult check(String user, String stack, String action, String commitSha) {
        Deployer deployer = deployerRepository.findByName(user).orElse(null);
        if (deployer == null) {
            return ApprovalResult.denied("unknown deployer: " + user);
        }

        StackPolicy stackPolicy = stackPolicyRepository.findByStackName(stack).orElse(null);
        if (stackPolicy == null) {
            return ApprovalResult.denied("no policy configured for stack: " + stack);
        }

        String sha = CommitSha.normalize(commitSha);
        if (stackPolicy.isRequireCommitSha() && sha == null) {
            return ApprovalResult.denied("stack " + stack + " requires the deployed commitSha to be supplied");
        }

        if (stackPolicy.getApprovalLevel() == ApprovalLevel.NONE) {
            ApprovalResult result = evaluate(deployer, stackPolicy);
            approvalLogService.record(user, stack, action, result.decision(), "SYSTEM", null);
            return result;
        }

        return checkWithApprovalFlow(deployer, stackPolicy, action, sha);
    }

    private ApprovalResult checkWithApprovalFlow(Deployer deployer, StackPolicy stackPolicy, String action, String sha) {
        ApprovalRequest request = findApplicableRequest(deployer, stackPolicy.getStackName(), action, sha);
        if (request != null) {
            ApprovalResult decided = decideFromRequest(deployer, request, stackPolicy, action, sha);
            if (decided != null) {
                return decided;
            }
        }

        ApprovalResult result = evaluate(deployer, stackPolicy);
        if (result.decision() == Decision.DENIED) {
            approvalLogService.record(deployer.getName(), stackPolicy.getStackName(), action,
                    result.decision(), "SYSTEM", null);
            return result;
        }
        return ApprovalResult.pending("approval required" + forCommit(sha)
                + "; call POST /approval/request to start the approval flow");
    }

    /**
     * An approval only speaks for the commit it was granted for, so a commit-bound
     * request is looked up first and a request bound to some other commit is ignored
     * rather than reused.
     */
    private ApprovalRequest findApplicableRequest(Deployer deployer, String stack, String action, String sha) {
        if (sha != null) {
            ApprovalRequest bound = approvalRequestRepository
                    .findTopByDeployerIdAndStackNameAndActionAndCommitShaOrderByCreatedAtDesc(
                            deployer.getId(), stack, action, sha)
                    .orElse(null);
            if (bound != null) {
                return bound;
            }
        }
        ApprovalRequest latest = approvalRequestRepository
                .findTopByDeployerIdAndStackNameAndActionOrderByCreatedAtDesc(deployer.getId(), stack, action)
                .orElse(null);
        return latest != null && latest.coversCommit(sha) ? latest : null;
    }

    /** Returns null when the request says nothing about this deploy and the policy should be re-evaluated. */
    private ApprovalResult decideFromRequest(Deployer deployer, ApprovalRequest request, StackPolicy stackPolicy,
                                             String action, String sha) {
        Long id = request.getId();
        return switch (request.getStatus()) {
            case APPROVED -> honourApproval(deployer, request, stackPolicy, action, sha);
            case CONSUMED -> ApprovalResult.denied("approval for request #" + id
                    + " was already spent on a deploy; submit a new /approval/request");
            case REJECTED -> ApprovalResult.denied(
                    "request #" + id + " was rejected; submit a new /approval/request to retry");
            case PENDING -> {
                long approved = approvalVoteRepository.countByRequestIdAndDecision(id, VoteDecision.APPROVE);
                long remaining = request.getRequiredApprovals() - approved;
                yield ApprovalResult.pending("awaiting " + remaining + " more approver(s) (request #" + id + ")");
            }
        };
    }

    private ApprovalResult honourApproval(Deployer deployer, ApprovalRequest request, StackPolicy stackPolicy,
                                          String action, String sha) {
        Instant now = Instant.now();
        Long id = request.getId();

        if (request.isExpiredAt(now)) {
            return ApprovalResult.denied("approval for request #" + id + " expired at "
                    + request.getExpiresAt() + "; submit a new /approval/request");
        }

        if (!stackPolicy.isSingleUse()) {
            return ApprovalResult.allowed("approved (request #" + id + ")" + forCommit(request.getCommitSha()));
        }

        // Single-use stacks spend the approval here: this call is the deploy going ahead,
        // so letting it authorise a second deploy would defeat the point of the flag.
        request.consume(now);
        approvalRequestRepository.save(request);
        approvalLogService.record(deployer.getName(), stackPolicy.getStackName(), action,
                Decision.ALLOWED, "SYSTEM", id);
        return ApprovalResult.allowed("approved (request #" + id + ")" + forCommit(request.getCommitSha())
                + "; single-use approval consumed");
    }

    private String forCommit(String sha) {
        return sha == null || sha.isBlank() ? "" : " for commit " + sha;
    }

    private ApprovalResult evaluate(Deployer deployer, StackPolicy stackPolicy) {
        return approvalPolicyResolver.resolve(stackPolicy.getApprovalLevel())
                .map((ApprovalPolicy policy) -> policy.evaluate(deployer, stackPolicy))
                .orElseGet(() -> ApprovalResult.denied(
                        "no approval policy registered for level: " + stackPolicy.getApprovalLevel()));
    }
}
