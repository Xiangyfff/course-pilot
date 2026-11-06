package com.courseplanner.qa.memory;

import com.courseplanner.auth.Authenticated;
import com.courseplanner.common.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 让用户能看见并删掉 Agent 记住的东西——记忆不可见就没法谈「可解释」。 */
@RestController
@RequestMapping("/api/memory")
public class MemoryController {

    private final MemoryService memoryService;

    public MemoryController(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(@Authenticated long userId) {
        return ApiResponse.ok(memoryService.list(userId));
    }

    @DeleteMapping("/{key}")
    public ApiResponse<Map<String, Object>> forget(@Authenticated long userId, @PathVariable String key) {
        return ApiResponse.ok(Map.of("deleted", memoryService.forget(userId, key)));
    }
}
