package com.courseplanner.extract;

import com.courseplanner.program.ProgramDraft;

/**
 * 策略接口：同一个「文档 -> 结构化方案」的动作，有规则版和模型版两种实现。
 * 规则版永远可用，是模型版的降级兜底。
 */
public interface ProgramExtractor {

    /**
     * @param usage 模型调用的 token/耗时；规则抽取为 null
     */
    record ExtractResult(ProgramDraft draft, String extractor, LlmClient.Result usage) {
    }

    ExtractResult extract(String text);

    String name();
}
