package com.courseplanner.solve;

import com.courseplanner.auth.Authenticated;
import com.courseplanner.common.ApiResponse;
import com.courseplanner.solve.PlanModels.Plan;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class PlanController {

    public record SolveRequest(@NotNull Long programId) {
    }

    public record SolveAccepted(long jobId) {
    }

    public record PlansView(long jobId, List<Plan> plans) {
    }

    private final SolveService solveService;

    public PlanController(SolveService solveService) {
        this.solveService = solveService;
    }

    @PostMapping("/plans")
    public ApiResponse<SolveAccepted> solve(@Authenticated long userId,
                                            @Valid @RequestBody SolveRequest req) {
        return ApiResponse.ok(new SolveAccepted(solveService.request(userId, req.programId())));
    }

    @GetMapping("/plans/{jobId}")
    public ApiResponse<PlansView> results(@Authenticated long userId, @PathVariable long jobId) {
        return ApiResponse.ok(new PlansView(jobId, solveService.results(userId, jobId)));
    }

    /** 页面刷新后恢复上一次的求解结果，避免每次进来都要重新算。 */
    @GetMapping("/plans/latest")
    public ApiResponse<List<Plan>> latest(@Authenticated long userId,
                                          @RequestParam long programId) {
        return ApiResponse.ok(solveService.latest(userId, programId));
    }

    @GetMapping("/dashboard/{programId}")
    public ApiResponse<SolveService.Dashboard> dashboard(@Authenticated long userId,
                                                         @PathVariable long programId) {
        return ApiResponse.ok(solveService.dashboard(userId, programId));
    }
}
