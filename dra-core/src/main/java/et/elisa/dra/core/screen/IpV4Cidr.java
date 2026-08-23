package et.elisa.dra.core.screen;

public record IpV4Cidr(int network, int prefixBits) {

    public IpV4Cidr {
        if (prefixBits < 0 || prefixBits > 32) {
            throw new IllegalArgumentException("prefix must be 0..32");
        }
        int mask = mask(prefixBits);
        network = network & mask;
    }

    public static IpV4Cidr parse(String cidr) {
        int slash = cidr.indexOf('/');
        String addrPart = slash < 0 ? cidr : cidr.substring(0, slash);
        int bits = slash < 0 ? 32 : Integer.parseInt(cidr.substring(slash + 1));
        Integer ip = parseIp(addrPart);
        if (ip == null) {
            throw new IllegalArgumentException("bad IPv4: " + cidr);
        }
        return new IpV4Cidr(ip, bits);
    }

    public boolean contains(int address) {
        return (address & mask()) == network;
    }

    public boolean contains(String dottedIpv4) {
        Integer ip = parseIp(dottedIpv4);
        return ip != null && contains(ip);
    }

    private int mask() {
        return mask(prefixBits);
    }

    private static int mask(int bits) {
        return bits == 0 ? 0 : (int) (0xFFFF_FFFFL << (32 - bits));
    }

    public static Integer parseIp(String dotted) {
        if (dotted == null) {
            return null;
        }
        String[] parts = dotted.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        int out = 0;
        for (String p : parts) {
            if (p.isEmpty() || p.length() > 3) {
                return null;
            }
            int v;
            try {
                v = Integer.parseInt(p);
            } catch (NumberFormatException e) {
                return null;
            }
            if (v < 0 || v > 255 || (p.length() > 1 && p.charAt(0) == '0')) {
                return null;
            }
            out = (out << 8) | v;
        }
        return out;
    }
}
