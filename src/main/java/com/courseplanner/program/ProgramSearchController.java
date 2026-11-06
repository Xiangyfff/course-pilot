package com.courseplanner.program;

import com.courseplanner.auth.Authenticated;
import com.courseplanner.common.ApiResponse;
import com.courseplanner.common.BizException;
import com.courseplanner.common.TextSearch;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 培养方案原文检索。单独一个 controller，是因为它同时服务于前端和 MCP 工具，
 * 不想让 ProgramService 多背一个「检索」职责。
 */
@RestController
@RequestMapping("/api/programs/{id}")
public class ProgramSearchController {

    private static final int LIMIT = 5;

    private final ProgramRepository programs;

    public ProgramSearchController(ProgramRepository programs) {
        this.programs = programs;
    }

    @GetMapping("/search")
    public ApiResponse<List<Map<String, String>>> search(@Authenticated long userId,
                                                         @PathVariable long id,
                                                         @RequestParam String keyword) {
        ProgramRepository.ProgramRow program = programs.findById(id)
                .orElseThrow(() -> BizException.notFound("培养方案"));
        if (program.userId() != userId) {
            throw BizException.forbidden();
        }
        String source = program.sourceFile() == null ? "培养方案" : program.sourceFile();
        List<Map<String, String>> hits = TextSearch.search(program.sourceText(), keyword, LIMIT).stream()
                .map(snippet -> Map.of("source", source, "snippet", snippet))
                .toList();
        return ApiResponse.ok(hits);
    }
}
