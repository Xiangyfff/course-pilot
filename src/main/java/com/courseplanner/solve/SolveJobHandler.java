package com.courseplanner.solve;

import com.courseplanner.common.BizException;
import com.courseplanner.common.ErrorCode;
import com.courseplanner.config.AppProperties;
import com.courseplanner.job.JobContext;
import com.courseplanner.job.JobHandler;
import com.courseplanner.job.JobType;
import com.courseplanner.solve.PlanModels.Plan;
import com.courseplanner.transcript.TranscriptService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
public class SolveJobHandler implements JobHandler {

    private final SolveService solveService;
    private final Solver solver;
    private final PlanResultRepository planResults;
    private final TranscriptService transcripts;
    private final AppProperties props;

    public SolveJobHandler(SolveService solveService, Solver solver,
                           PlanResultRepository planResults, TranscriptService transcripts,
                           AppProperties props) {
        this.solveService = solveService;
        this.solver = solver;
        this.planResults = planResults;
        this.transcripts = transcripts;
        this.props = props;
    }

    @Override
    public JobType type() {
        return JobType.SOLVE;
    }

    @Override
    public void handle(JobContext ctx) {
        long programId = ctx.bizId();
        ctx.progress(10, "SOLVING", "加载课程、模块与已修成绩");
        Set<String> passed = transcripts.passedCodes(ctx.userId());

        ctx.progress(35, "SOLVING", "先修约束求解中");
        List<Plan> plans = solver.solve(
                solveService.buildInput(programId, passed, props.getSolver().getPlanCount()));

        if (plans.isEmpty()) {
            throw new BizException(ErrorCode.SOLVE_FAILED,
                    "找不到可行方案：检查是否先修冲突，或每学期学分上限太低");
        }

        ctx.progress(85, "PERSISTING", "保存 " + plans.size() + " 套方案");
        planResults.saveAll(ctx.jobId(), ctx.userId(), programId, plans);
        ctx.progress(100, "DONE", "生成 " + plans.size() + " 套方案，最快 "
                + plans.get(0).semesters() + " 学期修完");
    }
}
