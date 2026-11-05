package com.threadscope.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
    "threadscope.analysis.health.thread-count-warning=2500",
    "threadscope.analysis.business-packages=com.acme,com.example"
})
class AnalysisPropertiesBindingTest {

    @Autowired
    private AnalysisProperties properties;

    @Test
    void bindsNestedThresholdsAndBusinessPackages() {
        assertEquals(2500, properties.health().threadCountWarning());
        assertEquals(5, properties.health().blockStormThreshold(), "未配置的阈值使用默认值");
        assertEquals(java.util.List.of("com.acme", "com.example"), properties.businessPackages());
    }
}
