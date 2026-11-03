package com.courseplanner.extract.validate;

import com.courseplanner.program.ProgramDraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 责任链：结构 -> 模块 -> 先修 -> 学分。
 * 顺序不能换：先有合法课程，才谈得上模块归属；先有模块，才谈得上学分守恒。
 */
@Component
public class ValidationChain {

    private static final Logger log = LoggerFactory.getLogger(ValidationChain.class);

    private final List<DraftValidator> validators;

    public ValidationChain(List<DraftValidator> validators) {
        this.validators = validators;
    }

    public record Outcome(ProgramDraft draft, List<String> warnings) {
    }

    public Outcome run(ProgramDraft draft) {
        List<String> warnings = new ArrayList<>();
        ProgramDraft current = draft;
        for (DraftValidator v : validators) {
            int before = warnings.size();
            current = v.validate(current, warnings);
            if (warnings.size() > before) {
                log.info("validator {} produced {} warnings", v.name(), warnings.size() - before);
            }
        }
        return new Outcome(current, warnings);
    }
}
