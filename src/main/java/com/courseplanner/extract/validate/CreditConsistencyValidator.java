package com.courseplanner.extract.validate;

import com.courseplanner.program.ProgramDraft;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@Order(4)
public class CreditConsistencyValidator implements DraftValidator {

    @Override
    public String name() {
        return "学分守恒校验";
    }

    @Override
    public ProgramDraft validate(ProgramDraft draft, List<String> warnings) {
        Map<String, Double> available = new HashMap<>();
        double sum = 0;
        for (ProgramDraft.CourseDraft c : draft.courses()) {
            available.merge(c.module(), c.credits(), Double::sum);
            sum += c.credits();
        }

        double requiredTotal = draft.modules().stream()
                .mapToDouble(m -> m.minCredits() == null ? 0 : m.minCredits())
                .sum();
        double total = draft.totalCredits() == null || draft.totalCredits() <= 0
                ? requiredTotal : draft.totalCredits();

        if (requiredTotal > total + 0.01) {
            warnings.add(String.format("模块最低学分合计 %.1f 大于毕业总学分 %.1f，方案本身不自洽", requiredTotal, total));
        }
        for (ProgramDraft.ModuleDraft m : draft.modules()) {
            double have = available.getOrDefault(m.code(), 0d);
            if (m.minCredits() != null && have < m.minCredits() - 0.01) {
                warnings.add(String.format("模块「%s」列出课程只有 %.1f 学分，低于要求的 %.1f，求解时会用选修补足",
                        m.name(), have, m.minCredits()));
            }
        }
        return new ProgramDraft(draft.school(), draft.major(), draft.gradeYear(), total,
                draft.courses(), draft.modules());
    }
}
