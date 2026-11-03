package com.courseplanner.demo;

import com.courseplanner.program.ProgramDraft;
import com.courseplanner.transcript.TranscriptRepository;

import java.util.List;

/**
 * 内置的示例培养方案（软件工程，2023 版）。
 * 学分按「精简版」设置，方便一个人在两学期内演示完整流程。
 */
public final class DemoProgram {

    private DemoProgram() {
    }

    private record C(String code, String name, double credits, String module, String type,
                     List<Integer> terms, List<String> prereqs) {
    }

    private static final List<C> COURSES = List.of(
            // 通识教育
            new C("EN101", "大学英语(一)", 3, "GENERAL", "REQUIRED", List.of(1, 2), List.of()),
            new C("EN102", "大学英语(二)", 3, "GENERAL", "REQUIRED", List.of(2, 3), List.of("EN101")),
            new C("PL101", "思想道德与法治", 3, "GENERAL", "REQUIRED", List.of(1, 2), List.of()),
            new C("PL102", "中国近现代史纲要", 3, "GENERAL", "REQUIRED", List.of(2, 3), List.of()),
            new C("PL103", "马克思主义基本原理", 3, "GENERAL", "REQUIRED", List.of(3, 4), List.of()),
            new C("PL104", "毛泽东思想和中国特色社会主义理论体系概论", 3, "GENERAL", "REQUIRED", List.of(4, 5), List.of()),
            new C("PE101", "体育(一)", 1, "GENERAL", "REQUIRED", List.of(1), List.of()),
            new C("PE102", "体育(二)", 1, "GENERAL", "REQUIRED", List.of(2), List.of()),
            new C("PE103", "体育(三)", 1, "GENERAL", "REQUIRED", List.of(3), List.of()),
            new C("PE104", "体育(四)", 1, "GENERAL", "REQUIRED", List.of(4), List.of()),
            new C("GE101", "大学语文", 2, "GENERAL", "REQUIRED", List.of(1, 2), List.of()),
            new C("GE102", "创新创业基础", 2, "GENERAL", "REQUIRED", List.of(5, 6), List.of()),
            new C("GE201", "艺术鉴赏", 2, "GENERAL", "ELECTIVE", List.of(3, 4), List.of()),
            new C("GE202", "心理学导论", 2, "GENERAL", "ELECTIVE", List.of(5, 6), List.of()),

            // 数学与自然科学
            new C("MA101", "高等数学A(上)", 5, "MATH", "REQUIRED", List.of(1, 2), List.of()),
            new C("MA102", "高等数学A(下)", 5, "MATH", "REQUIRED", List.of(2, 3), List.of("MA101")),
            new C("MA201", "线性代数", 3, "MATH", "REQUIRED", List.of(1, 2), List.of()),
            new C("MA202", "概率论与数理统计", 3, "MATH", "REQUIRED", List.of(3, 4), List.of("MA102")),
            new C("MA203", "离散数学", 3, "MATH", "REQUIRED", List.of(2, 3), List.of()),
            new C("PH101", "大学物理(上)", 3, "MATH", "REQUIRED", List.of(2, 3), List.of()),
            new C("PH102", "大学物理(下)", 3, "MATH", "REQUIRED", List.of(3, 4), List.of("PH101")),

            // 专业核心
            new C("CS101", "程序设计基础", 4, "MAJOR", "REQUIRED", List.of(1, 2), List.of()),
            new C("CS102", "面向对象程序设计", 3, "MAJOR", "REQUIRED", List.of(2, 3), List.of("CS101")),
            new C("CS201", "数据结构", 4, "MAJOR", "REQUIRED", List.of(3, 4), List.of("CS102")),
            new C("CS202", "计算机组成原理", 4, "MAJOR", "REQUIRED", List.of(3, 4), List.of("CS101")),
            new C("CS203", "操作系统", 4, "MAJOR", "REQUIRED", List.of(4, 5), List.of("CS201", "CS202")),
            new C("CS204", "计算机网络", 3, "MAJOR", "REQUIRED", List.of(4, 5), List.of("CS102")),
            new C("CS205", "数据库系统", 3, "MAJOR", "REQUIRED", List.of(4, 5), List.of("CS201")),
            new C("CS301", "软件工程", 3, "MAJOR", "REQUIRED", List.of(5, 6), List.of("CS201")),
            new C("CS302", "编译原理", 3, "MAJOR", "REQUIRED", List.of(5, 6), List.of("CS201")),
            new C("CS303", "分布式系统", 3, "MAJOR", "ELECTIVE", List.of(6, 7), List.of("CS203")),
            new C("CS304", "人工智能导论", 3, "MAJOR", "ELECTIVE", List.of(5, 6), List.of("MA202")),
            new C("CS305", "机器学习", 3, "MAJOR", "ELECTIVE", List.of(6, 7), List.of("CS304")),
            new C("CS306", "云计算技术", 2, "MAJOR", "ELECTIVE", List.of(6, 7), List.of("CS203")),
            new C("CS307", "信息安全", 2, "MAJOR", "ELECTIVE", List.of(5, 6), List.of("CS204")),

            // 实践环节
            new C("PR101", "程序设计实践", 2, "PRACTICE", "REQUIRED", List.of(2, 3), List.of("CS101")),
            new C("PR102", "软件工程课程设计", 4, "PRACTICE", "REQUIRED", List.of(6, 7), List.of("CS301")),
            new C("PR201", "生产实习", 4, "PRACTICE", "REQUIRED", List.of(7, 8), List.of()),
            new C("PR202", "毕业设计", 8, "PRACTICE", "REQUIRED", List.of(8), List.of("PR102"))
    );

    private static final List<String> PASSED = List.of(
            "MA101", "MA102", "MA201", "MA202", "MA203", "PH101",
            "CS101", "CS102", "CS201", "CS202",
            "EN101", "EN102", "PL101", "PL102", "PE101", "PE102", "GE101", "PR101");

    public static ProgramDraft draft() {
        List<ProgramDraft.CourseDraft> drafts = COURSES.stream()
                .map(c -> new ProgramDraft.CourseDraft(c.code(), c.name(), c.credits(),
                        c.module(), c.type(), c.terms(), c.prereqs()))
                .toList();
        List<ProgramDraft.ModuleDraft> modules = List.of(
                new ProgramDraft.ModuleDraft("GENERAL", "通识教育", 28d),
                new ProgramDraft.ModuleDraft("MATH", "数学与自然科学", 25d),
                new ProgramDraft.ModuleDraft("MAJOR", "专业核心", 40d),
                new ProgramDraft.ModuleDraft("PRACTICE", "实践环节", 18d));
        return new ProgramDraft("示例大学", "软件工程", 2023, 117d, drafts, modules);
    }

    /** 渲染成人类可读的文本，作为培养方案原文存库——问答的引用就来自这里。 */
    public static String text() {
        StringBuilder sb = new StringBuilder();
        sb.append("示例大学 软件工程专业 培养方案（2023 版）\n");
        sb.append("总学分：117 学分\n\n");
        sb.append("一、通识教育 最低 28 学分\n");
        appendModule(sb, "GENERAL");
        sb.append("\n二、数学与自然科学 最低 25 学分\n");
        appendModule(sb, "MATH");
        sb.append("\n三、专业核心 最低 40 学分\n");
        appendModule(sb, "MAJOR");
        sb.append("\n四、实践环节 最低 18 学分\n");
        appendModule(sb, "PRACTICE");
        sb.append("\n说明：每学期最多修读 25 学分；课程代码与先修关系以本方案为准。\n");
        return sb.toString();
    }

    private static void appendModule(StringBuilder sb, String module) {
        for (C c : COURSES) {
            if (!c.module().equals(module)) {
                continue;
            }
            sb.append(c.code()).append(' ').append(c.name()).append(' ').append(c.credits());
            sb.append(c.type().equals("REQUIRED") ? " 必修" : " 选修");
            sb.append(" 学期 ").append(String.join(",", c.terms().stream().map(String::valueOf).toList()));
            if (!c.prereqs().isEmpty()) {
                sb.append(" 先修：").append(String.join(",", c.prereqs()));
            }
            sb.append('\n');
        }
    }

    public static List<TranscriptRepository.PassedCourse> passedCourses() {
        return COURSES.stream()
                .filter(c -> PASSED.contains(c.code()))
                .map(c -> new TranscriptRepository.PassedCourse(c.code(), c.name(), c.credits(), "2023-2024"))
                .toList();
    }

    public static double totalCredits() {
        return COURSES.stream().mapToDouble(C::credits).sum();
    }
}
