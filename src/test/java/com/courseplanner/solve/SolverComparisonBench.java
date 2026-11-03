package com.courseplanner.solve;

import com.courseplanner.demo.DemoProgram;
import com.courseplanner.program.Course;
import com.courseplanner.program.CourseRepository;
import com.courseplanner.program.ProgramDraft;
import com.courseplanner.solve.PlanModels.Plan;
import com.courseplanner.solve.PlanModels.SolverInput;
import com.courseplanner.transcript.TranscriptRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 求解器对照实验：朴素贪心 vs 回溯 + 拓扑序 + MRV。
 *
 * 不是断言测试，跑它是为了拿 README 里那张对照表的数字：
 *
 *   ./mvnw -B test -Dtest=SolverComparisonBench
 *
 * 输入用内置演示方案（39 门课、已修 18 门），只保留必修课——
 * 两种算法解同一个子问题，比「能不能排完」和「要多久」才有意义。
 */
class SolverComparisonBench {

    private static final int WARMUP = 50;
    private static final int ROUNDS = 300;

    @Test
    void compare() {
        System.out.printf("%-14s %-22s %-10s %-6s %-7s %-8s %-8s%n",
                "算法", "场景", "排完次数", "学期", "学分", "p50", "p95");

        scenario("宽松：25 学分上限、开课不限", demoInput(25, false));
        scenario("紧张：13 学分上限、开课不限", demoInput(13, false));
        scenario("很紧：13 学分上限 + 隔学期开课", demoInput(13, true));

        // 演示数据两种算法都能排完，差别只在耗时；要看清「回退」到底值不值，
        // 需要一个必须换顺序才解得出的最小反例。
        SolverInput micro = microCase();
        boolean greedyOk = !new NaiveGreedySolver().solve(micro).isEmpty();
        boolean backtrackOk = !new BacktrackingSolver().solve(micro).isEmpty();
        System.out.println("最小反例（4 门课 / 每学期上限 8 学分 / 两门只开一个学期）：");
        System.out.println("  贪心(无回溯)   排完：" + greedyOk);
        System.out.println("  回溯+拓扑+MRV  排完：" + backtrackOk);
        org.junit.jupiter.api.Assertions.assertTrue(backtrackOk, "回溯应该能解出最小反例");
        org.junit.jupiter.api.Assertions.assertFalse(greedyOk, "贪心在最小反例上应该失败");
    }

    /**
     * 只有 4 门课的反例：C 只在第 1 学期开、D 只在第 2 学期开，每学期最多 8 学分（两门课）。
     * 贪心在第 1 学期先塞了 A、B，C 就再也放不进去，且不会回退；回溯会把 C 排在最前。
     */
    private SolverInput microCase() {
        List<Course> courses = List.of(
                new Course(1, "A101", "A", 4, "MAJOR", "REQUIRED", List.of(1, 2), List.of()),
                new Course(2, "B101", "B", 4, "MAJOR", "REQUIRED", List.of(1, 2), List.of()),
                new Course(3, "C101", "C", 4, "MAJOR", "REQUIRED", List.of(1), List.of()),
                new Course(4, "D101", "D", 4, "MAJOR", "REQUIRED", List.of(2), List.of()));
        return new SolverInput(courses, Set.of(), List.of(), 2, 8d, 1, 1500);
    }

    private void scenario(String name, SolverInput input) {
        Result greedy = measure(new NaiveGreedySolver(), input);
        Result backtracking = measure(new BacktrackingSolver(), input);
        System.out.printf("%-14s %-22s %-10s %-6s %-7s %-8s %-8s%n",
                "贪心(无回溯)", name, greedy.success + "/" + ROUNDS, greedy.semesters,
                greedy.credits, greedy.p50 + "ms", greedy.p95 + "ms");
        System.out.printf("%-14s %-22s %-10s %-6s %-7s %-8s %-8s%n",
                "回溯+拓扑+MRV", name, backtracking.success + "/" + ROUNDS, backtracking.semesters,
                backtracking.credits, backtracking.p50 + "ms", backtracking.p95 + "ms");
        System.out.println();
    }


    private record Result(int success, int semesters, double credits, double p50, double p95) {
    }

    private Result measure(Solver solver, SolverInput input) {
        for (int i = 0; i < WARMUP; i++) {
            solver.solve(input);
        }
        long[] ns = new long[ROUNDS];
        int success = 0;
        int semesters = 0;
        double credits = 0;
        for (int i = 0; i < ROUNDS; i++) {
            long t0 = System.nanoTime();
            List<Plan> plans = solver.solve(input);
            ns[i] = System.nanoTime() - t0;
            if (!plans.isEmpty()) {
                success++;
                Plan first = plans.get(0);
                semesters += first.semesters();
                credits += first.totalCredits();
            }
        }
        Arrays.sort(ns);
        int n = Math.max(1, success);
        return new Result(success, semesters / n, Math.round(credits / n * 10) / 10d,
                Math.round(ns[(int) (ROUNDS * 0.50)] / 1e4) / 100d,
                Math.round(ns[(int) (ROUNDS * 0.95)] / 1e4) / 100d);
    }

    private SolverInput demoInput(double maxCredits, boolean alternatingTerms) {
        ProgramDraft draft = DemoProgram.draft();

        Set<String> passed = DemoProgram.passedCourses().stream()
                .map(TranscriptRepository.PassedCourse::code)
                .collect(Collectors.toSet());

        List<Course> courses = new ArrayList<>();
        long id = 1;
        for (ProgramDraft.CourseDraft d : draft.courses()) {
            if (!"REQUIRED".equals(d.type())) {
                continue;
            }
            // 隔学期开课：没有先修的课只在单数学期开，有先修的只在双数学期开。
            // 这是一条很常见的真实约束（专业基础课秋季开、专业课春季开），
            // 会让「先到先得」的贪心把自己堵死。
            List<Integer> terms = d.terms();
            if (alternatingTerms) {
                terms = d.prereqs().isEmpty() ? List.of(1, 3, 5, 7) : List.of(2, 4, 6, 8);
            }
            courses.add(new Course(id++, d.code(), d.name(), d.credits() == null ? 0 : d.credits(),
                    d.module(), d.type(), terms, d.prereqs()));
        }
        List<CourseRepository.ModuleRequirement> modules = draft.modules().stream()
                .map(m -> new CourseRepository.ModuleRequirement(0, m.code(), m.name(), m.minCredits()))
                .toList();
        return new SolverInput(courses, passed, modules, 8, maxCredits, 3, 1500);
    }
}
