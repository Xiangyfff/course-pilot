package com.courseplanner.program;

import java.util.List;

/**
 * LLM / 规则解析器的输出契约。它只描述「文档里写了什么」，
 * 不保证自洽——自洽由 extract 包里的校验链负责。
 */
public record ProgramDraft(
        String school,
        String major,
        Integer gradeYear,
        Double totalCredits,
        List<CourseDraft> courses,
        List<ModuleDraft> modules
) {

    public record CourseDraft(
            String code,
            String name,
            Double credits,
            String module,
            String type,
            List<Integer> terms,
            List<String> prereqs
    ) {
        public CourseDraft normalized() {
            return new CourseDraft(
                    code == null ? "" : code.trim().toUpperCase(),
                    name == null ? "" : name.trim(),
                    credits,
                    module == null || module.isBlank() ? "GENERAL" : module.trim().toUpperCase(),
                    type == null ? "REQUIRED" : type.trim().toUpperCase(),
                    terms == null ? List.of() : terms,
                    prereqs == null ? List.of() : prereqs);
        }
    }

    public record ModuleDraft(String code, String name, Double minCredits) {
        public ModuleDraft normalized() {
            return new ModuleDraft(
                    code == null ? "GENERAL" : code.trim().toUpperCase(),
                    name == null || name.isBlank() ? code : name.trim(),
                    minCredits == null ? 0d : minCredits);
        }
    }
}
