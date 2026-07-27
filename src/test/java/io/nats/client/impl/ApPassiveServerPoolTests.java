package io.nats.client.impl;

import io.nats.client.Options;
import io.nats.client.ServerPool;
import io.nats.client.support.NatsUri;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the wrapper/equivalence logic added in PR #2 ("Better host comparison").
 * <p>
 * These exercise {@link ApPassiveServerPool}'s new resolution-based equivalence
 * ({@code isEquivalent}, {@code resolve}, {@code resolvedMap}, {@code resolveMode}) and the
 * peek/next traversal in isolation, using a deterministic fake {@link ServerPool} so no real
 * DNS or NATS servers are required. The existing {@code ApTests} cover only the
 * boot-real-servers-on-localhost path, where hostnames are never resolved.
 */
public class ApPassiveServerPoolTests {

    // ----------------------------------------------------------------------------------------
    // Test doubles / helpers
    // ----------------------------------------------------------------------------------------
    /** Deterministic ServerPool for unit-testing ApPassiveServerPool's wrapper logic. */
    static class FakeServerPool implements ServerPool {
        final Map<String, List<String>> dns = new HashMap<>();   // host -> resolved IPs
        final List<NatsUri> peekQueue = new ArrayList<>();        // what peek/next hand back, in order
        int pos = 0;
        List<String> serverList = new ArrayList<>();
        boolean acceptResult = true;
        boolean secure = false;
        final List<String> resolveCalls = new ArrayList<>();      // records each resolve, for cache asserts
        final List<NatsUri> connectSucceededCalls = new ArrayList<>();
        final List<NatsUri> connectFailedCalls = new ArrayList<>();
        Options initializedWith;

        @Override public void initialize(@NonNull Options opts) { initializedWith = opts; }

        @Override public boolean acceptDiscoveredUrls(@NonNull List<@NonNull String> discoveredServers) { return acceptResult; }

        @Override public NatsUri peekNextServer() {
            return peekQueue.isEmpty() ? null : peekQueue.get(pos % peekQueue.size());
        }

        @Override public NatsUri nextServer() {
            if (peekQueue.isEmpty()) {
                return null;
            }
            NatsUri n = peekQueue.get(pos % peekQueue.size());
            pos++;
            return n;
        }

        @Override public List<String> resolveHostToIps(@NonNull String host) { return dns.get(host); }

        @Override public List<String> resolveHostToIps(@NonNull String host, boolean maxOne, boolean ipv6) {
            resolveCalls.add(host);
            List<String> ips = dns.get(host);
            if (ips == null) {
                return null;
            }
            return (maxOne && !ips.isEmpty()) ? ips.subList(0, 1) : ips;
        }

        @Override public void connectSucceeded(@NonNull NatsUri nuri) { connectSucceededCalls.add(nuri); }
        @Override public void connectFailed(@NonNull NatsUri nuri) { connectFailedCalls.add(nuri); }
        @Override public @NonNull List<String> getServerList() { return serverList; }
        @Override public boolean hasSecureServer() { return secure; }
    }

    private static NatsUri uri(String s) {
        try {
            return new NatsUri(s);
        }
        catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    private static Options optionsWith(Options.HostnameResolveMode mode) {
        return Options.builder().hostnameResolveMode(mode).build();
    }

    // ----------------------------------------------------------------------------------------
    // A. isEquivalent - direct host-string match (resolution disabled)
    // ----------------------------------------------------------------------------------------

    @Test
    public void isEquivalent_A1_sameHostString_isSkipped() {
        FakeServerPool fake = new FakeServerPool();
        ApPassiveServerPool sp = new ApPassiveServerPool(fake);
        sp.resolveMode = Options.HostnameResolveMode.Unresolved;

        sp.activeConnectSucceeded(uri("nats://alpha.example.com:4222"));
        fake.peekQueue.add(uri("nats://alpha.example.com:4222")); // same host -> skipped
        fake.peekQueue.add(uri("nats://beta.example.com:4222"));

        NatsUri peeked = sp.peekNextServer();
        assertNotNull(peeked);
        assertEquals("beta.example.com", peeked.getHost());
    }


    @Test
    public void isEquivalent_A2_differentHost_isReturned() {
        FakeServerPool fake = new FakeServerPool();
        ApPassiveServerPool sp = new ApPassiveServerPool(fake);
        sp.resolveMode = Options.HostnameResolveMode.Unresolved;

        sp.activeConnectSucceeded(uri("nats://alpha.example.com:4222"));
        fake.peekQueue.add(uri("nats://beta.example.com:4222"));

        NatsUri peeked = sp.peekNextServer();
        assertNotNull(peeked);
        assertEquals("beta.example.com", peeked.getHost());
    }

    // ----------------------------------------------------------------------------------------
    // B. isEquivalent - resolution branch (ResolveToAll)
    // ----------------------------------------------------------------------------------------

    @Test
    public void isEquivalent_B1_twoDistinctHostnames_neverEquivalent() {
        // Discovered servers always come back as IPs and users supply hostnames, so two distinct
        // hostnames never get compared by resolution - only by direct equality. A shared resolved
        // IP does NOT make them equivalent.
        FakeServerPool fake = new FakeServerPool();
        ApPassiveServerPool sp = new ApPassiveServerPool(fake);
        sp.resolveMode = Options.HostnameResolveMode.ResolveToAll;
        sp.resolvedMap.put("a.example.com", Collections.singletonList("10.0.0.1"));
        sp.resolvedMap.put("b.example.com", Arrays.asList("10.0.0.1", "10.0.0.2")); // shares 10.0.0.1

        sp.activeConnectSucceeded(uri("nats://a.example.com:4222"));
        fake.peekQueue.add(uri("nats://b.example.com:4222"));

        NatsUri peeked = sp.peekNextServer();
        assertNotNull(peeked);
        assertEquals("b.example.com", peeked.getHost()); // not skipped despite shared IP
    }

    @Test
    public void isEquivalent_B2_activeIp_testHostnameResolvesToIt_equivalent() {
        // active is a literal IP; a peer hostname that resolves to that IP is equivalent (skipped).
        // The distinct server c.example.com proves the skip actually happened.
        FakeServerPool fake = new FakeServerPool();
        ApPassiveServerPool sp = new ApPassiveServerPool(fake);
        sp.resolveMode = Options.HostnameResolveMode.ResolveToAll;
        sp.resolvedMap.put("b.example.com", Collections.singletonList("10.0.0.1"));

        sp.activeConnectSucceeded(uri("nats://10.0.0.1:4222")); // literal IP, not cached
        fake.peekQueue.add(uri("nats://b.example.com:4222")); // resolves to active IP -> skipped
        fake.peekQueue.add(uri("nats://c.example.com:4222")); // distinct -> returned

        NatsUri peeked = sp.peekNextServer();
        assertNotNull(peeked);
        assertEquals("c.example.com", peeked.getHost());
    }

    @Test
    public void isEquivalent_B3_activeIp_testHostnameDoesNotResolveToIt_notEquivalent() {
        FakeServerPool fake = new FakeServerPool();
        ApPassiveServerPool sp = new ApPassiveServerPool(fake);
        sp.resolveMode = Options.HostnameResolveMode.ResolveToAll;
        sp.resolvedMap.put("b.example.com", Collections.singletonList("10.0.0.9")); // resolves elsewhere

        sp.activeConnectSucceeded(uri("nats://10.0.0.1:4222"));
        fake.peekQueue.add(uri("nats://b.example.com:4222"));

        NatsUri peeked = sp.peekNextServer();
        assertNotNull(peeked);
        assertEquals("b.example.com", peeked.getHost()); // not skipped
    }

    @Test
    public void isEquivalent_B4_activeHostname_testIpInResolution_equivalent() {
        // Reverse of B2: active is a resolvable hostname; a peer literal IP it resolves to is
        // equivalent (skipped).
        FakeServerPool fake = new FakeServerPool();
        ApPassiveServerPool sp = new ApPassiveServerPool(fake);
        sp.resolveMode = Options.HostnameResolveMode.ResolveToAll;
        sp.resolvedMap.put("a.example.com", Collections.singletonList("10.0.0.1"));

        sp.activeConnectSucceeded(uri("nats://a.example.com:4222"));
        fake.peekQueue.add(uri("nats://10.0.0.1:4222"));      // active resolves to this IP -> skipped
        fake.peekQueue.add(uri("nats://c.example.com:4222")); // distinct -> returned

        NatsUri peeked = sp.peekNextServer();
        assertNotNull(peeked);
        assertEquals("c.example.com", peeked.getHost());

        // comparing a literal-IP peer must not resolve it or pollute the cache
        assertFalse(sp.resolvedMap.containsKey("10.0.0.1"));
        assertFalse(fake.resolveCalls.contains("10.0.0.1"));
    }

    @Test
    public void isEquivalent_B5_activeHostname_testIpNotInResolution_notEquivalent() {
        FakeServerPool fake = new FakeServerPool();
        ApPassiveServerPool sp = new ApPassiveServerPool(fake);
        sp.resolveMode = Options.HostnameResolveMode.ResolveToAll;
        sp.resolvedMap.put("a.example.com", Collections.singletonList("10.0.0.1"));

        sp.activeConnectSucceeded(uri("nats://a.example.com:4222"));
        fake.peekQueue.add(uri("nats://10.0.0.9:4222")); // not one of active's resolved IPs

        NatsUri peeked = sp.peekNextServer();
        assertNotNull(peeked);
        assertEquals("10.0.0.9", peeked.getHost()); // not skipped
    }

    // ----------------------------------------------------------------------------------------
    // C. resolve(...) cache population (assert on resolvedMap after initialize)
    // ----------------------------------------------------------------------------------------

    @Test
    public void resolve_C1_hostnamesAreCached() {
        FakeServerPool fake = new FakeServerPool();
        fake.dns.put("a.example.com", Collections.singletonList("10.0.0.1"));
        fake.dns.put("b.example.com", Arrays.asList("10.0.0.2", "10.0.0.3"));
        fake.serverList = Arrays.asList("nats://a.example.com:4222", "nats://b.example.com:4222");

        ApPassiveServerPool sp = new ApPassiveServerPool(fake);
        sp.initialize(optionsWith(Options.HostnameResolveMode.ResolveToAll));

        assertEquals(2, sp.resolvedMap.size());
        assertEquals(Collections.singletonList("10.0.0.1"), sp.resolvedMap.get("a.example.com"));
        assertEquals(Arrays.asList("10.0.0.2", "10.0.0.3"), sp.resolvedMap.get("b.example.com"));
    }

    @Test
    public void resolve_C2_literalIpIsNotCached() {
        FakeServerPool fake = new FakeServerPool();
        fake.serverList = Collections.singletonList("nats://10.0.0.1:4222");

        ApPassiveServerPool sp = new ApPassiveServerPool(fake);
        sp.initialize(optionsWith(Options.HostnameResolveMode.ResolveToAll));

        assertTrue(sp.resolvedMap.isEmpty());
        assertTrue(fake.resolveCalls.isEmpty()); // never even attempted
    }

    @Test
    public void resolve_C3_websocketIsNotCached() {
        FakeServerPool fake = new FakeServerPool();
        fake.dns.put("a.example.com", Collections.singletonList("10.0.0.1"));
        fake.serverList = Collections.singletonList("ws://a.example.com:4222");

        ApPassiveServerPool sp = new ApPassiveServerPool(fake);
        sp.initialize(optionsWith(Options.HostnameResolveMode.ResolveToAll));

        assertTrue(sp.resolvedMap.isEmpty());
    }

    @Test
    public void resolve_C4_unresolvedModeCachesNothing() {
        FakeServerPool fake = new FakeServerPool();
        fake.dns.put("a.example.com", Collections.singletonList("10.0.0.1"));
        fake.serverList = Collections.singletonList("nats://a.example.com:4222");

        ApPassiveServerPool sp = new ApPassiveServerPool(fake);
        sp.initialize(optionsWith(Options.HostnameResolveMode.Unresolved));

        assertTrue(sp.resolvedMap.isEmpty());
        assertTrue(fake.resolveCalls.isEmpty());
    }

    @Test
    public void resolve_C5_emptyResolutionCachesPlaceholderAndIsNotReResolved() {
        FakeServerPool fake = new FakeServerPool();
        fake.dns.put("empty.example.com", new ArrayList<>()); // resolves to empty list
        fake.serverList = Collections.singletonList("nats://empty.example.com:4222");

        ApPassiveServerPool sp = new ApPassiveServerPool(fake);
        sp.initialize(optionsWith(Options.HostnameResolveMode.ResolveToAll));

        // "resolved to nothing" is recorded as an (empty) placeholder, not left absent
        assertTrue(sp.resolvedMap.containsKey("empty.example.com"));
        assertTrue(sp.resolvedMap.get("empty.example.com").isEmpty());

        // so a second pass finds the placeholder and does not resolve again
        sp.activeConnectSucceeded(uri("nats://empty.example.com:4222"));
        assertEquals(1, Collections.frequency(fake.resolveCalls, "empty.example.com"));
    }

    @Test
    public void resolve_C6_resultsAreCachedNotReResolved() {
        FakeServerPool fake = new FakeServerPool();
        fake.dns.put("a.example.com", Collections.singletonList("10.0.0.1"));
        fake.serverList = Collections.singletonList("nats://a.example.com:4222");

        ApPassiveServerPool sp = new ApPassiveServerPool(fake);
        sp.initialize(optionsWith(Options.HostnameResolveMode.ResolveToAll)); // resolves a once
        sp.activeConnectSucceeded(uri("nats://a.example.com:4222"));                 // same host -> cache hit

        assertEquals(1, Collections.frequency(fake.resolveCalls, "a.example.com"));
    }

    @Test
    public void resolve_C7_malformedServerStringFailsFast() {
        FakeServerPool fake = new FakeServerPool();
        fake.dns.put("good.example.com", Collections.singletonList("10.0.0.1"));
        fake.serverList = Arrays.asList("nats://bad host:4222", "nats://good.example.com:4222");

        ApPassiveServerPool sp = new ApPassiveServerPool(fake);
        // a malformed server string is treated as user error and surfaced (wrapped URISyntaxException)
        assertThrows(RuntimeException.class,
            () -> sp.initialize(optionsWith(Options.HostnameResolveMode.ResolveToAll)));
    }

    // ----------------------------------------------------------------------------------------
    // D. acceptDiscoveredUrls
    // ----------------------------------------------------------------------------------------

    @Test
    public void acceptDiscoveredUrls_D1_accepted_reResolves() {
        FakeServerPool fake = new FakeServerPool();
        fake.acceptResult = true;
        fake.dns.put("new.example.com", Collections.singletonList("10.0.0.5"));

        ApPassiveServerPool sp = new ApPassiveServerPool(fake);
        sp.initialize(optionsWith(Options.HostnameResolveMode.ResolveToAll)); // empty server list

        fake.serverList = Collections.singletonList("nats://new.example.com:4222"); // discovered
        assertTrue(sp.acceptDiscoveredUrls(Collections.singletonList("nats://new.example.com:4222")));
        assertTrue(sp.resolvedMap.containsKey("new.example.com"));
    }

    @Test
    public void acceptDiscoveredUrls_D2_rejected_doesNotReResolve() {
        FakeServerPool fake = new FakeServerPool();
        fake.acceptResult = false;
        fake.dns.put("new.example.com", Collections.singletonList("10.0.0.5"));

        ApPassiveServerPool sp = new ApPassiveServerPool(fake);
        sp.initialize(optionsWith(Options.HostnameResolveMode.ResolveToAll));

        fake.serverList = Collections.singletonList("nats://new.example.com:4222");
        assertFalse(sp.acceptDiscoveredUrls(Collections.singletonList("nats://new.example.com:4222")));
        assertTrue(sp.resolvedMap.isEmpty());
    }

    // ----------------------------------------------------------------------------------------
    // E. setActiveServer
    // ----------------------------------------------------------------------------------------

    @Test
    public void setActiveServer_E1_cachesActiveHostname() {
        FakeServerPool fake = new FakeServerPool();
        fake.dns.put("a.example.com", Collections.singletonList("10.0.0.1"));

        ApPassiveServerPool sp = new ApPassiveServerPool(fake);
        sp.resolveMode = Options.HostnameResolveMode.ResolveToAll;

        NatsUri active = uri("nats://a.example.com:4222");
        sp.activeConnectSucceeded(active);

        assertSame(active, sp.activeServerRef.get());
        assertEquals(Collections.singletonList("10.0.0.1"), sp.resolvedMap.get("a.example.com"));
    }

    @Test
    public void setActiveServer_E2_ipActiveHostNotCached() {
        FakeServerPool fake = new FakeServerPool();
        ApPassiveServerPool sp = new ApPassiveServerPool(fake);
        sp.resolveMode = Options.HostnameResolveMode.ResolveToAll;

        sp.activeConnectSucceeded(uri("nats://10.0.0.1:4222"));

        assertTrue(sp.resolvedMap.isEmpty());
    }

    // ----------------------------------------------------------------------------------------
    // F. peek/next traversal & loop-around termination
    // ----------------------------------------------------------------------------------------

    @Test
    public void traversal_F1_nullActive_peekDelegatesDirectly() {
        FakeServerPool fake = new FakeServerPool();
        ApPassiveServerPool sp = new ApPassiveServerPool(fake);

        NatsUri only = uri("nats://a.example.com:4222");
        fake.peekQueue.add(only);

        assertSame(only, sp.peekNextServer()); // no active -> straight delegation, no skipping
    }

    @Test
    public void traversal_F2_nullActive_nextDelegatesDirectly() {
        FakeServerPool fake = new FakeServerPool();
        ApPassiveServerPool sp = new ApPassiveServerPool(fake);

        NatsUri only = uri("nats://a.example.com:4222");
        fake.peekQueue.add(only);

        assertSame(only, sp.nextServer());
    }

    @Test
    public void traversal_F3_nextSkipsEquivalentsReturnsFirstDistinct() {
        FakeServerPool fake = new FakeServerPool();
        ApPassiveServerPool sp = new ApPassiveServerPool(fake);
        sp.resolveMode = Options.HostnameResolveMode.Unresolved;

        sp.activeConnectSucceeded(uri("nats://h.example.com:4222"));
        fake.peekQueue.add(uri("nats://h.example.com:4222")); // equivalent (same host AND same port) - skipped
        fake.peekQueue.add(uri("nats://h.example.com:6222")); // distinct: same host, DIFFERENT port - now the first distinct
        fake.peekQueue.add(uri("nats://other.example.com:4222")); // not reached: 6222 is already distinct

        // isEquivalent now compares port first, so the same host on a different port is NOT equivalent -
        // h.example.com:6222 is the first distinct server and is returned ahead of other.example.com.
        NatsUri next = sp.nextServer();
        assertNotNull(next);
        assertEquals("h.example.com", next.getHost());
        assertEquals(6222, next.getPort());
    }

    @Test
    public void traversal_F4_peekLoopAroundTerminates() {
        FakeServerPool fake = new FakeServerPool();
        ApPassiveServerPool sp = new ApPassiveServerPool(fake);
        sp.resolveMode = Options.HostnameResolveMode.Unresolved;

        sp.activeConnectSucceeded(uri("nats://h.example.com:4222"));
        NatsUri sameRef = uri("nats://h.example.com:4222"); // only candidate, always equivalent
        fake.peekQueue.add(sameRef);

        // peek == firstPeek guard must break the loop and return the (equivalent) server
        assertSame(sameRef, sp.peekNextServer());
    }

    @Test
    public void traversal_F5_nextLoopAroundTerminates() {
        FakeServerPool fake = new FakeServerPool();
        ApPassiveServerPool sp = new ApPassiveServerPool(fake);
        sp.resolveMode = Options.HostnameResolveMode.Unresolved;

        sp.activeConnectSucceeded(uri("nats://h.example.com:4222"));
        NatsUri sameRef = uri("nats://h.example.com:4222");
        fake.peekQueue.add(sameRef);

        // server == firstServer guard must break the loop and return the (equivalent) server
        assertSame(sameRef, sp.nextServer());
    }

    // ----------------------------------------------------------------------------------------
    // G. pass-through delegation
    // ----------------------------------------------------------------------------------------

    @Test
    public void delegation_G_passesThroughToWrappedPool() {
        FakeServerPool fake = new FakeServerPool();
        fake.dns.put("a.example.com", Collections.singletonList("10.0.0.1"));
        fake.serverList = Collections.singletonList("nats://a.example.com:4222");
        fake.secure = true;

        ApPassiveServerPool sp = new ApPassiveServerPool(fake);
        Options opts = optionsWith(Options.HostnameResolveMode.Unresolved);
        sp.initialize(opts);

        assertSame(opts, fake.initializedWith);
        assertEquals(Collections.singletonList("10.0.0.1"), sp.resolveHostToIps("a.example.com"));
        assertSame(fake.serverList, sp.getServerList());
        assertTrue(sp.hasSecureServer());

        NatsUri ok = uri("nats://a.example.com:4222");
        NatsUri bad = uri("nats://b.example.com:4222");
        sp.connectSucceeded(ok);
        sp.connectFailed(bad);
        assertEquals(Collections.singletonList(ok), fake.connectSucceededCalls);
        assertEquals(Collections.singletonList(bad), fake.connectFailedCalls);
    }

    static class EquivalentTesterApPassiveServerPool extends ApPassiveServerPool {
        @Override
        protected @NonNull List<String> _resolveHostToIps(@NonNull String host) {
            if (host.equals("host") || host.equals("also")) {
                return Arrays.asList("192.168.0.1", "192.168.0.2");
            }
            return Collections.emptyList();
        }
    }

    @Test
    public void testIsEquivalent() throws URISyntaxException {
        NatsUri nuriHost4221 = new NatsUri("host:4221");
        NatsUri nuriHost4222 = new NatsUri("host:4222");
        NatsUri nuriAlso4221 = new NatsUri("also:4221");
        NatsUri nuriIp_168_01_4221 = new NatsUri("192.168.0.1:4221");
        NatsUri nuriIp_168_01_4222 = new NatsUri("192.168.0.1:4222");
        NatsUri nuriIp_168_02_4221 = new NatsUri("192.168.0.2:4221");
        NatsUri nuriIp_169_01_4221 = new NatsUri("192.169.0.1:4221");
        NatsUri nuriNoMatch4221 = new NatsUri("nomatch:4221");

        EquivalentTesterApPassiveServerPool pool = new EquivalentTesterApPassiveServerPool();
        pool.initialize(Options.builder().hostnameResolveMode(Options.HostnameResolveMode.Unresolved).build());

        // match in both unresolved and resolved
        assertTrue(pool.isEquivalent(nuriHost4221, nuriHost4221));
        assertTrue(pool.isEquivalent(nuriIp_168_01_4221, nuriIp_168_01_4221));

        // match in resolved but not unresolved
        assertFalse(pool.isEquivalent(nuriHost4221, nuriAlso4221));
        assertFalse(pool.isEquivalent(nuriHost4221, nuriIp_168_01_4221));

        // does not match in either resolved or unresolved
        assertFalse(pool.isEquivalent(nuriHost4221, nuriHost4222));
        assertFalse(pool.isEquivalent(nuriHost4221, nuriNoMatch4221));
        assertFalse(pool.isEquivalent(nuriIp_168_01_4221, nuriIp_168_01_4222));
        assertFalse(pool.isEquivalent(nuriIp_168_01_4221, nuriIp_168_02_4221));
        assertFalse(pool.isEquivalent(nuriIp_168_01_4221, nuriIp_169_01_4221));

        pool.initialize(Options.builder().hostnameResolveMode(Options.HostnameResolveMode.ResolveToAll).build());

        // match in both unresolved and resolved
        assertTrue(pool.isEquivalent(nuriHost4221, nuriHost4221));
        assertTrue(pool.isEquivalent(nuriIp_168_01_4221, nuriIp_168_01_4221));

        // match in resolved but not unresolved
        assertTrue(pool.isEquivalent(nuriHost4221, nuriAlso4221));
        assertTrue(pool.isEquivalent(nuriHost4221, nuriIp_168_01_4221));

        // does not match in either resolved or unresolved
        assertFalse(pool.isEquivalent(nuriHost4221, nuriHost4222));
        assertFalse(pool.isEquivalent(nuriHost4221, nuriNoMatch4221));
        assertFalse(pool.isEquivalent(nuriIp_168_01_4221, nuriIp_168_01_4222));
        assertFalse(pool.isEquivalent(nuriIp_168_01_4221, nuriIp_168_02_4221));
        assertFalse(pool.isEquivalent(nuriIp_168_01_4221, nuriIp_169_01_4221));
    }
}
