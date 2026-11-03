package com.courseplanner.solve;

import com.courseplanner.program.Course;
import com.courseplanner.solve.PlanModels.Plan;
import com.courseplanner.solve.PlanModels.SolverInput;
import com.courseplanner.solve.PlanModels.Term;
import com.courseplanner.solve.PlanModels.Item;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 朴素贪心——**只用于对照实验**，不参与线上求解。
 *
 * 做法：按学期从早到晚扫，每学期把「先修都已满足 + 本学期开课 + 学分没超」的课
 * 按学分从大到小能塞多少塞多少，塞不下的留到下学期。不回退、不做 MRV、不做剪枝。
 *
 * 保留它的价值在于能回答「你那套回溯到底值不值」：
 * 同样的输入，贪心往往在最后几个学期堆满必修、或者干脆排不下；
 * 回溯能退回去换一个更早的学期。数字见 README「求解器对照」。
 */
public class NaiveGreedySolver implements Solver {

    @Override
    public List<Plan> solve(SolverInput in) {
        List<Plan> plans = new ArrayList<>();
        Plan plan = attempt(in);
        if (plan != null) {
            plans.add(plan.withIndex(0));
        }
        return plans;
    }

    private Plan attempt(SolverInput in) {
        List<Course> remaining = in.courses().stream()
                .filter(c -> !in.passedCodes().contains(c.code()))
                .toList();

        Map<String, Integer> termOf = new HashMap<>();
        Map<Integer, Double> load = new HashMap<>();
        Set<String> placed = new HashSet<>();
        List<String> notes = new ArrayList<>();

        List<Course> pending = new ArrayList<>(remaining);
        for (int term = 1; term <= in.semesters() && !pending.isEmpty(); term++) {
            List<Course> thisTerm = new ArrayList<>();
            for (Course c : pending) {
                if (!c.offeredIn(term)) {
                    continue;
                }
                if (!c.allPrereqsIn(placed) && !allPassed(c, in.passedCodes())) {
                    continue;
                }
                if (load.getOrDefault(term, 0d) + c.credits() > in.maxCreditsPerTerm() + 1e-6) {
                    continue;
                }
                thisTerm.add(c);
            }
            // 学分重的先塞，尽量少占学期
            thisTerm.sort(Comparator.comparingDouble(Course::credits).reversed()
                    .thenComparing(Course::code));
            List<Course> accepted = new ArrayList<>();
            for (Course c : thisTerm) {
                // 收集阶段只看单门课，真正下手前还要按当前已占学分再判一次
                if (load.getOrDefault(term, 0d) + c.credits() > in.maxCreditsPerTerm() + 1e-6) {
                    continue;
                }
                termOf.put(c.code(), term);
                load.merge(term, c.credits(), Double::sum);
                placed.add(c.code());
                accepted.add(c);
            }
            pending.removeAll(accepted);
        }

        if (!pending.isEmpty()) {
            notes.add("贪心在第 " + in.semesters() + " 学期后仍有 " + pending.size() + " 门课没排下");
            return null;
        }
        return buildPlan(termOf, in, notes);
    }

    private boolean allPassed(Course c, Set<String> passed) {
        return passed.containsAll(c.prereqs());
    }

    private Plan buildPlan(Map<String, Integer> termOf, SolverInput in, List<String> notes) {
        Map<Integer, List<Item>> byTerm = new HashMap<>();
        Map<String, Course> byCode = new HashMap<>();
        for (Course c : in.courses()) {
            byCode.put(c.code(), c);
        }
        for (Map.Entry<String, Integer> e : termOf.entrySet()) {
            Course c = byCode.get(e.getKey());
            byTerm.computeIfAbsent(e.getValue(), k -> new ArrayList<>())
                    .add(new Item(c.code(), c.name(), c.credits(), c.module(), c.type()));
        }
        List<Term> terms = new ArrayList<>();
        double total = 0;
        for (int t = 1; t <= in.semesters(); t++) {
            List<Item> items = byTerm.getOrDefault(t, List.of());
            if (items.isEmpty()) {
                continue;
            }
            items.sort(Comparator.comparing(Item::code));
            double credits = items.stream().mapToDouble(Item::credits).sum();
            total += credits;
            terms.add(new Term(t, credits, items));
        }
        int semesters = terms.size();
        return new Plan(0, semesters, total, 0, terms, notes);
    }
}
