package com.courseplanner.job;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class JobRepository {

    public record JobRow(long id, long userId, String type, Long bizId, String status, int progress,
                         String stage, String message, String errorCode, int retryCount,
                         String idempotencyKey, LocalDateTime createdAt, LocalDateTime startedAt,
                         LocalDateTime updatedAt) {
    }

    private final JdbcClient jdbc;

    public JobRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 幂等提交：同一 (user, type, biz, idemKey) 只产生一个任务。
     * 唯一索引兜底，冲突时返回已有任务 id，而不是抛给用户。
     */
    public long insertOrGet(long userId, JobType type, Long bizId, String idemKey) {
        try {
            KeyHolder kh = new GeneratedKeyHolder();
            LocalDateTime now = LocalDateTime.now();
            jdbc.sql("""
                            INSERT INTO job (user_id, type, biz_id, status, progress, retry_count,
                                             idempotency_key, created_at, updated_at, version)
                            VALUES (:uid, :type, :biz, 'PENDING', 0, 0, :key, :now, :now, 0)
                            """)
                    .param("uid", userId)
                    .param("type", type.name())
                    .param("biz", bizId)
                    .param("key", idemKey)
                    .param("now", now)
                    .update(kh, "id");
            return kh.getKey().longValue();
        } catch (DuplicateKeyException e) {
            return jdbc.sql("SELECT id FROM job WHERE idempotency_key = :key")
                    .param("key", idemKey).query(Long.class).single();
        }
    }

    public Optional<JobRow> findById(long id) {
        return jdbc.sql("""
                        SELECT id, user_id, type, biz_id, status, progress, stage, message, error_code,
                               retry_count, idempotency_key, created_at, started_at, updated_at
                        FROM job WHERE id = :id
                        """)
                .param("id", id)
                .query(JobRepository::map)
                .optional();
    }

    /**
     * 认领任务：只有仍处于 PENDING 的才能被抢到，这是「同一任务只执行一次」的全部实现。
     *
     * <p>返回这次认领拿到的 {@code version}（失败返回 -1），作为**围栏令牌**（fencing token）。
     * 之后所有写状态的 SQL 都带 {@code version = :fencing}：任务被 recoverStuck 重新排队、
     * 又被另一个线程认领后，version 已经变了，上一轮那个「还活着的慢线程」再写回时更新到 0 行，
     * 不会把新线程正在跑的任务改成 SUCCEEDED/FAILED。
     */
    public long claim(long id) {
        LocalDateTime now = LocalDateTime.now();
        int updated = jdbc.sql("""
                        UPDATE job SET status = 'RUNNING', started_at = :now, updated_at = :now,
                                       version = version + 1
                        WHERE id = :id AND status = 'PENDING'
                        """)
                .param("now", now)
                .param("id", id)
                .update();
        if (updated != 1) {
            return -1L;
        }
        return jdbc.sql("SELECT version FROM job WHERE id = :id")
                .param("id", id).query(Integer.class).single().longValue();
    }

    /** 返回 0 表示这次执行已经被新的认领取代，调用方只应记日志。 */
    public int progress(long id, long fencing, int progress, String stage, String message) {
        return jdbc.sql("""
                        UPDATE job SET progress = :p, stage = :s, message = :m, updated_at = :now
                        WHERE id = :id AND status = 'RUNNING' AND version = :v
                        """)
                .param("p", progress).param("s", stage).param("m", message)
                .param("now", LocalDateTime.now()).param("id", id).param("v", fencing)
                .update();
    }

    public int succeed(long id, long fencing, String message) {
        LocalDateTime now = LocalDateTime.now();
        return jdbc.sql("""
                        UPDATE job SET status = 'SUCCEEDED', progress = 100, stage = 'DONE',
                                      message = :m, finished_at = :now, updated_at = :now
                        WHERE id = :id AND status = 'RUNNING' AND version = :v
                        """)
                .param("m", message).param("now", now).param("id", id).param("v", fencing)
                .update();
    }

    /** 失败后按重试次数决定回到 PENDING 还是进入终态。返回影响行数，0 表示已成为旧执行者。 */
    public int fail(long id, long fencing, String errorCode, String message, boolean retryable) {
        LocalDateTime now = LocalDateTime.now();
        if (retryable) {
            return jdbc.sql("""
                            UPDATE job SET status = 'PENDING', retry_count = retry_count + 1,
                                          error_code = :code, message = :m, stage = 'RETRY',
                                          updated_at = :now
                            WHERE id = :id AND status = 'RUNNING' AND version = :v
                            """)
                    .param("code", errorCode).param("m", message).param("now", now)
                    .param("id", id).param("v", fencing)
                    .update();
        }
        return jdbc.sql("""
                            UPDATE job SET status = :status, error_code = :code, message = :m,
                                          finished_at = :now, updated_at = :now
                            WHERE id = :id AND status = 'RUNNING' AND version = :v
                            """)
                .param("status", JobStatus.FAILED.name())
                .param("code", errorCode).param("m", message).param("now", now)
                .param("id", id).param("v", fencing)
                .update();
    }

    public int dead(long id, long fencing, String errorCode, String message) {
        LocalDateTime now = LocalDateTime.now();
        return jdbc.sql("""
                        UPDATE job SET status = 'DEAD', error_code = :code, message = :m,
                                      finished_at = :now, updated_at = :now
                        WHERE id = :id AND status = 'RUNNING' AND version = :v
                        """)
                .param("code", errorCode).param("m", message).param("now", now)
                .param("id", id).param("v", fencing)
                .update();
    }

    /** 补偿扫描：进程崩溃导致停在 RUNNING 的任务，超时后重新排队。 */
    public List<Long> findStuckRunning(int timeoutSeconds, int limit) {
        return jdbc.sql("""
                        SELECT id FROM job
                        WHERE status = 'RUNNING' AND updated_at < :deadline
                        ORDER BY id LIMIT :limit
                        """)
                .param("deadline", LocalDateTime.now().minusSeconds(timeoutSeconds))
                .param("limit", limit)
                .query(Long.class)
                .list();
    }

    public List<Long> findPending(int limit) {
        return jdbc.sql("SELECT id FROM job WHERE status = 'PENDING' ORDER BY id LIMIT :limit")
                .param("limit", limit)
                .query(Long.class)
                .list();
    }

    public void requeue(long id) {
        jdbc.sql("""
                        UPDATE job SET status = 'PENDING', stage = 'REQUEUE', updated_at = :now
                        WHERE id = :id AND status = 'RUNNING'
                        """)
                .param("now", LocalDateTime.now()).param("id", id)
                .update();
    }

    private static JobRow map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new JobRow(
                rs.getLong("id"), rs.getLong("user_id"), rs.getString("type"),
                (Long) rs.getObject("biz_id"), rs.getString("status"), rs.getInt("progress"),
                rs.getString("stage"), rs.getString("message"), rs.getString("error_code"),
                rs.getInt("retry_count"), rs.getString("idempotency_key"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime());
    }
}
