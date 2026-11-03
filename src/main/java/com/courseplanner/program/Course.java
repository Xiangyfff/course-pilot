package com.courseplanner.program;

import java.util.List;
import java.util.Set;

public record Course(
        long id,
        String code,
        String name,
        double credits,
        String module,
        String type,
        List<Integer> offeredTerms,
        List<String> prereqs
) {
    public boolean isRequired() {
        return "REQUIRED".equals(type);
    }

    public boolean offeredIn(int term) {
        return offeredTerms.isEmpty() || offeredTerms.contains(term);
    }

    public boolean allPrereqsIn(Set<String> assigned) {
        return prereqs.stream().allMatch(assigned::contains);
    }
}
