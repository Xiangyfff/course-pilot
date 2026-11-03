package com.courseplanner.program;

import com.courseplanner.common.BizException;
import com.courseplanner.config.AppProperties;
import com.courseplanner.extract.DocumentParserFactory;
import com.courseplanner.job.JobService;
import com.courseplanner.job.JobType;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

@Service
public class ProgramService {

    public record CreateResult(long programId, long jobId, String textPreview) {
    }

    public record CourseView(String code, String name, double credits, String module,
                             String type, List<Integer> terms, List<String> prereqs) {
    }

    public record ModuleView(String code, String name, double minCredits, double available) {
    }

    public record ProgramDetail(long id, String school, String major, Integer gradeYear,
                                String status, double totalCredits, int courseCount,
                                List<CourseView> courses, List<ModuleView> modules,
                                List<String> warnings) {
    }

    public record ProgramSummary(long id, String title, String school, String major,
                                 String status, double totalCredits, int courseCount) {
    }

    private final ProgramRepository programs;
    private final CourseRepository courses;
    private final DocumentParserFactory parsers;
    private final JobService jobs;
    private final AppProperties props;

    public ProgramService(ProgramRepository programs, CourseRepository courses,
                          DocumentParserFactory parsers, JobService jobs, AppProperties props) {
        this.programs = programs;
        this.courses = courses;
        this.parsers = parsers;
        this.jobs = jobs;
        this.props = props;
    }

    public CreateResult create(long userId, MultipartFile file) {
        String filename = file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename();
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BizException(com.courseplanner.common.ErrorCode.FILE_INVALID, "读取上传文件失败", e);
        }
        String hash = sha256(bytes);

        // 同一份文件重复上传：复用上一次的解析结果，不重复烧 token
        var existing = programs.findRecentByHash(userId, hash);
        if (existing.isPresent()) {
            long programId = existing.get();
            long jobId = jobs.submit(userId, JobType.PARSE_PROGRAM, programId,
                    idemKey(userId, hash));
            return new CreateResult(programId, jobId, "");
        }

        String text = parsers.parse(filename, bytes);
        long programId = programs.insert(userId, null, null, null,
                stripExtension(filename), filename, hash, text);
        long jobId = jobs.submit(userId, JobType.PARSE_PROGRAM, programId, idemKey(userId, hash));
        String preview = text.length() > 600 ? text.substring(0, 600) : text;
        return new CreateResult(programId, jobId, preview);
    }

    @Cacheable(cacheNames = "programDetail", key = "#programId")
    public ProgramDetail detail(long userId, long programId) {
        ProgramRepository.ProgramRow p = programs.findById(programId)
                .orElseThrow(() -> BizException.notFound("培养方案"));
        if (p.userId() != userId) {
            throw BizException.forbidden();
        }
        List<Course> list = courses.findCourses(programId);
        List<CourseRepository.ModuleRequirement> modules = courses.findModules(programId);

        List<CourseView> courseViews = list.stream()
                .map(c -> new CourseView(c.code(), c.name(), c.credits(), c.module(),
                        c.type(), c.offeredTerms(), c.prereqs()))
                .toList();

        List<ModuleView> moduleViews = modules.stream()
                .map(m -> new ModuleView(m.code(), m.name(), m.minCredits(),
                        list.stream().filter(c -> c.module().equals(m.code()))
                                .mapToDouble(Course::credits).sum()))
                .toList();

        List<String> warnings = p.warnings() == null || p.warnings().isBlank()
                ? List.of() : List.of(p.warnings().split("\n"));

        return new ProgramDetail(p.id(), p.school(), p.major(), p.gradeYear(), p.status(),
                p.totalCredits(), list.size(), courseViews, moduleViews, warnings);
    }

    public List<ProgramSummary> list(long userId) {
        return programs.listByUser(userId).stream()
                .map(p -> new ProgramSummary(p.id(), p.title(), p.school(), p.major(),
                        p.status(), p.totalCredits(),
                        (int) courses.countCourses(p.id())))
                .toList();
    }

    private String idemKey(long userId, String hash) {
        return "PARSE_PROGRAM:" + userId + ":" + hash;
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
