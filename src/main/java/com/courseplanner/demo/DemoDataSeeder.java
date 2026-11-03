package com.courseplanner.demo;

import com.courseplanner.auth.UserRepository;
import com.courseplanner.job.JobService;
import com.courseplanner.job.JobType;
import com.courseplanner.program.CourseRepository;
import com.courseplanner.program.ProgramDraft;
import com.courseplanner.program.ProgramRepository;
import com.courseplanner.transcript.TranscriptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 演示数据：一个账号、一份软件工程培养方案、一份大二结束时的成绩单。
 * 演示打开就能用，不需要先上传文件。
 */
@Component
@ConditionalOnProperty(name = "app.demo.seed", havingValue = "true", matchIfMissing = true)
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final UserRepository users;
    private final ProgramRepository programs;
    private final CourseRepository courses;
    private final TranscriptRepository transcripts;
    private final JobService jobs;
    private final BCryptPasswordEncoder encoder;

    public DemoDataSeeder(UserRepository users, ProgramRepository programs, CourseRepository courses,
                          TranscriptRepository transcripts, JobService jobs,
                          BCryptPasswordEncoder encoder) {
        this.users = users;
        this.programs = programs;
        this.courses = courses;
        this.transcripts = transcripts;
        this.jobs = jobs;
        this.encoder = encoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (users.count() > 0) {
            return;
        }
        long userId = users.insert("demo", encoder.encode("demo1234"), "演示同学");
        long programId = programs.insert(userId, "示例大学", "软件工程", 2023,
                "软件工程专业培养方案（2023 版）", "培养方案示例.txt",
                "demo-seed", DemoProgram.text());
        courses.replaceAll(programId, DemoProgram.draft());
        programs.updateStatus(programId, "READY", DemoProgram.totalCredits(),
                "演示数据：由内置模板直接写入，未经过解析任务", 0);

        long transcriptId = transcripts.insert(userId, "成绩单示例.txt", "READY");
        transcripts.replaceCourses(transcriptId, DemoProgram.passedCourses());

        jobs.submit(userId, JobType.SOLVE, programId, "DEMO-INITIAL-SOLVE");
        log.info("demo data seeded: user=demo/{} program={} transcript={}", "demo1234", programId, transcriptId);
    }
}
