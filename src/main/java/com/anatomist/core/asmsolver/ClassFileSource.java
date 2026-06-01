package com.anatomist.core.asmsolver;

import java.util.Optional;
import java.util.Set;

/** Abstracts the source of .class bytes for the AsmTypeSolver, so unit tests
 *  can inject in-memory mock classes via ASM ClassWriter while production
 *  reads from on-disk jars. */
public interface ClassFileSource extends AutoCloseable {

    /** Return the raw .class bytes for {@code fqn} if present, else empty. */
    Optional<byte[]> find(String fqn);

    /** Enumerate every FQN this source can resolve (used for diagnostics
     *  and for the AsmTypeSolver to know whether to defer to a sibling). */
    Set<String> knownClasses();

    @Override
    default void close() {}
}
