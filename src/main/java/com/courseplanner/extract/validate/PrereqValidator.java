package com.courseplanner.extract.validate;

import com.courseplanner.program.ProgramDraft;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 先修关系最容易出错：模型会编出不存在的课程代码，也会造出环。
 * 环会让求解器永远找不到解，所以必须在入库前打断它。
 */
@Component
@Order(3)
public class PrereqValidator implements DraftValidator {

    @Override
    public String name() {
        return "先修校验";
    }

    @Override
    public ProgramDraft validate(ProgramDraft draft, List<String> warnings) {
        Set<String> codes = new HashSet<>();
        for (ProgramDraft.CourseDraft c : draft.courses()) {
            codes.add(c.code());
        }

        Map<String, List<String>> graph = new HashMap<>();
        for (ProgramDraft.CourseDraft c : draft.courses()) {
            graph.put(c.code(), new ArrayList<>());
        }

        List<ProgramDraft.CourseDraft> result = new ArrayList<>(draft.courses().size());
        int missing = 0;
        int cyclic = 0;
        for (ProgramDraft.CourseDraft c : draft.courses()) {
            List<String> kept = new ArrayList<>();
            for (String pre : c.prereqs()) {
                String p = pre.toUpperCase();
                if (p.equals(c.code())) {
                    cyclic++;
                    continue;
                }
                if (!codes.contains(p)) {
                    missing++;
                    continue;
                }
                // 加之前先试：如果这条边制造了环，就不加
                graph.get(p).add(c.code());
                if (hasCycle(graph)) {
                    graph.get(p).remove(graph.get(p).size() - 1);
                    cyclic++;
                    continue;
                }
                kept.add(p);
            }
            result.add(new ProgramDraft.CourseDraft(c.code(), c.name(), c.credits(), c.module(),
                    c.type(), c.terms(), kept));
        }
        if (missing > 0) {
            warnings.add("移除 " + missing + " 条指向不存在课程的先修关系");
        }
        if (cyclic > 0) {
            warnings.add("移除 " + cyclic + " 条会形成环/自引用的先修关系");
        }
        return new ProgramDraft(draft.school(), draft.major(), draft.gradeYear(),
                draft.totalCredits(), result, draft.modules());
    }

    /** 0=未访问 1=访问中 2=已完成；碰到「访问中」说明有环。 */
    private static boolean hasCycle(Map<String, List<String>> graph) {
        Map<String, Integer> state = new HashMap<>();
        for (String node : graph.keySet()) {
            if (dfs(node, graph, state)) {
                return true;
            }
        }
        return false;
    }

    private static boolean dfs(String node, Map<String, List<String>> graph, Map<String, Integer> state) {
        Integer s = state.get(node);
        if (s != null && s == 1) {
            return true;
        }
        if (s != null && s == 2) {
            return false;
        }
        state.put(node, 1);
        for (String next : graph.getOrDefault(node, List.of())) {
            if (dfs(next, graph, state)) {
                return true;
            }
        }
        state.put(node, 2);
        return false;
    }
}
