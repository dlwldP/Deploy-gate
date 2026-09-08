package deploygate.service;

import deploygate.dao.ApprovalLogRepository;
import deploygate.dao.ApprovalRequestRepository;
import deploygate.dao.ApprovalVoteRepository;
import deploygate.dao.DeployerRepository;
import deploygate.dao.StackPolicyRepository;
import deploygate.dto.ApprovalActionResponse;
import deploygate.dto.ApprovalRequestResponse;
import deploygate.entity.ApprovalLevel;
import deploygate.entity.ApprovalRequest;
import deploygate.entity.ApprovalRequestStatus;
import deploygate.entity.Deployer;
import deploygate.entity.StackPolicy;
import deploygate.entity.VoteDecision;
import deploygate.policy.ApprovalPolicyResolver;
import deploygate.policy.DualApproverPolicy;
import deploygate.policy.NoApprovalPolicy;
import deploygate.policy.SingleApproverPolicy;
import deploygate.validation.ApprovalRequestNotFoundException;
import deploygate.validation.ApproverNotAuthorizedException;
import deploygate.validation.DeployerNotAuthorizedException;
import deploygate.validation.DuplicateVoteException;
import deploygate.validation.InvalidRequestStateException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApprovalRequestServiceTest {

    private DeployerRepository deployerRepository;
    private StackPolicyRepository stackPolicyRepository;
    private ApprovalRequestRepository approvalRequestRepository;
    private ApprovalVoteRepository approvalVoteRepository;
    private ApprovalLogRepository approvalLogRepository;
    private ApprovalLogService approvalLogService;
    private ApprovalRequestService service;

    private final Deployer jiye = Deployer.builder().id(1L).name("jiye")
            .claims(Set.of("stack:ProdAlbStack:deploy")).build();
    private final Deployer alice = Deployer.builder().id(2L).name("alice")
            .claims(Set.of("stack:ProdAlbStack:approve")).build();
    private final Deployer bob = Deployer.builder().id(3L).name("bob")
            .claims(Set.of("stack:ProdAlbStack:approve")).build();
    private final StackPolicy dualStackPolicy = StackPolicy.builder()
            .stackName("ProdAlbStack").requiredClaim("stack:ProdAlbStack:deploy")
            .approvalLevel(ApprovalLevel.DUAL_APPROVER).build();

    @BeforeEach
    void setUp() {
        deployerRepository = mock(DeployerRepository.class);
        stackPolicyRepository = mock(StackPolicyRepository.class);
        approvalRequestRepository = mock(ApprovalRequestRepository.class);
        approvalVoteRepository = mock(ApprovalVoteRepository.class);
        approvalLogRepository = mock(ApprovalLogRepository.class);
        approvalLogService = mock(ApprovalLogService.class);
        ApprovalPolicyResolver resolver = new ApprovalPolicyResolver(
                List.of(new NoApprovalPolicy(), new SingleApproverPolicy(), new DualApproverPolicy()));
        service = new ApprovalRequestService(deployerRepository, stackPolicyRepository, resolver,
                approvalRequestRepository, approvalVoteRepository, approvalLogRepository, approvalLogService);

        when(deployerRepository.findById(1L)).thenReturn(Optional.of(jiye));
        when(deployerRepository.findByName("alice")).thenReturn(Optional.of(alice));
        when(deployerRepository.findByName("bob")).thenReturn(Optional.of(bob));
    }

    @Test
    void createRequest_noExistingRequest_createsPending() {
        when(deployerRepository.findByName("jiye")).thenReturn(Optional.of(jiye));
        when(stackPolicyRepository.findByStackName("ProdAlbStack")).thenReturn(Optional.of(dualStackPolicy));
        when(approvalRequestRepository.findByDeployerIdAndStackNameAndActionAndStatus(
                1L, "ProdAlbStack", "DEPLOY", ApprovalRequestStatus.PENDING)).thenReturn(Optional.empty());
        when(approvalRequestRepository.save(any(ApprovalRequest.class))).thenAnswer(invocation -> {
            ApprovalRequest saved = invocation.getArgument(0);
            return ApprovalRequest.builder().id(100L).deployerId(saved.getDeployerId())
                    .stackName(saved.getStackName()).action(saved.getAction()).status(saved.getStatus())
                    .requiredApprovals(saved.getRequiredApprovals()).createdAt(saved.getCreatedAt()).build();
        });
        when(approvalVoteRepository.countByRequestIdAndDecision(100L, VoteDecision.APPROVE)).thenReturn(0L);

        ApprovalRequestResponse response = service.createRequest("jiye", "ProdAlbStack", "DEPLOY");

        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(response.requiredApprovals()).isEqualTo(2);
        assertThat(response.currentApprovals()).isEqualTo(0);
    }

    @Test
    void createRequest_pendingAlreadyExists_returnsExistingIdempotently() {
        when(deployerRepository.findByName("jiye")).thenReturn(Optional.of(jiye));
        when(stackPolicyRepository.findByStackName("ProdAlbStack")).thenReturn(Optional.of(dualStackPolicy));
        ApprovalRequest existing = ApprovalRequest.builder().id(55L).deployerId(1L).stackName("ProdAlbStack")
                .action("DEPLOY").status(ApprovalRequestStatus.PENDING).requiredApprovals(2)
                .createdAt(Instant.now()).build();
        when(approvalRequestRepository.findByDeployerIdAndStackNameAndActionAndStatus(
                1L, "ProdAlbStack", "DEPLOY", ApprovalRequestStatus.PENDING)).thenReturn(Optional.of(existing));
        when(approvalVoteRepository.countByRequestIdAndDecision(55L, VoteDecision.APPROVE)).thenReturn(0L);

        ApprovalRequestResponse response = service.createRequest("jiye", "ProdAlbStack", "DEPLOY");

        assertThat(response.id()).isEqualTo(55L);
    }

    @Test
    void createRequest_deployerLacksClaim_throwsDeployerNotAuthorized() {
        Deployer newbie = Deployer.builder().id(9L).name("newbie").claims(Set.of()).build();
        when(deployerRepository.findByName("newbie")).thenReturn(Optional.of(newbie));
        when(stackPolicyRepository.findByStackName("ProdAlbStack")).thenReturn(Optional.of(dualStackPolicy));
        when(approvalRequestRepository.findByDeployerIdAndStackNameAndActionAndStatus(
                9L, "ProdAlbStack", "DEPLOY", ApprovalRequestStatus.PENDING)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createRequest("newbie", "ProdAlbStack", "DEPLOY"))
                .isInstanceOf(DeployerNotAuthorizedException.class);
    }

    @Test
    void createRequest_noneLevelStack_throwsInvalidRequestState() {
        when(deployerRepository.findByName("jiye")).thenReturn(Optional.of(jiye));
        StackPolicy noneStackPolicy = StackPolicy.builder().stackName("SmallAppStack")
                .requiredClaim("stack:SmallAppStack:deploy").approvalLevel(ApprovalLevel.NONE).build();
        when(stackPolicyRepository.findByStackName("SmallAppStack")).thenReturn(Optional.of(noneStackPolicy));

        assertThatThrownBy(() -> service.createRequest("jiye", "SmallAppStack", "DEPLOY"))
                .isInstanceOf(InvalidRequestStateException.class);
    }

    @Test
    void approve_dualApprover_firstVote_staysPending() {
        ApprovalRequest request = pendingDualRequest();
        when(approvalRequestRepository.findById(10L)).thenReturn(Optional.of(request));
        when(approvalVoteRepository.existsByRequestIdAndApproverId(10L, 2L)).thenReturn(false);
        when(approvalVoteRepository.countByRequestIdAndDecision(10L, VoteDecision.APPROVE)).thenReturn(1L);

        ApprovalActionResponse response = service.approve(10L, "alice");

        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(request.getStatus()).isEqualTo(ApprovalRequestStatus.PENDING);
    }

    @Test
    void approve_dualApprover_secondDistinctVote_becomesApproved() {
        ApprovalRequest request = pendingDualRequest();
        when(approvalRequestRepository.findById(10L)).thenReturn(Optional.of(request));
        when(approvalVoteRepository.existsByRequestIdAndApproverId(10L, 3L)).thenReturn(false);
        when(approvalVoteRepository.countByRequestIdAndDecision(10L, VoteDecision.APPROVE)).thenReturn(2L);

        ApprovalActionResponse response = service.approve(10L, "bob");

        assertThat(response.status()).isEqualTo("APPROVED");
        assertThat(request.getStatus()).isEqualTo(ApprovalRequestStatus.APPROVED);
    }

    @Test
    void approve_sameApproverTwice_throwsDuplicateVote() {
        ApprovalRequest request = pendingDualRequest();
        when(approvalRequestRepository.findById(10L)).thenReturn(Optional.of(request));
        when(approvalVoteRepository.existsByRequestIdAndApproverId(10L, 2L)).thenReturn(true);

        assertThatThrownBy(() -> service.approve(10L, "alice"))
                .isInstanceOf(DuplicateVoteException.class);
    }

    @Test
    void approve_nonPendingRequest_throwsInvalidRequestState() {
        ApprovalRequest request = pendingDualRequest();
        request.approve(Instant.now(), Instant.now().plusSeconds(3600));
        when(approvalRequestRepository.findById(10L)).thenReturn(Optional.of(request));

        assertThatThrownBy(() -> service.approve(10L, "alice"))
                .isInstanceOf(InvalidRequestStateException.class);
    }

    @Test
    void approve_unauthorizedApprover_throwsApproverNotAuthorized() {
        ApprovalRequest request = pendingDualRequest();
        when(approvalRequestRepository.findById(10L)).thenReturn(Optional.of(request));
        when(deployerRepository.findByName("jiye")).thenReturn(Optional.of(jiye));

        assertThatThrownBy(() -> service.approve(10L, "jiye"))
                .isInstanceOf(ApproverNotAuthorizedException.class);
    }

    @Test
    void approve_unknownRequestId_throwsApprovalRequestNotFound() {
        when(approvalRequestRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve(404L, "alice"))
                .isInstanceOf(ApprovalRequestNotFoundException.class);
    }

    @Test
    void reject_pendingRequest_immediatelyRejects() {
        ApprovalRequest request = pendingDualRequest();
        when(approvalRequestRepository.findById(10L)).thenReturn(Optional.of(request));
        when(approvalVoteRepository.existsByRequestIdAndApproverId(10L, 2L)).thenReturn(false);

        ApprovalActionResponse response = service.reject(10L, "alice");

        assertThat(response.status()).isEqualTo("REJECTED");
        assertThat(request.getStatus()).isEqualTo(ApprovalRequestStatus.REJECTED);
    }

    @Test
    void reject_nonPendingRequest_throwsInvalidRequestState() {
        ApprovalRequest request = pendingDualRequest();
        request.reject(Instant.now());
        when(approvalRequestRepository.findById(10L)).thenReturn(Optional.of(request));

        assertThatThrownBy(() -> service.reject(10L, "alice"))
                .isInstanceOf(InvalidRequestStateException.class);
    }

    @Test
    void history_filtersByStackAndUser() {
        service.history(Optional.of("ProdAlbStack"), Optional.of("jiye"), 20);

        org.mockito.Mockito.verify(approvalLogRepository)
                .findAllByStackNameAndDeployerNameOrderByDecidedAtDesc(
                        org.mockito.ArgumentMatchers.eq("ProdAlbStack"),
                        org.mockito.ArgumentMatchers.eq("jiye"),
                        any());
    }

    private ApprovalRequest pendingDualRequest() {
        return ApprovalRequest.builder().id(10L).deployerId(1L).stackName("ProdAlbStack").action("DEPLOY")
                .status(ApprovalRequestStatus.PENDING).requiredApprovals(2).createdAt(Instant.now()).build();
    }
}
