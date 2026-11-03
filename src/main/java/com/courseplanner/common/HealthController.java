package com.courseplanner.common;

import com.courseplanner.config.AppProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class HealthController {

    private final AppProperties props;

    public HealthController(AppProperties props) {
        this.props = props;
    }

    @GetMapping("/api/health")
    public ApiResponse<Map<String, Object>> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "UP");
        m.put("llm", props.getLlm().getProvider());
        m.put("model", props.getLlm().getModel());
        m.put("mq", props.getMq().getMode());
        m.put("cache", props.getCache().getMode());
        return ApiResponse.ok(m);
    }
}
