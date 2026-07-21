package io.nats.client.impl;

import io.nats.client.ConnectionListener;
import io.nats.client.ErrorListener;
import io.nats.client.Options;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ApOptions} and its {@link ApOptions.Builder}.
 * <p>
 * These assert that the builder sets each field onto the built {@link ApOptions} exactly as
 * supplied, that {@code build()} fills in the documented defaults, and that the copy constructor
 * {@link ApOptions.Builder#Builder(ApOptions)} carries every field across (rebuilding {@code options}
 * but sharing the pool / listener references). No servers or connections are involved.
 */
public class ApOptionsTests {

    // ----------------------------------------------------------------------------------------
    // A. build() defaults
    // ----------------------------------------------------------------------------------------

    @Test
    public void defaults_A1_emptyBuilderFillsOptionsAndPassiveErrorListener() {
        ApOptions ap = ApOptions.builder().build();

        // supplied defaults
        assertNotNull(ap.options);
        assertNotNull(ap.passiveErrorListener);

        // everything else stays null - no default is invented
        assertNull(ap.activeServerPool);
        assertNull(ap.passiveServerPool);
        assertNull(ap.passiveConnectionListener);
    }

    @Test
    public void defaults_A2_suppliedOptionsAreKeptNotReplaced() {
        Options options = Options.builder().build();
        ApOptions ap = ApOptions.builder(options).build();
        assertSame(options, ap.options); // the passed instance is used as-is

        ApOptions ap2 = ApOptions.builder().options(options).build();
        assertSame(options, ap2.options);
    }

    @Test
    public void defaults_A3_suppliedPassiveErrorListenerIsKeptNotReplaced() {
        ErrorListener el = new ErrorListener() {};
        ApOptions ap = ApOptions.builder().passiveErrorListener(el).build();
        assertSame(el, ap.passiveErrorListener); // default only applies when none supplied
    }

    // ----------------------------------------------------------------------------------------
    // B. every setter lands on the built ApOptions
    // ----------------------------------------------------------------------------------------

    @Test
    public void setters_B1_allFieldsAreSetThrough() {
        Options options = Options.builder().server("nats://example.com:4222").build();
        ApServerPool activePool = new ApPassiveServerPool();
        ApServerPool passivePool = new ApPassiveServerPool();
        ConnectionListener cl = (conn, type) -> {};
        ErrorListener el = new ErrorListener() {};

        ApOptions ap = ApOptions.builder()
            .options(options)
            .activeServerPool(activePool)
            .passiveServerPool(passivePool)
            .passiveConnectionListener(cl)
            .passiveErrorListener(el)
            .build();

        assertSame(options, ap.options);
        assertSame(activePool, ap.activeServerPool);
        assertSame(passivePool, ap.passiveServerPool);
        assertSame(cl, ap.passiveConnectionListener);
        assertSame(el, ap.passiveErrorListener);
    }

    @Test
    public void setters_B2_activeAndPassivePoolsAreIndependent() {
        ApServerPool activePool = new ApPassiveServerPool();
        ApServerPool passivePool = new ApPassiveServerPool();

        ApOptions ap = ApOptions.builder()
            .activeServerPool(activePool)
            .passiveServerPool(passivePool)
            .build();

        assertSame(activePool, ap.activeServerPool);
        assertSame(passivePool, ap.passiveServerPool);
        assertNotSame(ap.activeServerPool, ap.passiveServerPool);
    }

    // ----------------------------------------------------------------------------------------
    // C. copy constructor Builder(ApOptions)
    // ----------------------------------------------------------------------------------------

    @Test
    public void copy_C1_carriesEveryField() {
        Options options = Options.builder().server("nats://example.com:4222").build();
        ApServerPool activePool = new ApPassiveServerPool();
        ApServerPool passivePool = new ApPassiveServerPool();
        ConnectionListener cl = (conn, type) -> {};
        ErrorListener el = new ErrorListener() {};

        ApOptions original = ApOptions.builder()
            .options(options)
            .activeServerPool(activePool)
            .passiveServerPool(passivePool)
            .passiveConnectionListener(cl)
            .passiveErrorListener(el)
            .build();

        ApOptions copy = new ApOptions.Builder(original).build();

        // pools & listeners are carried by reference
        assertSame(activePool, copy.activeServerPool);
        assertSame(passivePool, copy.passiveServerPool);
        assertSame(cl, copy.passiveConnectionListener);
        assertSame(el, copy.passiveErrorListener);

        // options is rebuilt (a fresh instance) but carries the same servers
        assertNotSame(original.options, copy.options);
        assertEquals(original.options.getServers(), copy.options.getServers());
    }

    @Test
    public void copy_C2_nullSourceIsSafeAndYieldsDefaults() {
        // Builder(null) must not throw; build() then fills the usual defaults
        ApOptions copy = new ApOptions.Builder(null).build();

        assertNotNull(copy.options);
        assertNotNull(copy.passiveErrorListener);
        assertNull(copy.activeServerPool);
        assertNull(copy.passiveServerPool);
        assertNull(copy.passiveConnectionListener);
    }
}
