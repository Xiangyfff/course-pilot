package com.courseplanner.solve;

import java.util.List;

public final class PlanModels {

    private PlanModels() {
    }

    public record Item(String code, String name, double credits, String module, String type) {
    }

    public record Term(int term, double credits, List<Item> courses) {
    }

    public record Plan(int index, int semesters, double totalCredits, int riskScore,
                       List<Term> terms, List<String> notes) {

        public Plan withIndex(int newIndex) {
            return new Plan(newIndex, semesters, totalCredits, riskScore, terms, notes);
        }
    }

    /** 求解输入：只放纯数据，求解器不碰数据库。 */
    public record SolverInput(
            List<com.courseplanner.program.Course> courses,
            java.util.Set<String> passedCodes,
            List<com.courseplanner.program.CourseRepository.ModuleRequirement> modules,
            int semesters,
            double maxCreditsPerTerm,
            int planCount,
            long timeBudgetMs
    ) {
    }
}
