package com.anatomist.core.asmsolver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PackedTypeMetadataCacheTest {

    @Test
    void roundTripsMetadataAndRejectsCorruption(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("types.cache");
        AsmTypeMetadata metadata = new AsmTypeMetadata(
                "com.x.Foo", "java.lang.Object", List.of("java.io.Serializable"),
                1, "Lsig;", List.of(new AsmTypeMetadata.Field("value", "I", 2)),
                List.of(new AsmTypeMetadata.Method("run", "()V", null, 1)),
                List.of(new AsmTypeMetadata.Constructor("()V", 1)),
                List.of("java.lang.Deprecated"), List.of("com.x.Foo$Inner"));

        PackedTypeMetadataCache writer = PackedTypeMetadataCache.open(file, "fingerprint");
        writer.put(metadata);
        writer.write();

        PackedTypeMetadataCache reader = PackedTypeMetadataCache.open(file, "fingerprint");
        assertEquals(metadata, reader.get("com.x.Foo"));
        assertNull(reader.get("com.x.Missing"));

        byte[] corrupt = Files.readAllBytes(file);
        corrupt[corrupt.length / 2] ^= 1;
        Files.write(file, corrupt);
        PackedTypeMetadataCache recovered = PackedTypeMetadataCache.open(file, "fingerprint");
        assertNull(recovered.get("com.x.Foo"));
        assertFalse(Files.exists(file));
    }
}
