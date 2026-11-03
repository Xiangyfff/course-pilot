package com.courseplanner.transcript;

import com.courseplanner.common.BizException;
import com.courseplanner.common.ErrorCode;
import com.courseplanner.extract.DocumentParserFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 成绩单是强格式文本，解析是纯 CPU + 正则，不值得走异步任务。
 * 「什么时候该异步」本身就是设计决定：只有会等外部 IO（模型）的才异步。
 */
@Service
public class TranscriptService {

    public record UploadResult(long transcriptId, int courseCount, double totalCredits,
                               List<TranscriptExtractor.Entry> courses) {
    }

    private final TranscriptRepository transcripts;
    private final TranscriptExtractor extractor;
    private final DocumentParserFactory parsers;

    public TranscriptService(TranscriptRepository transcripts, TranscriptExtractor extractor,
                             DocumentParserFactory parsers) {
        this.transcripts = transcripts;
        this.extractor = extractor;
        this.parsers = parsers;
    }

    public UploadResult upload(long userId, MultipartFile file) {
        String filename = file.getOriginalFilename() == null ? "transcript" : file.getOriginalFilename();
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BizException(ErrorCode.FILE_INVALID, "读取文件失败", e);
        }
        String text = parsers.parse(filename, bytes);
        List<TranscriptExtractor.Entry> entries = extractor.extract(text);
        if (entries.isEmpty()) {
            throw new BizException(ErrorCode.FILE_INVALID,
                    "没有识别出成绩记录，请确认每行包含课程代码、名称与学分");
        }
        long id = transcripts.insert(userId, filename, "READY");
        transcripts.replaceCourses(id, entries.stream()
                .map(e -> new TranscriptRepository.PassedCourse(e.code(), e.name(), e.credits(), e.term()))
                .toList());
        double total = entries.stream().mapToDouble(TranscriptExtractor.Entry::credits).sum();
        return new UploadResult(id, entries.size(), total, entries);
    }

    public List<TranscriptRepository.TranscriptRow> list(long userId) {
        return transcripts.listByUser(userId);
    }

    /** 求解默认用最近一次上传的成绩单：多份成绩单的合并语义留到以后再说。 */
    public Set<String> passedCodes(long userId) {
        Optional<Long> latest = transcripts.findLatestId(userId);
        if (latest.isEmpty()) {
            return Set.of();
        }
        return transcripts.findCourses(latest.get()).stream()
                .map(TranscriptRepository.PassedCourse::code)
                .filter(c -> c != null && !c.isBlank())
                .map(c -> c.trim().toUpperCase())
                .collect(Collectors.toSet());
    }

    public List<TranscriptRepository.PassedCourse> passedCourses(long userId) {
        Optional<Long> latest = transcripts.findLatestId(userId);
        return latest.map(transcripts::findCourses).orElse(List.of());
    }
}
