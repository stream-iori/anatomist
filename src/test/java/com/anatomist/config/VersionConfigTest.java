package com.anatomist.config;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class VersionConfigTest {
    @Test void defaultsAndLargeBudgets() {
        var config=new ProjectConfig();assertFalse(config.versionsAutoGc());assertEquals(20,config.versionsKeep());
        ConfigLoader.applyToml(config,List.of("[versions.capture]","include_ignored = [\"ignored/**\"]","[versions.gc]","auto = true","max_bytes = 10000000000","keep = 3","max_age_days = 7","include_caches = true"));
        assertEquals(10000000000L,config.versionsMaxBytes());assertEquals(List.of("ignored/**"),config.captureIncludeIgnored());assertTrue(config.versionsAutoGc());
    }
    @Test void invalidPoliciesFailClearly() {
        for(String line:List.of("keep = -1","max_bytes = -1","max_age_days = -1","auto = 1","unknown = true"))
            assertThrows(ConfigException.class,()->ConfigLoader.applyToml(new ProjectConfig(),List.of("[versions.gc]",line)));
        assertThrows(ConfigException.class,()->ConfigLoader.applyToml(new ProjectConfig(),List.of("[versions.capture]","include_ignored = [\"../other/**\"]")));
    }
}
