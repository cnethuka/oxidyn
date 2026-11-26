package dev.oxidyn;

import dev.oxidyn.client.SortSelfTest;
import dev.oxidyn.ffi.NativeBackend;
import dev.oxidyn.ffi.Natives;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SortParityTest {
    @Test
    void nativeSortMatchesVertexSorting() {
        assumeTrue(Runtime.version().feature() >= 22, "native path needs Java 22+");
        NativeBackend backend = Natives.get();
        assertNotNull(backend, Natives.status());
        assertTrue(SortSelfTest.check(backend));
    }
}
