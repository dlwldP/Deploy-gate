package deploygate.service;

/**
 * Normalisation for the commit identifiers callers send. Kept in one place so a
 * request and the later check agree on what "the same commit" means.
 */
final class CommitSha {

    private CommitSha() {
    }

    /** Returns a trimmed, lower-cased sha, or null when nothing usable was supplied. */
    static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim().toLowerCase();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
