package com.courseplanner.extract.validate;

import com.courseplanner.common.BizException;
import com.courseplanner.common.ErrorCode;
import com.courseplanner.program.ProgramDraft;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@Order(1)
public class StructureValidator implements DraftValidator {

    private static final int MAX_COURSES = 300;

    @Override
    public String name() {
        return "结构校验";
    }

    @Override
    public ProgramDraft validate(ProgramDraft draft, List<String> warnings) {
        if (draft == null || draft.courses() == null || draft.courses().isEmpty()) {
            // 永久失败：同一份文档重试三次也不会突然冒出课程，直接进死信，别再占线程池
            throw BizException.permanent(ErrorCode.LLM_FAILED, "没有识别出任何课程，请检查文件或换一份模板");
        }
        Map<String, ProgramDraft.CourseDraft> byCode = new LinkedHashMap<>();
        int dropped = 0;
        for (ProgramDraft.CourseDraft raw : draft.courses()) {
            ProgramDraft.CourseDraft c = raw.normalized();
            if (c.code().isBlank() || c.name().isBlank() || c.credits() == null
                    || c.credits() <= 0 || c.credits() > 20) {
                dropped++;
                continue;
            }
            if (byCode.containsKey(c.code())) {
                warnings.add("课程代码 " + c.code() + " 重复，已保留第一条");
                continue;
            }
            byCode.put(c.code(), c);
        }
        if (dropped > 0) {
            warnings.add("丢弃 " + dropped + " 条字段不完整的课程");
        }
        if (byCode.isEmpty()) {
            throw new BizException(ErrorCode.LLM_FAILED, "识别出的课程全部不合法，请人工确认文档格式");
        }
        if (byCode.size() > MAX_COURSES) {
            warnings.add("课程数量超过 " + MAX_COURSES + "，已截断");
            List<ProgramDraft.CourseDraft> limited = new ArrayList<>(byCode.values()).subList(0, MAX_COURSES);
            return new ProgramDraft(draft.school(), draft.major(), draft.gradeYear(),
                    draft.totalCredits(), limited, draft.modules());
        }
        return new ProgramDraft(draft.school(), draft.major(), draft.gradeYear(),
                draft.totalCredits(), new ArrayList<>(byCode.values()), draft.modules());
    }
}
