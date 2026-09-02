package com.anatomist.framework;

import com.anatomist.model.BeanRefTarget;

import java.util.Map;
import java.util.Set;

public record DefaultProjectFactView(Set<String> knownNodeIds,
                                     Map<String, BeanRefTarget> beanTargets)
        implements ProjectFactView {
    public DefaultProjectFactView {
        knownNodeIds = knownNodeIds == null ? Set.of() : Set.copyOf(knownNodeIds);
        beanTargets = beanTargets == null ? Map.of() : Map.copyOf(beanTargets);
    }
}
