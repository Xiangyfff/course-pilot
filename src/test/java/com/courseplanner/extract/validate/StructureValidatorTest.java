package com.courseplanner.extract.validate;

import com.courseplanner.common.BizException;
import com.courseplanner.program.ProgramDraft;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StructureValidatorTest {

    private final StructureValidator validator = new StructureValidator();

    private static ProgramDraft.CourseDraft c(String code, String name, double credits) {
        return new ProgramDraft.CourseDraft(code, name, credits, "MAJOR", "REQUIRED",
                List.of(), List.of());
    }

    private static ProgramDraft draft(List<ProgramDraft.CourseDraft> courses) {
        return new ProgramDraft("示例大学", "软件工程", 2023, 100.0, courses, List.of());
    }

    @Test
    @DisplayName("重复课程代码只保留第一条")
    void duplicateCodeIsDropped() {
        List<String> warnings = new ArrayList<>();
        ProgramDraft out = validator.validate(
                draft(List.of(c("CS101", "程序设计", 4), c("CS101", "重复的", 3))), warnings);

        assertThat(out.courses()).hasSize(1);
        assertThat(out.courses().get(0).name()).isEqualTo("程序设计");
        assertThat(warnings).anyMatch(w -> w.contains("重复"));
    }

    @Test
    @DisplayName("缺字段或学分异常的课程被丢弃")
    void invalidCoursesAreDropped() {
        List<String> warnings = new ArrayList<>();
        ProgramDraft out = validator.validate(draft(List.of(
                c("CS101", "程序设计", 4),
                c("", "没代码", 3),
                c("CS102", "", 3),
                c("CS103", "零学分", 0),
                c("CS104", "学分离谱", 99))), warnings);

        assertThat(out.courses()).hasSize(1);
        assertThat(warnings).anyMatch(w -> w.contains("丢弃"));
    }

    @Test
    @DisplayName("一门课都识别不出来时直接报错，而不是入库空方案")
    void emptyResultFails() {
        assertThatThrownBy(() -> validator.validate(draft(List.of()), new ArrayList<>()))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("输出的课程代码统一大写，模块缺失时落到 GENERAL")
    void normalizesCodesAndModules() {
        ProgramDraft draft = new ProgramDraft(null, null, null, null,
                List.of(new ProgramDraft.CourseDraft("cs101", "程序设计", 4.0, null, null,
                        null, null)), List.of());

        ProgramDraft out = validator.validate(draft, new ArrayList<>());

        assertThat(out.courses().get(0).code()).isEqualTo("CS101");
        assertThat(out.courses().get(0).module()).isEqualTo("GENERAL");
        assertThat(out.courses().get(0).type()).isEqualTo("REQUIRED");
    }
}
