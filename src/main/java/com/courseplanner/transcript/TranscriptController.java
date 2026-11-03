package com.courseplanner.transcript;

import com.courseplanner.auth.Authenticated;
import com.courseplanner.common.ApiResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/transcripts")
public class TranscriptController {

    private final TranscriptService transcriptService;

    public TranscriptController(TranscriptService transcriptService) {
        this.transcriptService = transcriptService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<TranscriptService.UploadResult> upload(@Authenticated long userId,
                                                             @RequestPart("file") MultipartFile file) {
        return ApiResponse.ok(transcriptService.upload(userId, file));
    }

    @GetMapping
    public ApiResponse<List<TranscriptRepository.TranscriptRow>> list(@Authenticated long userId) {
        return ApiResponse.ok(transcriptService.list(userId));
    }
}
