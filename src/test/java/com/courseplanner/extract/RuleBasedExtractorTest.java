package com.courseplanner.extract;

import com.courseplanner.program.ProgramDraft;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedExtractorTest {

    private final RuleBasedExtractor extractor = new RuleBasedExtractor();

    private ProgramDraft draft(String text) {
        return extractor.extract(text).draft();
    }

    @Test
    @DisplayName("标题行同时含学校和专业时，按「专业」切开")
    void splitsSchoolAndMajorFromTitleLine() {
        ProgramDraft d = draft("""
                示例大学 软件工程专业 2023 级培养方案

                二、学分要求
                总学分 78

                专业核心
                CS101 程序设计基础 4
                CS102 面向对象程序设计 3 先修:CS101
                """);

        assertThat(d.school()).isEqualTo("示例大学");
        assertThat(d.major()).isEqualTo("软件工程");
        assertThat(d.courses()).hasSize(2);
        assertThat(d.totalCredits()).isEqualTo(78.0);
    }

    @Test
    @DisplayName("课程行不会被误当成校名 / 专业名")
    void courseLineIsNotMistakenForTitle() {
        ProgramDraft d = draft("""
                CS101 程序设计基础 4
                CS102 面向对象程序设计 3
                """);

        assertThat(d.school()).isNull();
        assertThat(d.major()).isNull();
        assertThat(d.courses()).hasSize(2);
    }

    @Test
    @DisplayName("学校和专业分两行时也能识别")
    void handlesSeparateSchoolAndMajorLines() {
        ProgramDraft d = draft("""
                示例大学
                软件工程专业
                CS101 程序设计基础 4
                """);

        assertThat(d.school()).isEqualTo("示例大学");
        assertThat(d.major()).isEqualTo("软件工程");
        assertThat(d.courses()).hasSize(1);
    }
}
