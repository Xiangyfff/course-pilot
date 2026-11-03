package com.courseplanner.solve;

import java.util.List;

public interface Solver {

    /** 返回 0..N 套可行方案，按「毕业快、负担平稳、风险低」排序。 */
    List<PlanModels.Plan> solve(PlanModels.SolverInput input);
}
