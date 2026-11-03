package com.courseplanner.solve;

import com.courseplanner.program.Course;
import com.courseplanner.program.CourseRepository;
import com.courseplanner.solve.PlanModels.Item;
import com.courseplanner.solve.PlanModels.Plan;
import com.courseplanner.solve.PlanModels.SolverInput;
import com.courseplanner.solve.PlanModels.Term;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 回溯 + 拓扑序 + 同层 MRV。
 *
 * 为什么不用 LLM 排课：LLM 会编出不存在的课程，且结果不可验证；
 * 排课本质是一个约束满足问题，交给能证明「无解」的算法更合适。
 *
 * 搜索顺序：先修深度升序（保证排到某门课时，它的先修要么已通过、要么已落位），
 * 同层再按 MRV 排（可选学期少的、学分重的先）。纯 MRV 会先排最深的「后修课」，
 * 导致先修尚未落位、被误当成「无约束」。
 *
 * 复杂度上界是指数级的，但真实培养方案里每学期的可行课程很少，
 * 加上这个排序后回溯深度通常不超过 2。
 */
@Component
public class BacktrackingSolver implements Solver {

    private static final Logger log = LoggerFactory.getLogger(BacktrackingSolver.class);

    @Override
    public List<Plan> solve(SolverInput in) {
        long deadline = System.currentTimeMillis() + in.timeBudgetMs();
        List<Plan> plans = new ArrayList<>();
        Set<String> signatures = new HashSet<>();

        // 同一条输入跑不同贪心偏好，得到互不相同的几套方案
        for (int strategy = 0; strategy < in.planCount() * 3 && plans.size() < in.planCount(); strategy++) {
            if (System.currentTimeMillis() > deadline) {
                log.warn("solver hit time budget after {} plans", plans.size());
                break;
            }
            Plan plan = attempt(in, strategy % 3, deadline);
            if (plan != null) {
                String sig = signature(plan);
                if (signatures.add(sig)) {
                    plans.add(plan.withIndex(plans.size()));
                }
            }
        }
        plans.sort(Comparator.comparingInt(Plan::semesters)
                .thenComparingInt(Plan::riskScore)
                .thenComparing(Plan::totalCredits));
        for (int i = 0; i < plans.size(); i++) {
            plans.set(i, plans.get(i).withIndex(i));
        }
        return plans;
    }

    private Plan attempt(SolverInput in, int strategy, long deadline) {
        List<Course> remaining = in.courses().stream()
                .filter(c -> !in.passedCodes().contains(c.code()))
                .toList();
        if (remaining.isEmpty()) {
            return new Plan(0, 0, 0, 0, List.of(), List.of("所有课程都已完成"));
        }

        Map<String, Integer> depth = computeDepth(remaining);
        Map<String, Integer> termOf = new HashMap<>();
        Map<Integer, Double> load = new HashMap<>();
        List<String> notes = new ArrayList<>();

        List<Course> required = remaining.stream()
                .filter(Course::isRequired)
                .sorted(prereqDepthFirst(depth))
                .toList();

        if (!place(required, 0, termOf, load, in, strategy, deadline)) {
            notes.add("在 " + in.semesters() + " 个学期内无法排完所有必修课，可能是先修冲突或学分上限过低");
            return null;
        }

        // 必修排完后，看哪些模块还差学分，用选修补
        List<Course> electives = remaining.stream()
                .filter(c -> !c.isRequired())
                .sorted(prereqDepthFirst(depth))
                .toList();
        fillModuleGaps(electives, termOf, load, in, strategy, notes);

        return buildPlan(termOf, in, notes);
    }

    /** 回溯主体。失败时回退并尝试下一个学期。 */
    private boolean place(List<Course> ordered, int idx, Map<String, Integer> termOf,
                          Map<Integer, Double> load, SolverInput in, int strategy, long deadline) {
        if (idx == ordered.size()) {
            return true;
        }
        if (System.currentTimeMillis() > deadline) {
            return false;
        }
        Course course = ordered.get(idx);
        for (int term : candidateTerms(course, in, load, strategy)) {
            if (!prereqsBefore(course, term, termOf, in.passedCodes())) {
                continue;
            }
            double used = load.getOrDefault(term, 0d);
            if (used + course.credits() > in.maxCreditsPerTerm() + 1e-6) {
                continue;
            }
            termOf.put(course.code(), term);
            load.merge(term, course.credits(), Double::sum);
            if (place(ordered, idx + 1, termOf, load, in, strategy, deadline)) {
                return true;
            }
            termOf.remove(course.code());
            load.merge(term, -course.credits(), Double::sum);
        }
        return false;
    }

    private void fillModuleGaps(List<Course> electives, Map<String, Integer> termOf,
                                Map<Integer, Double> load, SolverInput in, int strategy,
                                List<String> notes) {
        Map<String, Double> earned = moduleCredits(termOf, in);
        for (CourseRepository.ModuleRequirement module : in.modules()) {
            double gap = module.minCredits() - earned.getOrDefault(module.code(), 0d);
            if (gap <= 1e-6) {
                continue;
            }
            for (Course e : electives) {
                if (gap <= 1e-6) {
                    break;
                }
                if (!e.module().equals(module.code()) || termOf.containsKey(e.code())) {
                    continue;
                }
                Integer term = earliestFeasible(e, termOf, load, in);
                if (term == null) {
                    continue;
                }
                termOf.put(e.code(), term);
                load.merge(term, e.credits(), Double::sum);
                gap -= e.credits();
                earned.merge(module.code(), e.credits(), Double::sum);
            }
            if (gap > 1e-6) {
                notes.add(String.format("模块「%s」仍差 %.1f 学分，可修课程不足", module.name(), gap));
            }
        }
    }

    private Integer earliestFeasible(Course course, Map<String, Integer> termOf,
                                     Map<Integer, Double> load, SolverInput in) {
        for (int term = 1; term <= in.semesters(); term++) {
            if (!course.offeredIn(term) || !prereqsBefore(course, term, termOf, in.passedCodes())) {
                continue;
            }
            if (load.getOrDefault(term, 0d) + course.credits() <= in.maxCreditsPerTerm() + 1e-6) {
                return term;
            }
        }
        return null;
    }

    private List<Integer> candidateTerms(Course course, SolverInput in,
                                         Map<Integer, Double> load, int strategy) {
        List<Integer> terms = new ArrayList<>();
        for (int t = 1; t <= in.semesters(); t++) {
            if (course.offeredIn(t)) {
                terms.add(t);
            }
        }
        if (strategy == 1) {
            // 均衡型：优先塞进当前最空的学期
            terms.sort(Comparator.comparingDouble(t -> load.getOrDefault(t, 0d)));
        } else if (strategy == 2) {
            // 后置型：给低年级留出探索空间
            terms.sort(Comparator.reverseOrder());
        }
        return terms;
    }

    /**
     * 先修必须「严格早于」本课程所在的学期。
     * 三种情况分别处理：
     *  - 先修已通过：满足（已通过的课不会出现在 termOf 里）；
     *  - 先修已排进某个学期：必须早于当前学期；
     *  - 先修还没排：不满足 —— 这一点很关键，否则「被排在后修课之后」会被当成无约束。
     */
    private static boolean prereqsBefore(Course course, int term, Map<String, Integer> termOf,
                                         Set<String> passed) {
        for (String pre : course.prereqs()) {
            if (passed.contains(pre)) {
                continue;
            }
            Integer t = termOf.get(pre);
            if (t == null || t >= term) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, Double> moduleCredits(Map<String, Integer> termOf, SolverInput in) {
        Map<String, Double> earned = new HashMap<>();
        for (Course c : in.courses()) {
            // 已修课的学分也算进模块，否则「还差多少」会把上过课的模块重复算一遍
            if (in.passedCodes().contains(c.code()) || termOf.containsKey(c.code())) {
                earned.merge(c.module(), c.credits(), Double::sum);
            }
        }
        return earned;
    }

    /**
     * 先修深度升序 + 同层 MRV。
     *
     * 深度升序不是「启发式」，而是正确性要求：深度小的课一定不是深度大的课的后修，
     * 先排它才能保证后面的 prereqsBefore 检查有东西可查。
     * 同一层内再按 MRV（可选学期少、学分重）挑，减少回溯。
     */
    private static Comparator<Course> prereqDepthFirst(Map<String, Integer> depth) {
        return Comparator
                .comparingInt((Course c) -> depth.getOrDefault(c.code(), 0))
                .thenComparingInt(c -> c.offeredTerms().isEmpty() ? 8 : c.offeredTerms().size())
                .thenComparing(Comparator.comparingDouble(Course::credits).reversed());
    }

    private static Map<String, Integer> computeDepth(List<Course> courses) {
        Map<String, Course> byCode = new HashMap<>();
        courses.forEach(c -> byCode.put(c.code(), c));
        Map<String, Integer> memo = new HashMap<>();
        for (Course c : courses) {
            depthOf(c, byCode, memo, new HashSet<>());
        }
        return memo;
    }

    private static int depthOf(Course c, Map<String, Course> byCode,
                               Map<String, Integer> memo, Set<String> visiting) {
        Integer cached = memo.get(c.code());
        if (cached != null) {
            return cached;
        }
        if (!visiting.add(c.code())) {
            return 0;
        }
        int depth = 0;
        for (String pre : c.prereqs()) {
            Course p = byCode.get(pre);
            if (p != null) {
                depth = Math.max(depth, depthOf(p, byCode, memo, visiting) + 1);
            }
        }
        visiting.remove(c.code());
        memo.put(c.code(), depth);
        return depth;
    }

    private Plan buildPlan(Map<String, Integer> termOf, SolverInput in, List<String> notes) {
        Map<Integer, List<Item>> byTerm = new LinkedHashMap<>();
        Map<String, Course> byCode = new HashMap<>();
        in.courses().forEach(c -> byCode.put(c.code(), c));

        double total = 0;
        for (Map.Entry<String, Integer> e : termOf.entrySet()) {
            Course c = byCode.get(e.getKey());
            byTerm.computeIfAbsent(e.getValue(), k -> new ArrayList<>())
                    .add(new Item(c.code(), c.name(), c.credits(), c.module(), c.type()));
            total += c.credits();
        }

        List<Term> terms = new ArrayList<>();
        int maxTerm = 0;
        int risk = 0;
        for (Map.Entry<Integer, List<Item>> e : byTerm.entrySet()) {
            int term = e.getKey();
            maxTerm = Math.max(maxTerm, term);
            List<Item> items = e.getValue();
            items.sort(Comparator.comparing(Item::code));
            double credits = items.stream().mapToDouble(Item::credits).sum();
            if (credits > in.maxCreditsPerTerm() * 0.9) {
                risk += 8;
            }
            terms.add(new Term(term, credits, items));
        }
        terms.sort(Comparator.comparingInt(Term::term));

        // 先修链只剩一学期间隔的，风险最高：只要前面挂一门就得延期
        for (Map.Entry<String, Integer> e : termOf.entrySet()) {
            Course c = byCode.get(e.getKey());
            for (String pre : c.prereqs()) {
                Integer pt = termOf.get(pre);
                if (pt != null && e.getValue() - pt <= 1) {
                    risk += 6;
                }
            }
        }

        if (maxTerm > in.semesters() * 3 / 4) {
            notes.add("方案用到第 " + maxTerm + " 学期，后半程压力集中");
        }
        return new Plan(0, maxTerm, total, Math.min(risk, 100), terms, notes);
    }

    private static String signature(Plan plan) {
        StringBuilder sb = new StringBuilder();
        plan.terms().forEach(t -> t.courses().forEach(c -> sb.append(c.code()).append('@').append(t.term()).append(';')));
        return sb.toString();
    }
}
