package com.courseplanner.job;

import com.courseplanner.common.BizException;
import com.courseplanner.common.ErrorCode;
import com.courseplanner.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    private final JobRepository jobs;
    private final JobDispatcher dispatcher;
    private final AppProperties props;
    /**
     * 用 ObjectProvider 延迟到第一次执行时才取 handler：
     * handler → 业务 Service → JobService 是一条真实存在的环，
     * 在构造期直接把 List<JobHandler> 注进来会触发 Spring 的循环依赖检测。
     */
    private final ObjectProvider<List<JobHandler>> handlerProvider;
    private volatile Map<JobType, JobHandler> handlers;

    public JobService(JobRepository jobs, JobDispatcher dispatcher, AppProperties props,
                      ObjectProvider<List<JobHandler>> handlerProvider) {
        this.jobs = jobs;
        this.dispatcher = dispatcher;
        this.props = props;
        this.handlerProvider = handlerProvider;
    }

    private Map<JobType, JobHandler> handlers() {
        Map<JobType, JobHandler> local = handlers;
        if (local == null) {
            synchronized (this) {
                local = handlers;
                if (local == null) {
                    local = handlerProvider.getObject().stream()
                            .collect(Collectors.toMap(JobHandler::type, Function.identity()));
                    handlers = local;
                }
            }
        }
        return local;
    }

    /**
     * 提交任务：写库（同事务内可继续写业务数据）+ 立即投递。
     * 投递失败不影响正确性，补偿扫描会把 PENDING 再捞起来。
     */
    public long submit(long userId, JobType type, Long bizId, String idempotencyKey) {
        long jobId = jobs.insertOrGet(userId, type, bizId, idempotencyKey);
        dispatcher.dispatch(jobId, type);
        return jobId;
    }

    public void execute(long jobId) {
        JobRepository.JobRow job = jobs.findById(jobId).orElse(null);
        if (job == null || JobStatus.valueOf(job.status()).terminal()) {
            return;
        }
        // 认领失败说明别的线程已经接了，或任务已终态
        long fencing = jobs.claim(jobId);
        if (fencing < 0) {
            return;
        }
        JobHandler handler = handlers().get(JobType.valueOf(job.type()));
        if (handler == null) {
            jobs.fail(jobId, fencing, "NO_HANDLER", "未知任务类型 " + job.type(), false);
            return;
        }
        try {
            handler.handle(new JobContext(job, jobs, fencing));
            if (jobs.succeed(jobId, fencing, "完成") == 0) {
                // recoverStuck 已把任务重排给新的执行者，这次的结果作废，不覆盖新线程的状态
                log.warn("job {} 的执行权已被新的认领取代，丢弃本次结果", jobId);
            }
        } catch (BizException e) {
            handleFailure(job, fencing, e.errorCode().name(), e.getMessage(), e);
        } catch (Exception e) {
            handleFailure(job, fencing, ErrorCode.INTERNAL.name(), e.getMessage(), e);
        }
    }

    private void handleFailure(JobRepository.JobRow job, long fencing, String code, String message, Exception e) {
        // 永久失败（比如文档里根本没有课程）重试三次结果一样，直接进死信，
        // 把重试留给真正会自愈的失败：模型超时、网络抖动、数据库瞬时不可用。
        boolean retryable = !(e instanceof BizException biz) || biz.retryable();
        int nextRetry = job.retryCount() + 1;
        if (!retryable) {
            if (jobs.dead(job.id(), fencing, code, message) == 0) {
                log.warn("job {} 的执行权已被新的认领取代，丢弃本次失败结果", job.id());
            }
            log.error("job {} dead (permanent): {}", job.id(), message);
            return;
        }
        if (nextRetry >= props.getJob().getMaxRetry()) {
            if (jobs.dead(job.id(), fencing, code, message) == 0) {
                log.warn("job {} 的执行权已被新的认领取代，丢弃本次失败结果", job.id());
            }
            log.error("job {} dead after {} retries: {}", job.id(), nextRetry, message, e);
        } else {
            if (jobs.fail(job.id(), fencing, code, message, true) == 0) {
                log.warn("job {} 的执行权已被新的认领取代，丢弃本次重试", job.id());
                return;
            }
            log.warn("job {} failed, retry {}/{}: {}", job.id(), nextRetry,
                    props.getJob().getMaxRetry(), message);
        }
    }

    public JobRepository.JobRow getOwned(long userId, long jobId) {
        JobRepository.JobRow job = jobs.findById(jobId)
                .orElseThrow(() -> BizException.notFound("任务"));
        if (job.userId() != userId) {
            throw BizException.forbidden();
        }
        return job;
    }

    /**
     * 本地消息表式补偿：每 3 秒扫一次 PENDING。
     * claim() 保证同一条任务不会被两个线程同时执行。
     */
    @Scheduled(fixedDelayString = "3000")
    public void pollPending() {
        for (Long id : jobs.findPending(20)) {
            jobs.findById(id).ifPresent(j -> dispatcher.dispatch(id, JobType.valueOf(j.type())));
        }
    }

    /** 进程在跑任务时崩溃，任务会永远停在 RUNNING；超时后重新排队。 */
    @Scheduled(fixedDelayString = "30000")
    public void recoverStuck() {
        int timeout = (int) props.getJob().getTimeoutSeconds();
        for (Long id : jobs.findStuckRunning(timeout, 10)) {
            log.warn("job {} stuck in RUNNING for >{}s, requeue", id, timeout);
            jobs.requeue(id);
        }
    }
}
