package deploygate.policy;

import deploygate.entity.ApprovalLevel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ApprovalPolicyResolverTest {

    @Autowired
    private ApprovalPolicyResolver resolver;

    @Autowired
    private List<ApprovalPolicy> policies;

    @Test
    void everyApprovalLevelHasARegisteredPolicy() {
        // Adding a level without its policy would otherwise only surface at runtime,
        // as a request that cannot be evaluated at all.
        for (ApprovalLevel level : ApprovalLevel.values()) {
            assertThat(resolver.resolve(level))
                    .as("policy registered for %s", level)
                    .isPresent();
        }
    }

    @Test
    void eachLevelDeclaresItsOwnApproverCount() {
        assertThat(requiredApprovalsFor(ApprovalLevel.NONE)).isZero();
        assertThat(requiredApprovalsFor(ApprovalLevel.SINGLE_APPROVER)).isEqualTo(1);
        assertThat(requiredApprovalsFor(ApprovalLevel.DUAL_APPROVER)).isEqualTo(2);
    }

    @Test
    void noTwoPoliciesClaimTheSameLevel() {
        assertThat(policies).extracting(ApprovalPolicy::getSupportedLevel).doesNotHaveDuplicates();
    }

    private int requiredApprovalsFor(ApprovalLevel level) {
        return resolver.resolve(level).orElseThrow().requiredApprovals();
    }
}
