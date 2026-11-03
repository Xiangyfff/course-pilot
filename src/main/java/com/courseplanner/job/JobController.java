package com.courseplanner.job;

import com.courseplanner.auth.Authenticated;
import com.courseplanner.common.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private static final Logger log = LoggerFactory.getLogger(JobController.class);

    /** createdAt / startedAt 之差就是排队时延（提交到真正开始执行），写路径压测与前端「等了多久」都用它。 */
    public record JobView(long id, String type, String status, int progress,
                          String stage, String message, String errorCode, Long bizId,
                          String createdAt, String startedAt) {
    }

    private final JobService jobService;
    private final ScheduledExecutorService poller =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "sse-poller");
                t.setDaemon(true);
                return t;
            });

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    @GetMapping("/{id}")
    public ApiResponse<JobView> get(@Authenticated long userId, @PathVariable long id) {
        return ApiResponse.ok(toView(jobService.getOwned(userId, id)));
    }

    /**
     * SSE 进度。
     * 说明：前端用 fetch + ReadableStream 消费（而不是 EventSource），
     * 因为 EventSource 不能带 Authorization 头，把 token 放 URL 会进日志。
     */
    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@Authenticated long userId, @PathVariable long id) {
        jobService.getOwned(userId, id); // 越权检查在建立连接时做一次
        SseEmitter emitter = new SseEmitter(180_000L);
        AtomicBoolean closed = new AtomicBoolean(false);

        ScheduledFuture<?> future = poller.scheduleAtFixedRate(() -> {
            if (closed.get()) {
                return;
            }
            try {
                JobRepository.JobRow job = jobService.getOwned(userId, id);
                emitter.send(SseEmitter.event().name("progress").data(toView(job), MediaType.APPLICATION_JSON));
                if (JobStatus.valueOf(job.status()).terminal()) {
                    closed.set(true);
                    emitter.complete();
                }
            } catch (IOException e) {
                closed.set(true);
                emitter.completeWithError(e);
            } catch (Exception e) {
                closed.set(true);
                emitter.completeWithError(e);
            }
        }, 0, 400, TimeUnit.MILLISECONDS);

        Runnable cancel = () -> {
            closed.set(true);
            future.cancel(false);
        };
        emitter.onCompletion(cancel);
        emitter.onTimeout(() -> {
            cancel.run();
            emitter.complete();
        });
        emitter.onError(e -> cancel.run());
        return emitter;
    }

    private JobView toView(JobRepository.JobRow j) {
        return new JobView(j.id(), j.type(), j.status(), j.progress(),
                j.stage(), j.message(), j.errorCode(), j.bizId(),
                j.createdAt().toString(),
                j.startedAt() == null ? null : j.startedAt().toString());
    }
}
