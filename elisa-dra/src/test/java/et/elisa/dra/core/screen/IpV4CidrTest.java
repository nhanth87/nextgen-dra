package et.elisa.dra.core.screen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IpV4CidrTest {

    @Test
    void parseAndContains() {
        IpV4Cidr cidr = IpV4Cidr.parse("10.20.30.0/24");
        assertTrue(cidr.contains("10.20.30.1"));
        assertTrue(cidr.contains("10.20.30.255"));
        assertFalse(cidr.contains("10.20.31.1"));
    }

    @Test
    void hostBitsOfNetworkAreNormalized() {
        IpV4Cidr cidr = IpV4Cidr.parse("10.0.0.240/24");
        assertTrue(cidr.contains("10.0.0.7"));
        assertFalse(cidr.contains("10.0.1.7"));
    }

    @Test
    void slash32ExactMatch() {
        IpV4Cidr cidr = IpV4Cidr.parse("192.168.1.7/32");
        assertTrue(cidr.contains("192.168.1.7"));
        assertFalse(cidr.contains("192.168.1.8"));
    }

    @Test
    void slashZeroMatchesAnyValidAddress() {
        IpV4Cidr cidr = IpV4Cidr.parse("9.9.9.9/0");
        assertTrue(cidr.contains("1.2.3.4"));
        assertTrue(cidr.contains("200.11.22.33"));
    }

    @Test
    void noPrefixDefaultsToHost() {
        IpV4Cidr cidr = IpV4Cidr.parse("10.1.2.3");
        assertTrue(cidr.contains("10.1.2.3"));
        assertFalse(cidr.contains("10.1.2.4"));
    }

    @Test
    void malformedAddressesRejected() {
        assertEquals(null, IpV4Cidr.parseIp("abc"));
        assertEquals(null, IpV4Cidr.parseIp("1.2.3"));
        assertEquals(null, IpV4Cidr.parseIp("1.2.3.4.5"));
        assertEquals(null, IpV4Cidr.parseIp("256.1.1.1"));
        assertEquals(null, IpV4Cidr.parseIp("01.2.3.4"));
        assertEquals(null, IpV4Cidr.parseIp(""));
        assertEquals(null, IpV4Cidr.parseIp("::1"));
        assertThrows(IllegalArgumentException.class, () -> IpV4Cidr.parse("10.0.0.0/33"));
        assertThrows(IllegalArgumentException.class, () -> IpV4Cidr.parse("10.0.0/24"));
    }

    @Test
    void intContainsUsesMaskMath() {
        IpV4Cidr cidr = new IpV4Cidr(IpV4Cidr.parseIp("172.16.5.9"), 12);
        assertTrue(cidr.contains(IpV4Cidr.parseIp("172.19.255.254")));
        assertFalse(cidr.contains(IpV4Cidr.parseIp("172.32.0.1")));
    }
}
