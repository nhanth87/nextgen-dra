package et.elisa.dra.core.th;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import java.util.regex.Pattern;

public final class PseudoHostMapper {

    private static final Pattern PSEUDO_PATTERN = Pattern.compile("^(.+-)(\\d+)(\\..+)$");

    private final ThConfig config;
    private final MessageDigest sha256;

    public PseudoHostMapper(ThConfig config) {
        this.config = config;
        try {
            this.sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public String pseudoFor(String deterministicKey) {
        int index = Math.floorMod(stableHash(deterministicKey), config.pseudoCount()) + 1;
        return config.pseudoPrefix() + "-" + index + "." + config.internalDomainSuffix();
    }

    public Optional<String> realFor(String host) {
        var matcher = PSEUDO_PATTERN.matcher(host == null ? "" : host);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String prefix = matcher.group(1);
        int index;
        try {
            index = Integer.parseInt(matcher.group(2));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        if (!prefix.equals(config.pseudoPrefix() + "-")
                || index < 1 || index > config.pseudoCount()
                || !matcher.group(3).equals("." + config.internalDomainSuffix())) {
            return Optional.empty();
        }
        return Optional.of(host);
    }

    public boolean isInternalHost(String host) {
        return host != null && host.endsWith("." + config.internalDomainSuffix());
    }

    private long stableHash(String key) {
        byte[] digest = sha256.digest(key.getBytes(StandardCharsets.UTF_8));
        return ((long) (digest[0] & 0xFF) << 56)
                | ((long) (digest[1] & 0xFF) << 48)
                | ((long) (digest[2] & 0xFF) << 40)
                | ((long) (digest[3] & 0xFF) << 32)
                | ((long) (digest[4] & 0xFF) << 24)
                | ((long) (digest[5] & 0xFF) << 16)
                | ((long) (digest[6] & 0xFF) << 8)
                | (digest[7] & 0xFFL);
    }

    public ThConfig config() {
        return config;
    }
}
