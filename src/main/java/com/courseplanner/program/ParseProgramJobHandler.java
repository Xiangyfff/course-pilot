package com.courseplanner.program;

import com.courseplanner.common.BizException;
import com.courseplanner.extract.ProgramExtractionService;
import com.courseplanner.job.JobContext;
import com.courseplanner.job.JobHandler;
import com.courseplanner.job.JobType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ParseProgramJobHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(ParseProgramJobHandler.class);

    private final ProgramRepository programs;
    private final CourseRepository courses;
    private final ProgramExtractionService extraction;
    private final CacheManager cacheManager;

    public ParseProgramJobHandler(ProgramRepository programs, CourseRepository courses,
                                  ProgramExtractionService extraction, CacheManager cacheManager) {
        this.programs = programs;
        this.courses = courses;
        this.extraction = extraction;
        this.cacheManager = cacheManager;
    }

    @Override
    public JobType type() {
        return JobType.PARSE_PROGRAM;
    }

    @Override
    public void handle(JobContext ctx) {
        long programId = ctx.bizId();
        ProgramRepository.ProgramRow program = programs.findById(programId)
                .orElseThrow(() -> BizException.notFound("培养方案"));

        ctx.progress(15, "PARSING", "读取文档");
        String text = program.sourceText();
        if (text == null || text.isBlank()) {
            throw new BizException(com.courseplanner.common.ErrorCode.FILE_INVALID, "文档正文为空");
        }

        ctx.progress(35, "EXTRACTING", "识别课程、模块与先修关系");
        ProgramExtractionService.Extracted extracted =
                extraction.extract(text, ctx.userId(), ctx.jobId());

        ctx.progress(75, "PERSISTING", "写入课程与约束");
        courses.replaceAll(programId, extracted.draft());

        double total = extracted.draft().totalCredits() == null ? 0 : extracted.draft().totalCredits();
        String warnings = String.join("\n", extracted.warnings());
        saveWithRetry(programId, total, warnings);

        programs.updateMeta(programId, extracted.draft().school(), extracted.draft().major(),
                extracted.draft().gradeYear());

        // 标题优先用文档里识别出的「学校 + 专业」，比文件名（program-software-engineering）可读
        String school = extracted.draft().school();
        String major = extracted.draft().major();
        if (school != null && !school.isBlank() && major != null && !major.isBlank()) {
            programs.updateTitle(programId, school + " " + major + " 培养方案");
        }

        Cache cache = cacheManager.getCache("programDetail");
        if (cache != null) {
            cache.evict(programId);
        }
        ctx.progress(100, "DONE", "识别出 " + extracted.draft().courses().size()
                + " 门课程（" + extracted.extractor() + "）");
        log.info("program {} parsed by {}, {} courses, {} warnings",
                programId, extracted.extractor(), extracted.draft().courses().size(),
                extracted.warnings().size());
    }

    /**
     * 乐观锁：这条记录可能同时被人工校正和解析任务写。
     * 冲突就重读版本号再写，而不是覆盖别人的修改。
     */
    private void saveWithRetry(long programId, double total, String warnings) {
        for (int i = 0; i < 3; i++) {
            ProgramRepository.ProgramRow current = programs.findById(programId).orElseThrow();
            if (programs.updateStatus(programId, "READY", total, warnings, current.version())) {
                return;
            }
        }
        List<String> ignored = List.of();
        log.warn("optimistic lock conflict on program {}, last write wins {}", programId, ignored);
        ProgramRepository.ProgramRow current = programs.findById(programId).orElseThrow();
        programs.updateStatus(programId, "READY", total, warnings, current.version());
    }
}
