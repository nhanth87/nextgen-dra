package et.elisa.dra.core.engine;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public sealed interface Matcher {

    boolean evaluate(RoutingContext ctx);

    record And(List<Matcher> parts) implements Matcher {

        public And {
            parts = List.copyOf(parts);
        }

        @Override
        public boolean evaluate(RoutingContext ctx) {
            for (Matcher m : parts) {
                if (!m.evaluate(ctx)) {
                    return false;
                }
            }
            return true;
        }
    }

    record Or(List<Matcher> parts) implements Matcher {

        public Or {
            parts = List.copyOf(parts);
        }

        @Override
        public boolean evaluate(RoutingContext ctx) {
            for (Matcher m : parts) {
                if (m.evaluate(ctx)) {
                    return true;
                }
            }
            return false;
        }
    }

    record Not(Matcher inner) implements Matcher {

        @Override
        public boolean evaluate(RoutingContext ctx) {
            return !inner.evaluate(ctx);
        }
    }

    record HasApp(int appId) implements Matcher {

        @Override
        public boolean evaluate(RoutingContext ctx) {
            return ctx.applicationId() == appId;
        }
    }

    record HasCmd(Set<Integer> codes) implements Matcher {

        public HasCmd {
            codes = Set.copyOf(codes);
        }

        public HasCmd(int... codeArray) {
            this(java.util.Arrays.stream(codeArray).boxed()
                    .collect(java.util.stream.Collectors.toUnmodifiableSet()));
        }

        public HasCmd(List<Integer> codeList) {
            this(Set.copyOf(codeList));
        }

        @Override
        public boolean evaluate(RoutingContext ctx) {
            return codes.contains(ctx.commandCode());
        }
    }

    record RealmMatch(Field field, Op op, String value, Pattern pattern) implements Matcher {

        public enum Field { DEST, ORIG }

        public enum Op { EQ, SUFFIX, REGEX }

        public static RealmMatch of(Field field, Op op, String value) {
            Pattern p = op == Op.REGEX ? Pattern.compile(value, Pattern.CASE_INSENSITIVE) : null;
            return new RealmMatch(field, op, value, p);
        }

        @Override
        public boolean evaluate(RoutingContext ctx) {
            String target = field == Field.DEST ? ctx.destRealm() : ctx.origRealm();
            return matchString(target, op, value, pattern);
        }
    }

    record HostMatch(Field field, Op op, String value, Pattern pattern) implements Matcher {

        public enum Field { DEST, ORIG }

        public enum Op { EQ, SUFFIX, REGEX }

        public static HostMatch of(Field field, Op op, String value) {
            Pattern p = op == Op.REGEX ? Pattern.compile(value, Pattern.CASE_INSENSITIVE) : null;
            return new HostMatch(field, op, value, p);
        }

        @Override
        public boolean evaluate(RoutingContext ctx) {
            String target = field == Field.DEST ? ctx.destHost() : ctx.origHost();
            return matchString(target, op, value, pattern);
        }
    }

    record AvpMatch(String path, Op op, String value, Set<String> values,
                    boolean ipv4, byte[] network, int prefixLen) implements Matcher {

        public enum Op { EQ, PREFIX, CONTAINS, IN_LIST, IP_IN_CIDR }

        public static AvpMatch of(String rawPath, Op op, String value) {
            String path = PathNames.canonical(rawPath);
            Set<String> vals = null;
            boolean v4 = true;
            byte[] net = null;
            int prefix = 0;
            if (op == Op.IN_LIST) {
                vals = Set.of(value.split(","));
            } else if (op == Op.IP_IN_CIDR) {
                String[] parts = value.split("/", 2);
                try {
                    InetAddress addr = InetAddress.getByName(parts[0].trim());
                    net = addr.getAddress();
                    v4 = net.length == 4;
                    prefix = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : net.length * 8;
                } catch (UnknownHostException e) {
                    net = new byte[0];
                }
            }
            return new AvpMatch(path, op, value, vals, v4, net, prefix);
        }

        @Override
        public boolean evaluate(RoutingContext ctx) {
            String v = ctx.key(path);
            if (v == null || v.isBlank()) {
                return false;
            }
            return switch (op) {
                case EQ -> v.equals(value);
                case PREFIX -> v.startsWith(value);
                case CONTAINS -> v.contains(value);
                case IN_LIST -> values.contains(v);
                case IP_IN_CIDR -> cidrContains(v);
            };
        }

        private boolean cidrContains(String ip) {
            byte[] addr;
            try {
                InetAddress a = InetAddress.getByName(ip.trim());
                addr = a.getAddress();
            } catch (UnknownHostException e) {
                return false;
            }
            if (addr.length != network.length) {
                return false;
            }
            int fullBytes = prefixLen / 8;
            int remBits = prefixLen % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (addr[i] != network[i]) {
                    return false;
                }
            }
            if (remBits > 0 && fullBytes < network.length) {
                int mask = 0xFF << (8 - remBits);
                return (addr[fullBytes] & mask) == (network[fullBytes] & mask);
            }
            return true;
        }
    }

    record PlmnMatch(String fromKey, Set<String> in, Set<String> notIn) implements Matcher {

        public PlmnMatch {
            in = in == null ? Set.of() : Set.copyOf(in);
            notIn = notIn == null ? Set.of() : Set.copyOf(notIn);
        }

        public static PlmnMatch of(String rawKey, List<String> inList, List<String> notInList) {
            return new PlmnMatch(PathNames.plmnKey(rawKey),
                    inList == null ? Set.of() : Set.copyOf(inList),
                    notInList == null ? Set.of() : Set.copyOf(notInList));
        }

        @Override
        public boolean evaluate(RoutingContext ctx) {
            String v = ctx.key(fromKey);
            if (v == null || v.isBlank()) {
                return false;
            }
            if (!in.isEmpty()) {
                for (String p : in) {
                    if (v.startsWith(p)) {
                        return true;
                    }
                }
                return false;
            }
            for (String p : notIn) {
                if (v.startsWith(p)) {
                    return false;
                }
            }
            return true;
        }
    }

    record DrmpAtLeast(int threshold) implements Matcher {

        @Override
        public boolean evaluate(RoutingContext ctx) {
            return ctx.drmpPriority() >= threshold;
        }
    }

    record IngressPeerIn(Set<String> ids) implements Matcher {

        public IngressPeerIn {
            ids = Set.copyOf(ids);
        }

        public IngressPeerIn(List<String> idList) {
            this(Set.copyOf(idList));
        }

        @Override
        public boolean evaluate(RoutingContext ctx) {
            return ids.contains(ctx.ingressPeerId());
        }
    }

    record FlagIs(Bit bit) implements Matcher {

        public enum Bit { REQUEST, PROXYABLE, ERROR, RETRANSMIT }

        @Override
        public boolean evaluate(RoutingContext ctx) {
            return switch (bit) {
                case REQUEST -> ctx.isRequest();
                case PROXYABLE -> ctx.proxiable();
                case ERROR -> ctx.errorBit() != 0;
                case RETRANSMIT -> ctx.retransmitBit() != 0;
            };
        }
    }

    record Always(boolean result) implements Matcher {

        public static final Always TRUE = new Always(true);

        @Override
        public boolean evaluate(RoutingContext ctx) {
            return result;
        }
    }

    final class PathNames {

        private static final Map<String, String> ALIASES = Map.ofEntries(
                Map.entry("IMSI", "IMSI"),
                Map.entry("USER_NAME", "IMSI"),
                Map.entry("1", "IMSI"),
                Map.entry("MSISDN", "MSISDN"),
                Map.entry("701", "MSISDN"),
                Map.entry("VISITED_PLMN", "VISITED_PLMN"),
                Map.entry("VISITED_PLMN_ID", "VISITED_PLMN"),
                Map.entry("1407", "VISITED_PLMN"),
                Map.entry("FRAMED_IP", "FRAMED_IP"),
                Map.entry("FRAMED_IP_ADDRESS", "FRAMED_IP"),
                Map.entry("8", "FRAMED_IP"),
                Map.entry("APN", "APN"),
                Map.entry("CALLED_STATION_ID", "APN"),
                Map.entry("30", "APN"),
                Map.entry("DEST_HOST", "DEST_HOST"),
                Map.entry("DESTINATION_HOST", "DEST_HOST"),
                Map.entry("293", "DEST_HOST"),
                Map.entry("DEST_REALM", "DEST_REALM"),
                Map.entry("DESTINATION_REALM", "DEST_REALM"),
                Map.entry("283", "DEST_REALM"),
                Map.entry("ORIG_HOST", "ORIG_HOST"),
                Map.entry("ORIGIN_HOST", "ORIG_HOST"),
                Map.entry("264", "ORIG_HOST"),
                Map.entry("ORIG_REALM", "ORIG_REALM"),
                Map.entry("ORIGIN_REALM", "ORIG_REALM"),
                Map.entry("296", "ORIG_REALM"),
                Map.entry("SESSION_ID", "SESSION_ID"),
                Map.entry("263", "SESSION_ID"));

        private static final Set<String> PLMN_SOURCES = Set.of("IMSI", "MSISDN", "VISITED_PLMN");

        public static String canonical(String rawPath) {
            String k = normalize(rawPath);
            return ALIASES.getOrDefault(k, k);
        }

        public static String plmnKey(String rawKey) {
            String k = normalize(rawKey);
            return PLMN_SOURCES.contains(k) ? k : "IMSI";
        }

        public static boolean isKnownKey(String canonical) {
            return Set.of("IMSI", "MSISDN", "VISITED_PLMN", "FRAMED_IP", "APN",
                    "DEST_HOST", "DEST_REALM", "ORIG_HOST", "ORIG_REALM", "SESSION_ID").contains(canonical)
                    || canonical.equals("FRAMED_IP_APN");
        }

        private static String normalize(String raw) {
            return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace('.', '_');
        }

        private PathNames() {
        }
    }

    private static boolean matchString(String target, Enum<?> op, String value, Pattern pattern) {
        if (target == null) {
            return false;
        }
        return switch (op.name()) {
            case "EQ" -> target.equalsIgnoreCase(value);
            case "SUFFIX" -> lowercase(target).endsWith(lowercase(value));
            case "REGEX" -> pattern != null && pattern.matcher(target).matches();
            default -> false;
        };
    }

    private static String lowercase(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
