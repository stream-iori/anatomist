package com.anatomist.framework;

import com.anatomist.model.BeanRefTarget;

import java.util.Map;
import java.util.Set;

/** Read-only merged view: retained committed facts plus facts staged in the current run. */
public interface ProjectFactView {
    Set<String> knownNodeIds();
    Map<String, BeanRefTarget> beanTargets();
}
