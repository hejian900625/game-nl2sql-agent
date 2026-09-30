package io.github.hejian.gamenl2sql.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * §9 的启动 fail-fast：配置残缺时 Model bean 建立即失败，进程拒绝启动，
 * 而不是静默回落到 starter 的 gpt-4.1-mini 打到别处。这里只验装配期，不联网。
 */
class ModelConfigFailFastTest {

    private final ModelConfig config = new ModelConfig();

    @Test
    void rejectsMissingApiKey() {
        assertThatThrownBy(() -> config.model(new ModelProperties("deepseek-flash", "", true, false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DEEPSEEK_API_KEY");
    }

    @Test
    void rejectsMissingModelName() {
        assertThatThrownBy(() -> config.model(new ModelProperties(" ", "placeholder", true, false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("game.model.name");
    }

    @Test
    void rejectsStarterDefaultModelName() {
        assertThatThrownBy(() -> config.model(new ModelProperties("gpt-4.1-mini", "placeholder", true, false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("gpt-4.1-mini");
    }
}
