package deploygate.service;

import deploygate.dao.ApprovalLogRepository;
import deploygate.dao.ApprovalRequestRepository;
import deploygate.dao.ApprovalVoteRepository;
import deploygate.dao.DeployerRepository;
import deploygate.dao.StackPolicyRepository;
import deploygate.dto.ApprovalActionResponse;
import deploygate.dto.ApprovalHistoryEntry;
import deploygate.dto.ApprovalRequestResponse;
import deploygate.entity.ApprovalLevel;
import deploygate.entity.ApprovalLog;
import deploygate.entity.ApprovalRequest;
import deploygate.entity.ApprovalRequestStatus;
import deploygate.entity.ApprovalVote;
import deploygate.entity.Deployer;
import deploygate.entity.StackPolicy;
import deploygate.entity.VoteDecision;
import deploygate.policy.ApprovalPolicy;
import deploygate.policy.ApprovalPolicyResolver;
import deploygate.policy.ApprovalResult;
import deploygate.policy.Decision;
import deploygate.validation.ApprovalRequestNotFoundException;
import deploygate.validation.ApproverNotAuthorizedException;
import deploygate.validation.DeployerNotAuthorizedException;
import deploygate.validation.DeployerNotFoundException;
import deploygate.validation.DuplicateVoteException;
import deploygate.validation.InvalidRequestStateException;
import deploygate.validation.StackNotFoundException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class ApprovalRequestService {

    private final DeployerRepository deployerRepository;
    private final StackPolicyRepository stackPolicyRepository;
    private final ApprovalPolicyResolver approvalPolicyResolver;
    private final ApprovalRequestRepository approvalRequestRepository;
    private final ApprovalVoteRepository approvalVoteRepository;
    private final ApprovalLogRepository approvalLogRepository;
    private final ApprovalLogService approvalLogService;

    public ApprovalRequestService(DeployerRepository deployerRepository,
                                   StackPolicyRepository stackPolicyRepository,
                                   ApprovalPolicyResolver approvalPolicyResolver,
                                   ApprovalRequestRepository approvalRequestRepository,
                                   ApprovalVoteRepository approvalVoteRepository,
                                   ApprovalLogRepository approvalLogRepository,
                                   ApprovalLogService approvalLogService) {
        this.deployerRepository = deployerRepository;
        this.stackPolicyRepository = stackPolicyRepository;
        this.approvalPolicyResolver = approvalPolicyResolver;
        this.approvalRequestRepository = approvalRequestRepository;
        this.approvalVoteRepository = approvalVoteRepository;
        this.approvalLogRepository = approvalLogRepository;
        this.approvalLogService = approvalLogService;
    }

    public ApprovalRequestResponse createRequest(String user, String stack, String action) {
        return createRequest(user, stack, action, null);
    }

    public ApprovalRequestResponse createRequest(String user, String stack, String action, String commitSha) {
        Deployer deployer = deployerRepository.findByName(user)
                .orElseThrow(() -> new DeployerNotFoundException("unknown deployer: " + user));
        StackPolicy stackPolicy = stackPolicyRepository.findByStackName(stack)
                .orElseThrow(() -> new StackNotFoundException("no policy configured for stack: " + stack));

        if (stackPolicy.getApprovalLevel() == ApprovalLevel.NONE) {
            throw new InvalidRequestStateException("stack does not require approval: " + stack);
        }

        String sha = CommitSha.normalize(commitSha);
        if (stackPolicy.isRequireCommitSha() && sha == null) {
            throw new InvalidRequestStateException(
                    "stack " + stack + " requires the deployed commitSha to be supplied");
        }

        // Idempotent per commit: a retried pipeline step must not open a second request,
        // but a genuinely different commit needs its own approval.
        ApprovalRequest existing = (sha == null
                ? approvalRequestRepository.findByDeployerIdAndStackNameAndActionAndStatus(
                        deployer.getId(), stack, action, ApprovalRequestStatus.PENDING)
                : approvalRequestRepository.findByDeployerIdAndStackNameAndActionAndCommitShaAndStatus(
                        deployer.getId(), stack, action, sha, ApprovalRequestStatus.PENDING))
                .orElse(null);
        if (existing != null) {
            return toResponse(existing, "existing pending request");
        }

        ApprovalPolicy policy = approvalPolicyResolver.resolve(stackPolicy.getApprovalLevel())
                .orElseThrow(() -> new InvalidRequestStateException(
                        "no approval policy registered for level: " + stackPolicy.getApprovalLevel()));
        ApprovalResult result = policy.evaluate(deployer, stackPolicy);

        if (result.decision() == Decision.DENIED) {
            approvalLogService.record(user, stack, action, Decision.DENIED, "SYSTEM", null);
            throw new DeployerNotAuthorizedException(result.reason());
        }

        // The count belongs to the policy, not to a branch here: a new approval level
        // must not be able to silently inherit someone else's threshold.
        int requiredApprovals = policy.requiredApprovals();
        ApprovalRequest request = approvalRequestRepository.save(ApprovalRequest.builder()
                .deployerId(deployer.getId())
                .stackName(stack)
                .action(action)
                .commitSha(sha)
                .status(ApprovalRequestStatus.PENDING)
                .requiredApprovals(requiredApprovals)
                .createdAt(Instant.now())
                .build());

        approvalLogService.record(user, stack, action, Decision.PENDING, null, request.getId());
        return toResponse(request, result.reason());
    }

    public ApprovalActionResponse approve(Long requestId, String approverName) {
        ApprovalRequest request = requireVotableRequest(requestId);
        Deployer approver = requireApprover(request, approverName);

        if (approvalVoteRepository.existsByRequestIdAndApproverId(requestId, approver.getId())) {
            throw new DuplicateVoteException("approver has already voted on request #" + requestId);
        }

        Instant votedAt = Instant.now();
        approvalVoteRepository.save(ApprovalVote.builder()
                .requestId(requestId)
                .approverId(approver.getId())
                .decision(VoteDecision.APPROVE)
                .decidedAt(votedAt)
                .build());
        // Touch the request so a simultaneous second vote loses the version race and
        // retries, instead of both voters counting a tally that misses the other.
        request.recordVote(votedAt);
        approvalRequestRepository.save(request);

        long approvals = approvalVoteRepository.countByRequestIdAndDecision(requestId, VoteDecision.APPROVE);
        Deployer requester = deployerRepository.findById(request.getDeployerId()).orElseThrow();

        if (approvals >= request.getRequiredApprovals()) {
            Instant now = Instant.now();
            Instant expiresAt = now.plus(approvalTtl(request.getStackName()));
            request.approve(now, expiresAt);
            approvalRequestRepository.save(request);
            approvalLogService.record(requester.getName(), request.getStackName(), request.getAction(),
                    Decision.ALLOWED, approverName, requestId);
            return new ApprovalActionResponse(requestId, request.getStatus().name(),
                    "fully approved; valid until " + expiresAt);
        }

        approvalLogService.record(requester.getName(), request.getStackName(), request.getAction(),
                Decision.PENDING, approverName, requestId);
        long remaining = request.getRequiredApprovals() - approvals;
        return new ApprovalActionResponse(requestId, request.getStatus().name(), "awaiting " + remaining + " more approver(s)");
    }

    public ApprovalActionResponse reject(Long requestId, String approverName) {
        ApprovalRequest request = requireVotableRequest(requestId);
        Deployer approver = requireApprover(request, approverName);

        if (approvalVoteRepository.existsByRequestIdAndApproverId(requestId, approver.getId())) {
            throw new DuplicateVoteException("approver has already voted on request #" + requestId);
        }

        Instant votedAt = Instant.now();
        approvalVoteRepository.save(ApprovalVote.builder()
                .requestId(requestId)
                .approverId(approver.getId())
                .decision(VoteDecision.REJECT)
                .decidedAt(votedAt)
                .build());

        request.recordVote(votedAt);
        request.reject(votedAt);
        approvalRequestRepository.save(request);

        Deployer requester = deployerRepository.findById(request.getDeployerId()).orElseThrow();
        approvalLogService.record(requester.getName(), request.getStackName(), request.getAction(),
                Decision.DENIED, approverName, requestId);

        return new ApprovalActionResponse(requestId, request.getStatus().name(), "rejected by " + approverName);
    }

    @Transactional(readOnly = true)
    public List<ApprovalHistoryEntry> history(Optional<String> stack, Optional<String> user, int limit) {
        Pageable pageable = PageRequest.of(0, Math.min(Math.max(limit, 1), 200));
        List<ApprovalLog> logs;
        if (stack.isPresent() && user.isPresent()) {
            logs = approvalLogRepository.findAllByStackNameAndDeployerNameOrderByDecidedAtDesc(stack.get(), user.get(), pageable);
        } else if (stack.isPresent()) {
            logs = approvalLogRepository.findAllByStackNameOrderByDecidedAtDesc(stack.get(), pageable);
        } else if (user.isPresent()) {
            logs = approvalLogRepository.findAllByDeployerNameOrderByDecidedAtDesc(user.get(), pageable);
        } else {
            logs = approvalLogRepository.findAllByOrderByDecidedAtDesc(pageable);
        }

        return logs.stream()
                .map(log -> new ApprovalHistoryEntry(log.getId(), log.getDeployerName(), log.getStackName(),
                        log.getAction(), log.getResult().name(), log.getDecidedBy(), log.getDecidedAt()))
                .toList();
    }

    /** Falls back to the default when the stack policy is gone, so an approval is never open-ended. */
    private Duration approvalTtl(String stackName) {
        int minutes = stackPolicyRepository.findByStackName(stackName)
                .map(StackPolicy::approvalTtlMinutesOrDefault)
                .orElse(StackPolicy.DEFAULT_APPROVAL_TTL_MINUTES);
        return Duration.ofMinutes(minutes);
    }

    private ApprovalRequest requireVotableRequest(Long requestId) {
        ApprovalRequest request = approvalRequestRepository.findById(requestId)
                .orElseThrow(() -> new ApprovalRequestNotFoundException("no approval request with id: " + requestId));
        if (request.getStatus() != ApprovalRequestStatus.PENDING) {
            throw new InvalidRequestStateException(
                    "request #" + requestId + " is not pending (status: " + request.getStatus() + ")");
        }
        return request;
    }

    private Deployer requireApprover(ApprovalRequest request, String approverName) {
        Deployer approver = deployerRepository.findByName(approverName)
                .orElseThrow(() -> new DeployerNotFoundException("unknown deployer: " + approverName));
        // Which claim signs off on a stack is the policy's call, not a naming rule baked
        // into this method: a stack owned by one team should be approvable by that team.
        String requiredClaim = stackPolicyRepository.findByStackName(request.getStackName())
                .map(StackPolicy::approverClaimOrDefault)
                .orElseGet(() -> "stack:" + request.getStackName() + ":approve");
        if (!approver.getClaims().contains(requiredClaim)) {
            throw new ApproverNotAuthorizedException(approverName + " is missing required claim: " + requiredClaim);
        }
        // Four-eyes principle: the deploy would otherwise be self-authorized, which
        // defeats the point of requiring an approver at all.
        if (approver.getId().equals(request.getDeployerId())) {
            throw new ApproverNotAuthorizedException("a deployer cannot approve or reject their own request");
        }
        return approver;
    }

    private ApprovalRequestResponse toResponse(ApprovalRequest request, String reason) {
        long currentApprovals = approvalVoteRepository.countByRequestIdAndDecision(request.getId(), VoteDecision.APPROVE);
        return new ApprovalRequestResponse(request.getId(), request.getStatus().name(),
                request.getRequiredApprovals(), currentApprovals, reason);
    }
}
