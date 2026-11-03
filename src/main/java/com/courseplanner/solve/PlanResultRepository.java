package com.courseplanner.solve;

import com.courseplanner.solve.PlanModels.Plan;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public class PlanResultRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public PlanResultRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void saveAll(long jobId, long userId, long programId, List<Plan> plans) {
        for (Plan plan : plans) {
            try {
                jdbc.sql("""
                                INSERT INTO plan_result
                                  (job_id, user_id, program_id, plan_index, total_credits,
                                   risk_score, plan_json, created_at)
                                VALUES (:j, :u, :p, :i, :total, :risk, :json, :now)
                                """)
                        .param("j", jobId).param("u", userId).param("p", programId)
                        .param("i", plan.index())
                        .param("total", plan.totalCredits())
                        .param("risk", plan.riskScore())
                        .param("json", mapper.writeValueAsString(plan))
                        .param("now", LocalDateTime.now())
                        .update();
            } catch (Exception e) {
                throw new IllegalStateException("方案序列化失败", e);
            }
        }
    }

    public List<Plan> findByJob(long jobId) {
        return jdbc.sql("""
                        SELECT plan_json FROM plan_result WHERE job_id = :j ORDER BY plan_index
                        """)
                .param("j", jobId)
                .query((rs, i) -> read(rs.getString("plan_json")))
                .list();
    }

    public long countByJob(long jobId) {
        return jdbc.sql("SELECT COUNT(*) FROM plan_result WHERE job_id = :j")
                .param("j", jobId).query(Long.class).single();
    }

    private Plan read(String json) {
        try {
            return mapper.readValue(json, Plan.class);
        } catch (Exception e) {
            throw new IllegalStateException("方案反序列化失败", e);
        }
    }

    /** 最近一次成功求解的全部方案（只取最新的那个 job，避免混入历史批次）。 */
    public List<Plan> latest(long userId, long programId) {
        return jdbc.sql("""
                        SELECT plan_json FROM plan_result
                        WHERE job_id = (
                            SELECT MAX(pr.job_id) FROM plan_result pr
                            JOIN job j ON j.id = pr.job_id
                            WHERE pr.user_id = :u AND pr.program_id = :p AND j.status = 'SUCCEEDED'
                        )
                        ORDER BY plan_index
                        """)
                .param("u", userId).param("p", programId)
                .query((rs, i) -> read(rs.getString("plan_json")))
                .list();
    }
}
