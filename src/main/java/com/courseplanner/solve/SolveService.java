package com.courseplanner.solve;

import com.courseplanner.common.BizException;
import com.courseplanner.config.AppProperties;
import com.courseplanner.job.JobRepository;
import com.courseplanner.job.JobService;
import com.courseplanner.job.JobType;
import com.courseplanner.program.Course;
import com.courseplanner.program.CourseRepository;
import com.courseplanner.program.ProgramRepository;
import com.courseplanner.solve.PlanModels.Plan;
import com.courseplanner.transcript.TranscriptService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
public class SolveService {

    public record Dashboard(String programId, String major, double requiredCredits,
                            double earnedCredits, double remainingCredits,
                            int remainingCourses, List<ModuleProgress> modules) {
    }

    public record ModuleProgress(String code, String name, double required, double earned,
                                 double remaining, int coursesLeft) {
    }

    private final ProgramRepository programs;
    private final CourseRepository courses;
    private final TranscriptService transcripts;
    private final JobService jobs;
    private final JobRepository jobRepo;
    private final PlanResultRepository planResults;
    private final AppProperties props;

    public SolveService(ProgramRepository programs, CourseRepository courses,
                        TranscriptService transcripts, JobService jobs, JobRepository jobRepo,
                        PlanResultRepository planResults, AppProperties props) {
        this.programs = programs;
        this.courses = courses;
        this.transcripts = transcripts;
        this.jobs = jobs;
        this.jobRepo = jobRepo;
        this.planResults = planResults;
        this.props = props;
    }

    public long request(long userId, long programId) {
        ProgramRepository.ProgramRow program = programs.findById(programId)
                .orElseThrow(() -> BizException.notFound("培养方案"));
        if (program.userId() != userId) {
            throw BizException.forbidden();
        }
        if (!"READY".equals(program.status())) {
            throw BizException.badRequest("培养方案还没解析完成，不能排课");
        }
        Set<String> passed = transcripts.passedCodes(userId);
        String key = "SOLVE:" + userId + ":" + programId + ":" + program.version()
                + ":" + passed.hashCode();
        return jobs.submit(userId, JobType.SOLVE, programId, key);
    }

    public List<Plan> results(long userId, long jobId) {
        JobRepository.JobRow job = jobs.getOwned(userId, jobId);
        if (!"SOLVE".equals(job.type())) {
            throw BizException.badRequest("这不是求解任务");
        }
        return planResults.findByJob(jobId);
    }

    public List<Plan> latest(long userId, long programId) {
        ProgramRepository.ProgramRow program = programs.findById(programId)
                .orElseThrow(() -> BizException.notFound("培养方案"));
        if (program.userId() != userId) {
            throw BizException.forbidden();
        }
        return planResults.latest(userId, programId);
    }

    public Dashboard dashboard(long userId, long programId) {
        ProgramRepository.ProgramRow program = programs.findById(programId)
                .orElseThrow(() -> BizException.notFound("培养方案"));
        if (program.userId() != userId) {
            throw BizException.forbidden();
        }
        List<Course> all = courses.findCourses(programId);
        Set<String> passed = transcripts.passedCodes(userId);

        List<ModuleProgress> modules = courses.findModules(programId).stream()
                .map(m -> {
                    double earned = all.stream()
                            .filter(c -> c.module().equals(m.code()) && passed.contains(c.code()))
                            .mapToDouble(Course::credits).sum();
                    int left = (int) all.stream()
                            .filter(c -> c.module().equals(m.code()) && !passed.contains(c.code())
                                    && c.isRequired())
                            .count();
                    return new ModuleProgress(m.code(), m.name(), m.minCredits(), earned,
                            Math.max(0, m.minCredits() - earned), left);
                })
                .toList();

        double earned = all.stream().filter(c -> passed.contains(c.code()))
                .mapToDouble(Course::credits).sum();
        int remainingCourses = (int) all.stream()
                .filter(c -> c.isRequired() && !passed.contains(c.code())).count();

        return new Dashboard(String.valueOf(programId), program.major(),
                program.totalCredits(), earned, Math.max(0, program.totalCredits() - earned),
                remainingCourses, modules);
    }

    public PlanModels.SolverInput buildInput(long programId, Set<String> passed, int planCount) {
        return new PlanModels.SolverInput(
                courses.findCourses(programId),
                passed,
                courses.findModules(programId),
                props.getSolver().getSemesters(),
                props.getSolver().getMaxCreditsPerTerm(),
                planCount,
                props.getSolver().getTimeBudgetMs());
    }
}
