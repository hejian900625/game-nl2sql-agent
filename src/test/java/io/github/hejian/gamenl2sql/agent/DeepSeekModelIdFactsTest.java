package io.github.hejian.gamenl2sql.agent;

import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelContextWindows;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DeepSeek 模型 ID 这条上游 note 的**看门断言**（结论与实测过程见
 * {@code docs/upstream-deepseek-model-ids.md}）。全程离线 —— {@code resolve} 只构造对象，不打网络。
 *
 * <p>为什么把这些钉成测试而不是只写在文档里：这是"上游修好了我们该知道"的那类事实。
 * 一旦 {@code ModelContextWindows.DEEPSEEK} 补上当前正式名 {@code deepseek-flash}，
 * 下面两条会翻红，那就是让我们去删掉这条 note 的信号（跟 #3100 那两条 {@code noneMatch} 同理）。
 *
 * <p>顺带钉住一个我们自己踩过的错：{@code deepseek-v4-flash} 并没有 400，DeepSeek 把它当
 * {@code deepseek-flash} 的别名收下了（2026-10-01 用真 key 实测，见上面文档）。表里有它、
 * 没有正式名，所以照抄框架表里的名字反而看不出问题。
 */
class DeepSeekModelIdFactsTest {

    @Test
    void frameworkTableMissingTheCurrentCanonicalFlashId() {
        assertThat(ModelContextWindows.DEEPSEEK)
                .as("框架 2.0.3 的表停在旧命名")
                .containsKeys("deepseek-v4-flash", "deepseek-v4-pro");
        assertThat(ModelContextWindows.DEEPSEEK)
                .as("DeepSeek 自己 /models 返回的正式名不在表里")
                .doesNotContainKey("deepseek-flash");
    }

    @Test
    void canonicalIdResolvesWithZeroContextWindowWhileAliasReportsOneMillion() {
        assertThat(window("deepseek-flash")).as("正式名查不到窗口").isZero();
        assertThat(window("deepseek-v4-flash")).as("别名有 1M，且 provider 也收这个别名").isEqualTo(1_000_000);
    }

    /** 与 {@code ModelConfig} 同一条 SPI 路径；key 是假的，构造模型不需要真凭证。 */
    private static int window(String name) {
        Model model = ModelRegistry.resolve("deepseek:" + name, ModelCreationContext.builder()
                .apiKey("sk-placeholder-never-sent")
                .enableThinking(false)
                .build());
        return model.getContextWindowSize();
    }
}
