package com.courseplanner.common;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 培养方案原文检索：中文按二字组重合度打分。
 * 粗糙但零依赖、可解释；Agent 工具和 HTTP 接口共用同一份实现，避免两边结果不一致。
 */
public final class TextSearch {

    private TextSearch() {
    }

    public static List<String> search(String text, String keyword, int limit) {
        if (text == null || text.isBlank() || keyword == null || keyword.isBlank()) {
            return List.of();
        }
        Set<String> q = bigrams(keyword);
        List<String> lines = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            String line = raw.trim();
            if (line.length() >= 4 && line.length() <= 160) {
                lines.add(line);
            }
        }
        record Scored(String line, int score) {
        }
        List<Scored> scored = new ArrayList<>();
        for (String line : lines) {
            Set<String> lb = bigrams(line);
            int hit = 0;
            for (String g : q) {
                if (lb.contains(g)) {
                    hit++;
                }
            }
            if (hit > 0) {
                scored.add(new Scored(line, hit));
            }
        }
        scored.sort((a, b) -> Integer.compare(b.score(), a.score()));
        return scored.stream().limit(limit).map(Scored::line).toList();
    }

    private static Set<String> bigrams(String s) {
        String cleaned = s.replaceAll("[\\s，。、；：？！,.;:?!()（）「」【】\"']", "");
        Set<String> out = new LinkedHashSet<>();
        for (int i = 0; i + 2 <= cleaned.length(); i++) {
            out.add(cleaned.substring(i, i + 2));
        }
        return out;
    }
}
