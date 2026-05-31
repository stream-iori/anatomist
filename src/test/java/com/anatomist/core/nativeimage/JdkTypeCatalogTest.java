package com.anatomist.core.nativeimage;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Pin the on-disk binary catalog format. Anatomist ships these for several
 *  JDK versions; the reader must round-trip what the builder writes. */
class JdkTypeCatalogTest {

    @Test
    void roundTrip_singleType() throws Exception {
        JdkType t = new JdkType();
        t.fqn = "java.lang.String";
        t.superFqn = "java.lang.Object";
        t.interfaceFqns = List.of("java.io.Serializable", "java.lang.Comparable", "java.lang.CharSequence");
        t.flags = JdkType.FLAG_CLASS | JdkType.FLAG_FINAL;
        t.fields = List.of(new JdkType.FieldEntry("CASE_INSENSITIVE_ORDER", "Ljava/util/Comparator;", JdkType.FLAG_STATIC | JdkType.FLAG_FINAL));
        t.methods = List.of(
                new JdkType.MethodEntry("length", "()I", JdkType.FLAG_PUBLIC),
                new JdkType.MethodEntry("substring", "(I)Ljava/lang/String;", JdkType.FLAG_PUBLIC),
                new JdkType.MethodEntry("substring", "(II)Ljava/lang/String;", JdkType.FLAG_PUBLIC)
        );

        JdkTypeCatalog cat = new JdkTypeCatalog(21);
        cat.add(t);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        cat.writeTo(out);
        JdkTypeCatalog reloaded = JdkTypeCatalog.readFrom(new ByteArrayInputStream(out.toByteArray()));

        assertEquals(21, reloaded.jdkRelease());
        assertEquals(1, reloaded.size());
        JdkType got = reloaded.find("java.lang.String");
        assertNotNull(got);
        assertEquals("java.lang.String", got.fqn);
        assertEquals("java.lang.Object", got.superFqn);
        assertEquals(3, got.interfaceFqns.size());
        assertTrue(got.interfaceFqns.contains("java.lang.Comparable"));
        assertEquals(1, got.fields.size());
        assertEquals(3, got.methods.size());
        // distinguishes substring overloads via descriptor
        long substringCount = got.methods.stream().filter(m -> m.name.equals("substring")).count();
        assertEquals(2, substringCount);
    }

    @Test
    void roundTrip_manyTypes_sharesStringPool() throws Exception {
        JdkTypeCatalog cat = new JdkTypeCatalog(21);
        for (int i = 0; i < 100; i++) {
            JdkType t = new JdkType();
            t.fqn = "java.lang.X" + i;
            t.superFqn = "java.lang.Object"; // shared
            t.interfaceFqns = List.of();
            t.flags = JdkType.FLAG_CLASS;
            t.fields = List.of();
            t.methods = List.of(new JdkType.MethodEntry("toString", "()Ljava/lang/String;", JdkType.FLAG_PUBLIC));
            cat.add(t);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        cat.writeTo(out);
        byte[] bytes = out.toByteArray();
        // String pool sharing keeps "java.lang.Object" / "toString" /
        // "()Ljava/lang/String;" interned once. Without pooling each repetition
        // would inline ~44 extra bytes × 100 = ~4400 extra bytes.
        // With pool: ~4000; without: ~8400. Pin < 5000 as the cliff.
        assertTrue(bytes.length < 5000,
                "expected pooled encoding < 5000 bytes; got " + bytes.length);

        JdkTypeCatalog re = JdkTypeCatalog.readFrom(new ByteArrayInputStream(bytes));
        assertEquals(100, re.size());
        assertEquals("java.lang.Object", re.find("java.lang.X42").superFqn);
    }

    @Test
    void readFrom_rejectsBadMagic() {
        ByteArrayInputStream in = new ByteArrayInputStream("WRONG".getBytes());
        assertThrows(IllegalArgumentException.class,
                () -> JdkTypeCatalog.readFrom(in));
    }

    @Test
    void writeAndReadBack_isDeterministic() throws Exception {
        JdkTypeCatalog cat = new JdkTypeCatalog(21);
        JdkType t = new JdkType();
        t.fqn = "java.util.List";
        t.superFqn = null; // interface
        t.interfaceFqns = List.of("java.util.Collection");
        t.flags = JdkType.FLAG_INTERFACE | JdkType.FLAG_PUBLIC;
        t.fields = List.of();
        t.methods = List.of(new JdkType.MethodEntry("of", "()Ljava/util/List;", JdkType.FLAG_STATIC | JdkType.FLAG_PUBLIC));
        cat.add(t);

        ByteArrayOutputStream out1 = new ByteArrayOutputStream();
        cat.writeTo(out1);
        ByteArrayOutputStream out2 = new ByteArrayOutputStream();
        cat.writeTo(out2);
        assertArrayEquals(out1.toByteArray(), out2.toByteArray());
    }

    @Test
    void find_returnsNullForUnknown() throws Exception {
        JdkTypeCatalog cat = new JdkTypeCatalog(21);
        JdkType t = new JdkType();
        t.fqn = "java.lang.Object";
        t.interfaceFqns = List.of();
        t.fields = List.of();
        t.methods = List.of();
        cat.add(t);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        cat.writeTo(out);
        JdkTypeCatalog re = JdkTypeCatalog.readFrom(new ByteArrayInputStream(out.toByteArray()));
        assertNull(re.find("does.not.Exist"));
    }
}
