package com.courseplanner.extract.validate;

import com.courseplanner.program.ProgramDraft;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 课程挂在一个不存在的模块下，求解时这块学分就永远算不满。
 *
 * <p>两种来源要分开处理：
 * <ul>
 *   <li>课程写的是模块<b>名字</b>（「专业核心」）而模块表里 code 是 {@code CORE}——统一映射到 code，
 *       否则会凭空补建一个同名模块，学分要求对不上；</li>
 *   <li>真的没定义过——补一个 0 学分的模块，让问题显式暴露，而不是静默丢失。</li>
 * </ul>
 */
@Component
@Order(2)
public class ModuleValidator implements DraftValidator {

    @Override
    public String name() {
        return "模块校验";
    }

    @Override
    public ProgramDraft validate(ProgramDraft draft, List<String> warnings) {
        Map<String, ProgramDraft.ModuleDraft> byCode = new LinkedHashMap<>();
        Map<String, String> nameToCode = new HashMap<>();
        for (ProgramDraft.ModuleDraft m : draft.modules()) {
            ProgramDraft.ModuleDraft n = m.normalized();
            // 文档里模块常写成整行标题：「一、通识教育（最低 24 学分）」。
            // 模型有时把这整行当模块名抄回来，清洗掉序号和括号里的学分要求，
            // 否则模块名和课程挂的模块名对不上，学分要求永远算不满。
            String code = cleanModuleLabel(n.code());
            String name = cleanModuleLabel(n.name());
            ProgramDraft.ModuleDraft c = new ProgramDraft.ModuleDraft(code, name, n.minCredits());
            byCode.putIfAbsent(code, c);
            nameToCode.putIfAbsent(name, code);
        }

        List<ProgramDraft.CourseDraft> courses = new ArrayList<>();
        for (ProgramDraft.CourseDraft c : draft.courses()) {
            String module = c.module();
            String resolved = byCode.containsKey(module) ? module : nameToCode.get(cleanModuleLabel(module));
            if (resolved == null) {
                warnings.add("课程 " + c.code() + " 的模块 " + module + " 未在方案中定义，已补建");
                byCode.put(module, new ProgramDraft.ModuleDraft(module, module, 0d));
                resolved = module;
            }
            courses.add(resolved.equals(module) ? c
                    : new ProgramDraft.CourseDraft(c.code(), c.name(), c.credits(), resolved,
                    c.type(), c.terms(), c.prereqs()));
        }

        // 保留有学分要求的模块，以及确实有课挂着的模块；其余空壳丢掉
        Map<String, Long> used = courses.stream()
                .collect(java.util.stream.Collectors.groupingBy(ProgramDraft.CourseDraft::module,
                        java.util.stream.Collectors.counting()));
        List<ProgramDraft.ModuleDraft> kept = byCode.values().stream()
                .filter(m -> m.minCredits() > 0 || used.containsKey(m.code()))
                .toList();

        return new ProgramDraft(draft.school(), draft.major(), draft.gradeYear(),
                draft.totalCredits(), courses, new ArrayList<>(kept));
    }

    /** 「一、通识教育（最低 24 学分）」→「通识教育」 */
    static String cleanModuleLabel(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        s = s.replaceFirst("^[一二三四五六七八九十]+[、.．]\\s*", "");
        s = s.replaceFirst("^第[一二三四五六七八九十]+部分\\s*[、.．:：]?\\s*", "");
        s = s.replaceFirst("[（(]\\s*最低\\s*[0-9.]+\\s*学分\\s*[）)]\\s*$", "");
        s = s.replaceFirst("\\s*[（(][^）)]*学分[）)]\\s*$", "");
        return s.trim();
    }
}
