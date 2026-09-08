package deploygate.service;

import java.util.Locale;

/**
 * Normalisation for the action a caller asks about.
 *
 * <p>Actions are part of the key an approval is filed under, so {@code DEPLOY} and
 * {@code deploy} arriving as two different actions would quietly split one approval
 * flow into two — a pipeline typo becomes a request that nobody ever approves.
 */
final class DeployAction {

    private DeployAction() {
    }

    static String normalize(String raw) {
        return raw == null ? null : raw.trim().toUpperCase(Locale.ROOT);
    }
}
