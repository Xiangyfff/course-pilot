package com.courseplanner.extract;

import com.courseplanner.program.ProgramDraft;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规则抽取：不依赖任何模型。
 *
 * 它是两件事的兜底：
 * 1) 没有 API Key 时整条链路仍然能跑通（演示不能卡在网络上）；
 * 2) 模型调用失败/输出不合法时降级，而不是让任务直接失败。
 *
 * 局限要讲清楚：只能处理「一行一门课、代码-名称-学分」的排版，
 * 复杂表格/扫描件必须靠模型或人工校正。
 */
@Component
public class RuleBasedExtractor implements ProgramExtractor {

    private static final Pattern CODE = Pattern.compile("^([A-Z]{2,6}\\d{2,4})$");
    private static final Pattern CODE_ANY = Pattern.compile("\\b([A-Z]{2,6}\\d{2,4})\\b");
    private static final Pattern NUMBER = Pattern.compile("^\\d{1,2}(\\.\\d)?$");
    private static final Pattern TERMS = Pattern.compile("^[1-8](,[1-8])*$");
    private static final Pattern TOTAL = Pattern.compile("总学分[^0-9]{0,6}(\\d{2,3}(\\.\\d)?)");
    private static final Pattern MODULE_LINE = Pattern.compile("^(.{2,16}?)(?:模块|课程群)?\\s*(?:最低|要求|应修)?\\s*(\\d{2,3}(?:\\.\\d)?)\\s*学分");
    private static final Pattern PREREQ = Pattern.compile("先修[:：]\\s*([A-Z]{2,6}\\d{2,4}(?:[、,，/ ]+[A-Z]{2,6}\\d{2,4})*)");

    @Override
    public String name() {
        return "rule-based";
    }

    @Override
    public ExtractResult extract(String text) {
        return new ExtractResult(build(text), name(), null);
    }

    private ProgramDraft build(String text) {
        List<ProgramDraft.CourseDraft> courses = new ArrayList<>();
        List<ProgramDraft.ModuleDraft> modules = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Double totalCredits = null;
        String school = null;
        String major = null;

        for (String rawLine : text.split("\\R")) {
            String line = rawLine.replace('\u3000', ' ').trim();
            if (line.isEmpty()) {
                continue;
            }

            Matcher total = TOTAL.matcher(line);
            if (total.find()) {
                totalCredits = Double.parseDouble(total.group(1));
                continue;
            }
            // 课程行优先识别：后面的标题/模块启发式都建立在「这一行不是课程」之上
            ProgramDraft.CourseDraft course = parseCourse(line);
            if (course != null) {
                if (seen.add(course.code())) {
                    courses.add(course);
                }
                continue;
            }

            // 标题行常把学校和专业写在一起：「示例大学 软件工程专业 2023 级培养方案」。
            // 按「专业」切开：左边最后一段是专业，再往左是学校。
            if ((school == null || major == null) && line.contains("培养方案")) {
                int p = line.indexOf("专业");
                if (p > 0) {
                    String before = line.substring(0, Math.min(p, 40)).trim();
                    int sp = before.lastIndexOf(' ');
                    if (sp > 0) {
                        if (school == null) {
                            school = before.substring(0, sp).trim();
                        }
                        if (major == null) {
                            major = before.substring(sp + 1).trim();
                        }
                    } else if (major == null) {
                        major = before;
                    }
                }
                if (school == null) {
                    for (String token : line.split("[ ,，、·]+")) {
                        if (token.endsWith("大学") || token.endsWith("学院")) {
                            school = token;
                            break;
                        }
                    }
                }
            }
            // 学校和专业分行的写法（「示例大学」一行、「软件工程专业」一行）
            if (school == null && (line.contains("大学") || line.contains("学院"))
                    && !line.contains("专业") && !line.contains("方案") && !line.contains("培养")
                    && line.length() <= 24) {
                school = line;
            }
            if (major == null && line.endsWith("专业") && line.length() <= 24) {
                major = line.substring(0, line.length() - 2).trim();
            }

            Matcher module = MODULE_LINE.matcher(line);
            if (module.matches() && !line.contains("总")) {
                String name = module.group(1).trim();
                String code = moduleCode(name);
                if (modules.stream().noneMatch(m -> m.code().equals(code))) {
                    modules.add(new ProgramDraft.ModuleDraft(code, name,
                            Double.parseDouble(module.group(2))));
                }
            }
        }

        if (modules.isEmpty() && !courses.isEmpty()) {
            // 文档里没有模块学分要求时，用课程数据兜一个，保证求解器有约束可用
            modules.add(new ProgramDraft.ModuleDraft("GENERAL", "全部课程",
                    courses.stream().mapToDouble(ProgramDraft.CourseDraft::credits).sum()));
        }
        if (totalCredits == null) {
            totalCredits = modules.stream().mapToDouble(ProgramDraft.ModuleDraft::minCredits).sum();
        }
        return new ProgramDraft(school, major, null, totalCredits, courses, modules);
    }

    private ProgramDraft.CourseDraft parseCourse(String line) {
        String[] tokens = line.split("\\s+");
        if (tokens.length < 3) {
            return null;
        }
        int codeIdx = -1;
        for (int i = 0; i < tokens.length; i++) {
            if (CODE.matcher(tokens[i].toUpperCase(Locale.ROOT)).matches()) {
                codeIdx = i;
                break;
            }
        }
        if (codeIdx < 0) {
            return null;
        }
        String code = tokens[codeIdx].toUpperCase(Locale.ROOT);

        int creditIdx = -1;
        double credits = 0;
        for (int i = tokens.length - 1; i > codeIdx; i--) {
            String t = tokens[i].replace("学分", "");
            if (NUMBER.matcher(t).matches()) {
                double v = Double.parseDouble(t);
                if (v > 0 && v <= 12) {
                    credits = v;
                    creditIdx = i;
                    break;
                }
            }
        }
        if (creditIdx < 0) {
            return null;
        }

        StringBuilder name = new StringBuilder();
        for (int i = codeIdx + 1; i < creditIdx; i++) {
            String t = tokens[i];
            if (TERMS.matcher(t).matches()) {
                continue;
            }
            name.append(t);
        }
        String courseName = name.toString().replaceAll("[（(]\\s*[)）]", "").trim();
        if (courseName.isEmpty()) {
            return null;
        }

        List<Integer> terms = new ArrayList<>();
        for (String t : tokens) {
            if (TERMS.matcher(t).matches() && !t.equals(String.valueOf((int) credits))) {
                for (String p : t.split(",")) {
                    terms.add(Integer.parseInt(p));
                }
            }
        }

        List<String> prereqs = new ArrayList<>();
        Matcher pm = PREREQ.matcher(line);
        if (pm.find()) {
            for (String p : pm.group(1).split("[、,，/ ]+")) {
                if (!p.isBlank()) {
                    prereqs.add(p.toUpperCase(Locale.ROOT));
                }
            }
        }

        String type = (line.contains("选修") || line.contains("限选")) ? "ELECTIVE" : "REQUIRED";
        String module = moduleCode(inferModuleName(courseName));
        return new ProgramDraft.CourseDraft(code, courseName, credits, module, type,
                terms, prereqs).normalized();
    }

    private static String inferModuleName(String courseName) {
        if (courseName.matches(".*(数学|物理|化学|线性代数|概率|统计).*")) {
            return "数学与自然科学";
        }
        if (courseName.matches(".*(体育|英语|思想|政治|军事|职业|通识|写作).*")) {
            return "通识教育";
        }
        if (courseName.matches(".*(实验|实践|实习|设计|实训).*")) {
            return "实践环节";
        }
        return "专业核心";
    }

    private static String moduleCode(String name) {
        if (name.contains("数学") || name.contains("自然科学")) return "MATH";
        if (name.contains("通识")) return "GENERAL";
        if (name.contains("实践")) return "PRACTICE";
        if (name.contains("选修")) return "ELECTIVE";
        return "MAJOR";
    }
}
