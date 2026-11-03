package com.courseplanner.extract.validate;

import com.courseplanner.program.ProgramDraft;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ModuleValidatorTest {

    private final ModuleValidator validator = new ModuleValidator();

    private static ProgramDraft.CourseDraft course(String code, String module) {
        return new ProgramDraft.CourseDraft(code, code + " 课程", 3d, module, "REQUIRED",
                List.of(), List.of());
    }

    @Test
    @DisplayName("课程写模块名、模块表里是 code 时，统一映射到 code")
    void mapsModuleNameToCode() {
        ProgramDraft draft = new ProgramDraft("示例大学", "软件工程", 2023, 78d,
                List.of(course("CS101", "专业核心"), course("GE101", "通识教育")),
                List.of(new ProgramDraft.ModuleDraft("CORE", "专业核心", 28d),
                        new ProgramDraft.ModuleDraft("GE", "通识教育", 16d)));

        List<String> warnings = new ArrayList<>();
        ProgramDraft out = validator.validate(draft, warnings);

        assertThat(out.courses()).extracting(ProgramDraft.CourseDraft::module)
                .containsExactly("CORE", "GE");
        assertThat(out.modules()).hasSize(2);
        assertThat(warnings).isEmpty();
    }

    @Test
    @DisplayName("真的没有定义的模块才补建，并留下提示")
    void createsMissingModule() {
        ProgramDraft draft = new ProgramDraft(null, null, null, null,
                List.of(course("XX101", "不存在的模块")),
                List.of(new ProgramDraft.ModuleDraft("CORE", "专业核心", 28d)));

        List<String> warnings = new ArrayList<>();
        ProgramDraft out = validator.validate(draft, warnings);

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0)).contains("不存在的模块");
        assertThat(out.modules()).hasSize(2);
    }

    @Test
    @DisplayName("整行标题被当成模块名时，去掉序号和括号里的学分要求")
    void cleansHeadingLikeModuleLabel() {
        ProgramDraft draft = new ProgramDraft(null, null, null, null,
                List.of(course("GE101", "一、通识教育（最低 24 学分）")),
                List.of(new ProgramDraft.ModuleDraft("一、通识教育（最低 24 学分）",
                        "一、通识教育（最低 24 学分）", 24d)));

        List<String> warnings = new ArrayList<>();
        ProgramDraft out = validator.validate(draft, warnings);

        assertThat(out.modules()).extracting(ProgramDraft.ModuleDraft::name).containsExactly("通识教育");
        assertThat(out.courses()).extracting(ProgramDraft.CourseDraft::module).containsExactly("通识教育");
        assertThat(warnings).isEmpty();
    }
}
