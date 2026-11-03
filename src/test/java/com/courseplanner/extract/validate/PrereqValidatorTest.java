package com.courseplanner.extract.validate;

import com.courseplanner.program.ProgramDraft;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PrereqValidatorTest {

    private final PrereqValidator validator = new PrereqValidator();

    private static ProgramDraft.CourseDraft c(String code, String... prereqs) {
        return new ProgramDraft.CourseDraft(code, "课程" + code, 3.0, "MAJOR", "REQUIRED",
                List.of(), List.of(prereqs));
    }

    private static ProgramDraft draft(ProgramDraft.CourseDraft... courses) {
        return new ProgramDraft("示例大学", "软件工程", 2023, 100.0,
                List.of(courses), List.of());
    }

    @Test
    @DisplayName("指向不存在课程的先修关系会被删掉")
    void missingPrereqIsRemoved() {
        List<String> warnings = new ArrayList<>();
        ProgramDraft out = validator.validate(draft(c("CS101", "ZZ999")), warnings);

        assertThat(out.courses().get(0).prereqs()).isEmpty();
        assertThat(warnings).anyMatch(w -> w.contains("不存在"));
    }

    @Test
    @DisplayName("自引用会被删掉")
    void selfReferenceIsRemoved() {
        List<String> warnings = new ArrayList<>();
        ProgramDraft out = validator.validate(draft(c("CS101", "CS101")), warnings);

        assertThat(out.courses().get(0).prereqs()).isEmpty();
    }

    @Test
    @DisplayName("A→B→A 的环会被打断，且课程本身保留")
    void cycleIsBroken() {
        List<String> warnings = new ArrayList<>();
        ProgramDraft out = validator.validate(
                draft(c("CS101", "CS102"), c("CS102", "CS101")), warnings);

        assertThat(out.courses()).hasSize(2);
        assertThat(warnings).anyMatch(w -> w.contains("环"));

        // 结果里必须不存在任何环
        for (ProgramDraft.CourseDraft course : out.courses()) {
            assertThat(course.prereqs()).doesNotContain(course.code());
        }
        long totalEdges = out.courses().stream().mapToLong(x -> x.prereqs().size()).sum();
        assertThat(totalEdges).isLessThan(2);
    }

    @Test
    @DisplayName("合法的先修关系原样保留")
    void validChainIsKept() {
        List<String> warnings = new ArrayList<>();
        ProgramDraft out = validator.validate(
                draft(c("CS101"), c("CS102", "CS101"), c("CS201", "CS102")), warnings);

        assertThat(out.courses().get(2).prereqs()).containsExactly("CS102");
        assertThat(warnings).isEmpty();
    }
}
