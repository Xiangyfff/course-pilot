package com.courseplanner.transcript;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 成绩单抽取：比培养方案简单——一行一门课，没有先修和模块。
 * 仍然用规则而不是模型，因为「课程代码 + 学分 + 分数」是强格式。
 * 如果成绩单是扫描件，这里会抽不到东西，需要 OCR 或人工录入。
 */
@Component
public class TranscriptExtractor {

    private static final Pattern CODE = Pattern.compile("^([A-Z]{2,6}\\d{2,4})$");
    private static final Pattern NUMBER = Pattern.compile("^(\\d{1,3}(\\.\\d)?)$");
    private static final Pattern TERM = Pattern.compile("(20\\d{2})[-/年]?(0?[1-9]|1[0-2])");

    public record Entry(String code, String name, double credits, Double score, String term) {
    }

    public List<Entry> extract(String text) {
        List<Entry> result = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            String line = raw.replace('\u3000', ' ').trim();
            if (line.isEmpty()) {
                continue;
            }
            Entry e = parseLine(line);
            if (e != null) {
                result.add(e);
            }
        }
        return result;
    }

    private Entry parseLine(String line) {
        String[] tokens = line.split("\\s+");
        if (tokens.length < 3) {
            return null;
        }
        int codeIdx = -1;
        for (int i = 0; i < tokens.length; i++) {
            String t = tokens[i].toUpperCase(Locale.ROOT);
            if (CODE.matcher(t).matches()) {
                codeIdx = i;
                break;
            }
        }
        if (codeIdx < 0) {
            return null;
        }

        List<Double> numbers = new ArrayList<>();
        List<Integer> numberIdx = new ArrayList<>();
        for (int i = codeIdx + 1; i < tokens.length; i++) {
            Matcher m = NUMBER.matcher(tokens[i]);
            if (m.matches()) {
                numbers.add(Double.parseDouble(m.group(1)));
                numberIdx.add(i);
            }
        }
        if (numbers.isEmpty()) {
            return null;
        }

        // 第一段数字通常是学分，第二段是成绩（成绩可能缺失）
        double credits = numbers.get(0);
        if (credits <= 0 || credits > 12) {
            return null;
        }
        Double score = null;
        if (numbers.size() >= 2 && numbers.get(1) <= 100) {
            score = numbers.get(1);
        }

        int nameEnd = numberIdx.get(0);
        StringBuilder name = new StringBuilder();
        for (int i = codeIdx + 1; i < nameEnd; i++) {
            name.append(tokens[i]);
        }
        if (name.isEmpty()) {
            return null;
        }

        String term = null;
        Matcher tm = TERM.matcher(line);
        if (tm.find()) {
            term = tm.group(1) + "-" + (tm.group(2).length() == 1 ? "0" + tm.group(2) : tm.group(2));
        }
        return new Entry(tokens[codeIdx].toUpperCase(Locale.ROOT), name.toString(), credits, score, term);
    }
}
