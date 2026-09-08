package deploygate.policy;

import deploygate.entity.ApprovalLevel;
import deploygate.entity.Deployer;
import deploygate.entity.StackPolicy;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DualApproverPolicyTest {

    private final DualApproverPolicy policy = new DualApproverPolicy();

    @Test
    void pendingWhenDeployerHasRequiredClaim() {
        Deployer deployer = Deployer.builder()
                .name("jiye")
                .claims(Set.of("stack:ProdAlbStack:deploy"))
                .build();
        StackPolicy stackPolicy = StackPolicy.builder()
                .stackName("ProdAlbStack")
                .requiredClaim("stack:ProdAlbStack:deploy")
                .approvalLevel(ApprovalLevel.DUAL_APPROVER)
                .build();

        ApprovalResult result = policy.evaluate(deployer, stackPolicy, "DEPLOY");

        assertThat(result.decision()).isEqualTo(Decision.PENDING);
    }

    @Test
    void deniesWhenDeployerLacksRequiredClaim() {
        Deployer deployer = Deployer.builder()
                .name("newbie")
                .claims(Set.of())
                .build();
        StackPolicy stackPolicy = StackPolicy.builder()
                .stackName("ProdAlbStack")
                .requiredClaim("stack:ProdAlbStack:deploy")
                .approvalLevel(ApprovalLevel.DUAL_APPROVER)
                .build();

        ApprovalResult result = policy.evaluate(deployer, stackPolicy, "DEPLOY");

        assertThat(result.decision()).isEqualTo(Decision.DENIED);
    }
}
