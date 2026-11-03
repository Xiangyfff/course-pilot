package com.courseplanner.extract.validate;

import com.courseplanner.program.ProgramDraft;

import java.util.List;

/**
 * 校验链的一环：拿到上一步的结果，修复自己能修复的，修不了就抛异常。
 * 必须幂等、可重复执行——同一份文档重跑两次，结果应当一致。
 */
public interface DraftValidator {

    String name();

    ProgramDraft validate(ProgramDraft draft, List<String> warnings);
}
