package io.github.hejian.gamenl2sql;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Day-0 门禁④附加项：证明框架的 DeepSeek SPI 路径真的能调通。
 * 无 DEEPSEEK_API_KEY 时跳过（CI 不持有 key）。
 */
class DeepSeekModelSmokeTest {

    @Test
    void resolvesPrefixedModelIdAndAnswers() {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "无 DEEPSEEK_API_KEY，跳过联网探活");

        Model model = ModelRegistry.resolve("deepseek:deepseek-flash",
                ModelCreationContext.builder()
                        .apiKey(apiKey)
                        .stream(false)
                        .enableThinking(false)
                        .build());

        assertThat(model.getModelName()).isEqualTo("deepseek-flash");
        assertThat(model.supportsNativeStructuredOutput()).isFalse();

        List<ChatResponse> responses = model.stream(
                List.of(Msg.builder().role(MsgRole.USER).textContent("只回答一个阿拉伯数字：1+1=").build()),
                List.of(),
                GenerateOptions.builder().build())
                .collectList()
                .block(Duration.ofSeconds(60));

        assertThat(responses).isNotEmpty();
        String text = responses.get(responses.size() - 1).getContent().stream()
                .filter(TextBlock.class::isInstance)
                .map(b -> ((TextBlock) b).getText())
                .reduce("", (a, b) -> a + b);
        System.out.println("[gate4] DeepSeek 返回: " + text.trim()
                + " | finishReason=" + responses.get(responses.size() - 1).getFinishReason()
                + " | contextWindow=" + model.getContextWindowSize());
        assertThat(text).contains("2");
    }
}
