package com.courseplanner.qa.agent;

import com.courseplanner.config.AppProperties;
import com.courseplanner.extract.LlmCallLogRepository;
import com.courseplanner.extract.LlmClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** AgentLoop 的控制流：工具复用检测、非法 JSON 重试、轮数收束。不调真实模型。 */
class AgentLoopTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** 计数的假工具。 */
    private static final class CountingTool implements AgentTool {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public String name() {
            return "get_progress";
        }

        @Override
        public String description() {
            return "查学分";
        }

        @Override
        public String argsHint() {
            return "无";
        }

        @Override
        public JsonNode execute(long userId, long programId, JsonNode args) {
            calls.incrementAndGet();
            ObjectNode node = new ObjectMapper().createObjectNode();
            node.put("remaining", 62);
            return node;
        }
    }

    private AgentLoop loop(CountingTool tool, LlmClient client) {
        @SuppressWarnings("unchecked")
        ObjectProvider<LlmClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        AppProperties props = new AppProperties();
        props.getQa().setMaxSteps(3);
        return new AgentLoop(new ToolRegistry(List.of(tool)), provider,
                mock(LlmCallLogRepository.class), props, mapper);
    }

    private LlmClient plannerThenAnswer(String plannerOutput, String answer) {
        return request -> request.jsonMode()
                ? new LlmClient.Result(plannerOutput, 10, 5, 1, "fake")
                : new LlmClient.Result(answer, 10, 5, 1, "fake");
    }

    @Test
    void stopsWhenModelRepeatsSameToolWithSameArgs() {
        CountingTool tool = new CountingTool();
        String plan = "{\"thought\":\"查一下\",\"tool\":\"get_progress\",\"args\":{}}";
        AgentLoop loop = loop(tool, plannerThenAnswer(plan, "还差 62 学分"));

        AgentLoop.LoopResult result = loop.run(1L, 1L, "我还差多少学分", List.of());

        assertThat(tool.calls.get()).isEqualTo(1);
        assertThat(result.steps()).hasSize(1);
        assertThat(result.text()).isEqualTo("还差 62 学分");
        assertThat(result.engine()).isEqualTo("agent:fake");
    }

    @Test
    void unknownToolIsFedBackInsteadOfThrowing() {
        CountingTool tool = new CountingTool();
        String bad = "{\"thought\":\"猜的\",\"tool\":\"drop_table\",\"args\":{}}";
        AgentLoop loop = loop(tool, plannerThenAnswer(bad, "培养方案里没有写"));

        AgentLoop.LoopResult result = loop.run(1L, 1L, "随便问问", List.of());

        assertThat(tool.calls.get()).isZero();
        assertThat(result.steps()).isEmpty();
        assertThat(result.text()).isEqualTo("培养方案里没有写");
    }

    @Test
    void givesUpAfterTwoInvalidJsonOutputs() {
        CountingTool tool = new CountingTool();
        AgentLoop loop = loop(tool, plannerThenAnswer("不是 JSON", "抱歉，我暂时查不到"));

        AgentLoop.LoopResult result = loop.run(1L, 1L, "帮我看看", List.of());

        assertThat(tool.calls.get()).isZero();
        assertThat(result.text()).isEqualTo("抱歉，我暂时查不到");
    }
}
