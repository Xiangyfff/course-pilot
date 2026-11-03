package com.courseplanner.program;

import com.courseplanner.auth.Authenticated;
import com.courseplanner.common.ApiResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/programs")
public class ProgramController {

    private final ProgramService programService;

    public ProgramController(ProgramService programService) {
        this.programService = programService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ProgramService.CreateResult> upload(@Authenticated long userId,
                                                          @RequestPart("file") MultipartFile file) {
        return ApiResponse.ok(programService.create(userId, file));
    }

    @GetMapping
    public ApiResponse<List<ProgramService.ProgramSummary>> list(@Authenticated long userId) {
        return ApiResponse.ok(programService.list(userId));
    }

    @GetMapping("/{id}")
    public ApiResponse<ProgramService.ProgramDetail> detail(@Authenticated long userId,
                                                            @PathVariable long id) {
        return ApiResponse.ok(programService.detail(userId, id));
    }
}
