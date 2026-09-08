package deploygate.policy;

import deploygate.entity.ApprovalLevel;
import deploygate.entity.Deployer;
import deploygate.entity.StackPolicy;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class NoApprovalPolicyTest {

    private final NoApprovalPolicy policy = new NoApprovalPolicy();

    @Test
    void allowsWhenDeployerHasRequiredClaim() {
        Deployer deployer = Deployer.builder()
                .name("jiye")
                .claims(Set.of("stack:SmallAppStack:deploy"))
                .build();
        StackPolicy stackPolicy = StackPolicy.builder()
                .stackName("SmallAppStack")
                .requiredClaim("stack:SmallAppStack:deploy")
                .approvalLevel(ApprovalLevel.NONE)
                .build();

        ApprovalResult result = policy.evaluate(deployer, stackPolicy, "DEPLOY");

        assertThat(result.decision()).isEqualTo(Decision.ALLOWED);
    }

    @Test
    void deniesWhenDeployerLacksRequiredClaim() {
        Deployer deployer = Deployer.builder()
                .name("newbie")
                .claims(Set.of("stack:SmallAppStack:deploy"))
                .build();
        StackPolicy stackPolicy = StackPolicy.builder()
                .stackName("ProdAlbStack")
                .requiredClaim("stack:ProdAlbStack:deploy")
                .approvalLevel(ApprovalLevel.NONE)
                .build();

        ApprovalResult result = policy.evaluate(deployer, stackPolicy, "DEPLOY");

        assertThat(result.decision()).isEqualTo(Decision.DENIED);
    }
}
