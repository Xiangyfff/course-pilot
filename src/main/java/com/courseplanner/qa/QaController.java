package com.courseplanner.qa;

import com.courseplanner.auth.Authenticated;
import com.courseplanner.common.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api/qa")
public class QaController {

    /** history 可选：只用于解析「那按这个排呢」这类指代，事实仍必须来自工具。 */
    public record AskRequest(@NotNull Long programId, @NotBlank String question,
                             List<QaService.Turn> history) {
    }

    private final QaService qaService;
    private final ObjectMapper mapper;
    private final ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "qa-stream");
        t.setDaemon(true);
        return t;
    });

    public QaController(QaService qaService, ObjectMapper mapper) {
        this.qaService = qaService;
        this.mapper = mapper;
    }

    @GetMapping("/history")
    public ApiResponse<List<QaRepository.Message>> history(@Authenticated long userId,
                                                           @RequestParam(required = false) Long programId) {
        return ApiResponse.ok(qaService.history(userId, programId));
    }

    /**
     * 流式问答。整体答完再按小块推给前端——
     * 对短回答（200 字以内）来说，首字延迟差别不大，但保留了随时切换上游流式的接口。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Authenticated long userId, @Valid @RequestBody AskRequest req) {
        SseEmitter emitter = new SseEmitter(120_000L);
        pool.submit(() -> {
            try {
                QaService.Answer answer = qaService.answer(userId, req.programId(), req.question(), req.history());
                java.util.Map<String, Object> meta = new java.util.LinkedHashMap<>();
                meta.put("citations", answer.citations());
                meta.put("engine", answer.engine());
                meta.put("steps", answer.steps());
                emitter.send(SseEmitter.event().name("meta").data(
                        mapper.writeValueAsString(meta), MediaType.APPLICATION_JSON));
                String text = answer.text();
                int step = 6;
                for (int i = 0; i < text.length(); i += step) {
                    String chunk = text.substring(i, Math.min(text.length(), i + step));
                    emitter.send(SseEmitter.event().name("delta").data(chunk));
                    Thread.sleep(12);
                }
                emitter.send(SseEmitter.event().name("done").data(""));
                emitter.complete();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                emitter.completeWithError(e);
            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event().name("error").data(String.valueOf(e.getMessage())));
                } catch (Exception ignored) {
                    // 客户端已经断开
                }
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }
}
