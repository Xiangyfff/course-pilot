package com.courseplanner.job;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「任务被 recoverStuck 重排之后，原来那个慢线程还能不能把结果写回去？」
 * 答案必须是不能——靠认领时拿到的 version 做围栏令牌（fencing token）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "app.demo.seed=false")
@ActiveProfiles("dev")
class JobFencingTest {

    @Autowired
    private JobRepository jobs;

    @Test
    @DisplayName("重排后旧执行者写回被拒，新执行者正常收尾")
    void staleExecutorCannotOverwriteAfterRequeue() {
        long jobId = jobs.insertOrGet(1L, JobType.PARSE_PROGRAM, null,
                "test-fencing-" + System.nanoTime());

        long first = jobs.claim(jobId);
        assertThat(first).as("第一次认领应拿到非负的围栏令牌").isGreaterThanOrEqualTo(0);
        assertThat(jobs.findById(jobId).orElseThrow().status()).isEqualTo("RUNNING");

        // 模拟 recoverStuck：任务在 RUNNING 超时后被重新排队
        jobs.requeue(jobId);
        assertThat(jobs.findById(jobId).orElseThrow().status()).isEqualTo("PENDING");

        long second = jobs.claim(jobId);
        assertThat(second).as("重排后新执行者拿到新的围栏令牌").isNotEqualTo(first);

        // 旧执行者（还活着的慢线程）此时才结束，它的写回必须全部落空
        assertThat(jobs.succeed(jobId, first, "旧执行者的结果")).isZero();
        assertThat(jobs.progress(jobId, first, 99, "STALE", null)).isZero();
        assertThat(jobs.fail(jobId, first, "BOOM", "旧执行者的失败", true)).isZero();
        assertThat(jobs.dead(jobId, first, "BOOM", "旧执行者的死信")).isZero();

        JobRepository.JobRow stillRunning = jobs.findById(jobId).orElseThrow();
        assertThat(stillRunning.status()).as("新执行者仍在跑，状态不能被旧线程改掉").isEqualTo("RUNNING");
        assertThat(stillRunning.retryCount()).as("重试次数不能被旧线程篡改").isZero();

        // 新执行者正常收尾
        assertThat(jobs.succeed(jobId, second, "新执行者的结果")).isEqualTo(1);
        assertThat(jobs.findById(jobId).orElseThrow().status()).isEqualTo("SUCCEEDED");
    }

    @Test
    @DisplayName("重复投递同一条 PENDING 任务时，只有一个线程能认领成功")
    void onlyOneClaimWins() {
        long jobId = jobs.insertOrGet(1L, JobType.PARSE_PROGRAM, null,
                "test-claim-once-" + System.nanoTime());

        long a = jobs.claim(jobId);
        long b = jobs.claim(jobId);

        assertThat(a).isGreaterThanOrEqualTo(0);
        assertThat(b).as("第二个线程认领失败，返回 -1").isEqualTo(-1L);
    }
}
