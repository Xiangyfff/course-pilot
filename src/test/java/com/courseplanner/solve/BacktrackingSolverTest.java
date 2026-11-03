package com.courseplanner.solve;

import com.courseplanner.program.Course;
import com.courseplanner.program.CourseRepository;
import com.courseplanner.solve.PlanModels.Plan;
import com.courseplanner.solve.PlanModels.SolverInput;
import com.courseplanner.solve.PlanModels.Term;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 求解器的测试重点不是「排出来的课表好不好看」，
 * 而是「排出来的课表是否真的满足约束」——这也是它比 LLM 排课值得讲的地方。
 */
class BacktrackingSolverTest {

    private final Solver solver = new BacktrackingSolver();

    private static Course course(String code, double credits, String module, String... prereqs) {
        return new Course(0, code, code, credits, module, "REQUIRED", List.of(), List.of(prereqs));
    }

    private static SolverInput input(List<Course> courses, double maxPerTerm) {
        return new SolverInput(courses, Set.of(),
                List.of(new CourseRepository.ModuleRequirement(1, "MAJOR", "专业核心", 20)),
                8, maxPerTerm, 3, 1500);
    }

    private static Map<String, Integer> termOf(Plan plan) {
        Map<String, Integer> map = new HashMap<>();
        for (Term t : plan.terms()) {
            t.courses().forEach(c -> map.put(c.code(), t.term()));
        }
        return map;
    }

    @Test
    @DisplayName("先修链上的课必须排在后修课之前")
    void prereqOrderIsRespected() {
        List<Course> courses = List.of(
                course("CS101", 4, "MAJOR"),
                course("CS102", 3, "MAJOR", "CS101"),
                course("CS201", 4, "MAJOR", "CS102"),
                course("MA101", 5, "MAJOR"),
                course("MA201", 3, "MAJOR", "MA101"));

        List<Plan> plans = solver.solve(input(courses, 25));

        assertThat(plans).isNotEmpty();
        for (Plan plan : plans) {
            Map<String, Integer> term = termOf(plan);
            assertThat(term).containsKeys("CS101", "CS102", "CS201", "MA101", "MA201");
            assertThat(term.get("CS102")).isGreaterThan(term.get("CS101"));
            assertThat(term.get("CS201")).isGreaterThan(term.get("CS102"));
            assertThat(term.get("MA201")).isGreaterThan(term.get("MA101"));
        }
    }

    @Test
    @DisplayName("每学期学分不超过上限")
    void termCreditCapIsRespected() {
        List<Course> courses = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            courses.add(course("CS10" + i, 4, "MAJOR"));
        }

        List<Plan> plans = solver.solve(input(courses, 9));

        assertThat(plans).isNotEmpty();
        for (Plan plan : plans) {
            for (Term t : plan.terms()) {
                assertThat(t.credits()).isLessThanOrEqualTo(9.0);
            }
        }
    }

    @Test
    @DisplayName("学分上限太小时返回空结果，而不是给出不合法方案")
    void infeasibleReturnsEmpty() {
        List<Course> courses = List.of(
                course("CS101", 5, "MAJOR"),
                course("CS102", 5, "MAJOR"));

        assertThat(solver.solve(input(courses, 4))).isEmpty();
    }

    @Test
    @DisplayName("多套方案彼此不同（策略 0/1/2 产生不同排布）")
    void plansAreDistinct() {
        List<Course> courses = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            courses.add(course("CS20" + i, 3, "MAJOR"));
        }

        List<Plan> plans = solver.solve(input(courses, 12));

        assertThat(plans).hasSizeGreaterThan(1);
        long distinct = plans.stream().map(Plan::terms).distinct().count();
        assertThat(distinct).isEqualTo(plans.size());
    }

    @Test
    @DisplayName("已经通过的课不再出现在方案里")
    void passedCoursesAreSkipped() {
        List<Course> courses = List.of(
                course("CS101", 4, "MAJOR"),
                course("CS102", 3, "MAJOR", "CS101"));

        SolverInput in = new SolverInput(courses, Set.of("CS101"),
                List.of(new CourseRepository.ModuleRequirement(1, "MAJOR", "专业核心", 3)),
                8, 25, 1, 1500);

        List<Plan> plans = solver.solve(in);

        assertThat(plans).isNotEmpty();
        Map<String, Integer> term = termOf(plans.get(0));
        assertThat(term).doesNotContainKey("CS101");
        assertThat(term).containsKey("CS102");
    }

    @Test
    @DisplayName("已修学分已覆盖模块要求时，不再拿选修课硬凑")
    void passedCreditsFillModuleGap() {
        List<Course> courses = List.of(
                course("CS101", 20, "MAJOR"),
                new Course(0, "GE999", "自由选修", 5, "MAJOR", "ELECTIVE", List.of(), List.of()));

        SolverInput in = new SolverInput(courses, Set.of("CS101"),
                List.of(new CourseRepository.ModuleRequirement(1, "MAJOR", "专业核心", 20)),
                8, 25, 3, 1500);

        List<Plan> plans = solver.solve(in);

        assertThat(plans).isNotEmpty();
        for (Plan plan : plans) {
            assertThat(termOf(plan)).doesNotContainKey("GE999");
            assertThat(plan.notes()).noneMatch(n -> n.contains("可修课程不足"));
        }
    }
}
