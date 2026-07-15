package com.anatomist.core.asmsolver;

import com.anatomist.core.logging.AnatomistLog;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32C;

/** Read-mostly packed cache of ASM type metadata. */
final class PackedTypeMetadataCache {

    private static final int MAGIC = 0x41545459; // ATTY
    private static final int VERSION = 1;

    private final Path file;
    private final String fingerprint;
    private final byte[] packed;
    private final int[] recordOffsets;
    private final int[] nameOffsets;
    private final int[] nameLengths;
    private final Map<String, AsmTypeMetadata> used = new HashMap<>();
    private long hits;
    private long misses;

    private PackedTypeMetadataCache(Path file, String fingerprint, byte[] packed,
                                    int[] recordOffsets, int[] nameOffsets, int[] nameLengths) {
        this.file = file;
        this.fingerprint = fingerprint;
        this.packed = packed;
        this.recordOffsets = recordOffsets;
        this.nameOffsets = nameOffsets;
        this.nameLengths = nameLengths;
    }

    static PackedTypeMetadataCache open(Path file, String fingerprint) {
        if (file == null) return null;
        if (!Files.isRegularFile(file)) {
            return new PackedTypeMetadataCache(file, fingerprint, new byte[0],
                    new int[0], new int[0], new int[0]);
        }
        try {
            return read(file, fingerprint);
        } catch (IOException | RuntimeException e) {
            AnatomistLog.debug("packed type cache: ignoring " + file + " (" + e.getMessage() + ")");
            try { Files.deleteIfExists(file); } catch (IOException ignored) {}
            return new PackedTypeMetadataCache(file, fingerprint, new byte[0],
                    new int[0], new int[0], new int[0]);
        }
    }

    AsmTypeMetadata get(String fqn) {
        AsmTypeMetadata current = used.get(fqn);
        if (current != null) {
            hits++;
            return current;
        }
        byte[] query = fqn.getBytes(StandardCharsets.UTF_8);
        int low = 0;
        int high = recordOffsets.length - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            int comparison = compare(query, nameOffsets[middle], nameLengths[middle]);
            if (comparison == 0) {
                AsmTypeMetadata metadata = decode(recordOffsets[middle]);
                used.put(fqn, metadata);
                hits++;
                return metadata;
            }
            if (comparison < 0) high = middle - 1; else low = middle + 1;
        }
        misses++;
        return null;
    }

    void put(AsmTypeMetadata metadata) {
        if (metadata != null) used.put(metadata.fqn(), metadata);
    }

    void write() {
        if (file == null || used.isEmpty()) return;
        try {
            Files.createDirectories(file.getParent());
            List<AsmTypeMetadata> sorted = new ArrayList<>(used.values());
            sorted.sort(Comparator.comparing(AsmTypeMetadata::fqn));
            ByteArrayOutputStream bodyBytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bodyBytes)) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                writeString(out, fingerprint);
                out.writeInt(sorted.size());
                for (AsmTypeMetadata metadata : sorted) {
                    byte[] record = encode(metadata);
                    out.writeInt(record.length);
                    out.write(record);
                }
            }
            byte[] body = bodyBytes.toByteArray();
            CRC32C crc = new CRC32C();
            crc.update(body, 0, body.length);
            ByteBuffer complete = ByteBuffer.allocate(body.length + Integer.BYTES);
            complete.put(body).putInt((int) crc.getValue());
            Path temporary = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
            try {
                Files.write(temporary, complete.array());
                try {
                    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
            AnatomistLog.debug("packed type cache: wrote types=" + sorted.size()
                    + " bytes=" + complete.capacity());
        } catch (IOException | RuntimeException e) {
            AnatomistLog.debug("packed type cache: write failed (" + e.getMessage() + ")");
        }
    }

    String stats() {
        return "types=" + recordOffsets.length + " hits=" + hits + " misses=" + misses
                + " packed_bytes=" + packed.length;
    }

    private static PackedTypeMetadataCache read(Path file, String fingerprint) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length < 24) throw new IOException("packed type cache is truncated");
        int storedCrc = ByteBuffer.wrap(bytes, bytes.length - Integer.BYTES, Integer.BYTES).getInt();
        CRC32C crc = new CRC32C();
        crc.update(bytes, 0, bytes.length - Integer.BYTES);
        if ((int) crc.getValue() != storedCrc) throw new IOException("packed type cache CRC mismatch");
        ByteBuffer in = ByteBuffer.wrap(bytes, 0, bytes.length - Integer.BYTES);
        if (in.getInt() != MAGIC || in.getInt() != VERSION) {
            throw new IOException("unsupported packed type cache");
        }
        String storedFingerprint = readString(in);
        if (!fingerprint.equals(storedFingerprint)) throw new IOException("type cache fingerprint mismatch");
        int count = in.getInt();
        if (count < 0) throw new IOException("invalid packed type count");
        int[] records = new int[count];
        int[] names = new int[count];
        int[] lengths = new int[count];
        for (int i = 0; i < count; i++) {
            if (in.remaining() < 8) throw new IOException("truncated packed type record");
            int length = in.getInt();
            if (length < 4 || length > in.remaining()) throw new IOException("invalid packed type record");
            int record = in.position();
            int nameLength = in.getInt();
            if (nameLength < 0 || nameLength > length - 4) throw new IOException("invalid packed type name");
            records[i] = record;
            names[i] = in.position();
            lengths[i] = nameLength;
            in.position(record + length);
        }
        if (in.hasRemaining()) throw new IOException("trailing packed type data");
        return new PackedTypeMetadataCache(file, fingerprint, bytes, records, names, lengths);
    }

    private AsmTypeMetadata decode(int recordOffset) {
        ByteBuffer in = ByteBuffer.wrap(packed);
        in.position(recordOffset);
        String fqn = readString(in);
        String superFqn = readString(in);
        int access = in.getInt();
        String signature = readString(in);
        List<String> interfaces = readStrings(in);
        int fieldCount = checkedCount(in);
        List<AsmTypeMetadata.Field> fields = new ArrayList<>(fieldCount);
        for (int i = 0; i < fieldCount; i++) {
            fields.add(new AsmTypeMetadata.Field(readString(in), readString(in), in.getInt()));
        }
        int methodCount = checkedCount(in);
        List<AsmTypeMetadata.Method> methods = new ArrayList<>(methodCount);
        for (int i = 0; i < methodCount; i++) {
            methods.add(new AsmTypeMetadata.Method(readString(in), readString(in),
                    readString(in), in.getInt()));
        }
        int constructorCount = checkedCount(in);
        List<AsmTypeMetadata.Constructor> constructors = new ArrayList<>(constructorCount);
        for (int i = 0; i < constructorCount; i++) {
            constructors.add(new AsmTypeMetadata.Constructor(readString(in), in.getInt()));
        }
        return new AsmTypeMetadata(fqn, superFqn, interfaces, access, signature,
                fields, methods, constructors, readStrings(in), readStrings(in));
    }

    private static byte[] encode(AsmTypeMetadata metadata) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            writeString(out, metadata.fqn());
            writeString(out, metadata.superFqn());
            out.writeInt(metadata.access());
            writeString(out, metadata.signature());
            writeStrings(out, metadata.interfaceFqns());
            out.writeInt(metadata.fields().size());
            for (AsmTypeMetadata.Field field : metadata.fields()) {
                writeString(out, field.name()); writeString(out, field.descriptor()); out.writeInt(field.access());
            }
            out.writeInt(metadata.methods().size());
            for (AsmTypeMetadata.Method method : metadata.methods()) {
                writeString(out, method.name()); writeString(out, method.descriptor());
                writeString(out, method.signature()); out.writeInt(method.access());
            }
            out.writeInt(metadata.constructors().size());
            for (AsmTypeMetadata.Constructor constructor : metadata.constructors()) {
                writeString(out, constructor.descriptor()); out.writeInt(constructor.access());
            }
            writeStrings(out, metadata.annotations());
            writeStrings(out, metadata.nestedTypes());
        }
        return bytes.toByteArray();
    }

    private int compare(byte[] query, int start, int length) {
        int common = Math.min(query.length, length);
        for (int i = 0; i < common; i++) {
            int left = query[i] & 0xff;
            int right = packed[start + i] & 0xff;
            if (left != right) return Integer.compare(left, right);
        }
        return Integer.compare(query.length, length);
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        if (value == null) { out.writeInt(-1); return; }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(ByteBuffer in) {
        int length = in.getInt();
        if (length < 0) return null;
        if (length > in.remaining()) throw new IllegalArgumentException("invalid packed string length");
        byte[] bytes = new byte[length];
        in.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeStrings(DataOutputStream out, List<String> values) throws IOException {
        out.writeInt(values.size());
        for (String value : values) writeString(out, value);
    }

    private static List<String> readStrings(ByteBuffer in) {
        int count = checkedCount(in);
        List<String> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) out.add(readString(in));
        return List.copyOf(out);
    }

    private static int checkedCount(ByteBuffer in) {
        int count = in.getInt();
        if (count < 0 || count > 1_000_000) throw new IllegalArgumentException("invalid packed count");
        return count;
    }
}
