package com.courseplanner.qa.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** extract 是纯规则，不需要 Spring 上下文。 */
class MemoryServiceMemoryTest {

    private final MemoryService service = new MemoryService(null);

    @Test
    void extractsGraduationPreference() {
        List<MemoryService.Memory> found = service.extract("我想早点毕业，怎么排课？");
        assertThat(found).extracting(MemoryService.Memory::key).containsExactly("pref:graduation");
    }

    @Test
    void extractsMultiplePreferences() {
        List<MemoryService.Memory> found = service.extract("我想早点毕业，而且别排早八，最好压力小一点");
        assertThat(found).extracting(MemoryService.Memory::key)
                .contains("pref:graduation", "pref:morning", "pref:load");
    }

    @Test
    void ignoresOrdinaryQuestions() {
        assertThat(service.extract("我还差多少学分？")).isEmpty();
        assertThat(service.extract("")).isEmpty();
        assertThat(service.extract(null)).isEmpty();
    }

    @Test
    void doesNotMistakePositiveMorningForPreference() {
        assertThat(service.extract("早八几点上课？")).isEmpty();
    }
}
