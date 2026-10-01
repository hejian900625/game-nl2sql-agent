package io.github.hejian.gamenl2sql.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * README 让人把 key 写进 .env，所以 .env 必须真有人读 —— Spring Boot 不自动读它，是
 * application.yml 里那行 spring.config.import 在读。这三条锁的是那行配置的机制与优先级。
 * <p>
 * 探针用的键名是本项目独有的，且不断言 {@code DEEPSEEK_API_KEY} 的值 —— 失败信息会把解析到的
 * 值原样打出来，那台机器上项目根的 .env 里是真 key（2026-10-01 就这么漏过一次进 surefire 报告）。
 */
class EnvFileImportTest {

    /** 占位符解析按字面名查（不做 relaxed binding），所以这里必须用 .env 里那个大写原名。 */
    private static final String PROBE_KEY = "GAME_DOTENV_PROBE_KEY";

    @TempDir
    Path tmp;

    private ApplicationContextRunner runnerWithImport(String location) {
        return new ApplicationContextRunner()
                .withPropertyValues("spring.config.import=" + location)
                .withInitializer(ctx -> ConfigDataEnvironmentPostProcessor.applyTo(ctx.getEnvironment()));
    }

    private Path writeDotEnv(String value) throws IOException {
        Path envFile = tmp.resolve(".env");
        Files.writeString(envFile, "GAME_DOTENV_PROBE_KEY=" + value + "\nANOTHER=1\n");
        return envFile;
    }

    @Test
    void dotenvStyleFileIsParsedAsKeyEqualsValue() throws IOException {
        Path envFile = writeDotEnv("from-dotenv-file");

        runnerWithImport("optional:file:" + envFile + "[.properties]").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(ConfigurableEnvironment.class).getProperty(PROBE_KEY))
                    .isEqualTo("from-dotenv-file");
        });
    }

    /** 真实环境变量必须压过 .env：换 key 与"CI 只导出变量"的用法都靠 export，不能被一份 .env 悄悄顶掉。 */
    @Test
    void systemEnvironmentRanksAboveEveryConfigDataFile() throws IOException {
        Path envFile = writeDotEnv("from-dotenv-file");

        runnerWithImport("optional:file:" + envFile + "[.properties]").run(ctx -> {
            ConfigurableEnvironment env = ctx.getBean(ConfigurableEnvironment.class);
            int systemEnv = -1;
            int firstConfigData = -1;
            int i = 0;
            for (PropertySource<?> source : env.getPropertySources()) {
                String name = source.getName();
                if ("systemEnvironment".equals(name)) {
                    systemEnv = i;
                } else if (name.startsWith("Config resource") && firstConfigData < 0) {
                    firstConfigData = i;
                }
                i++;
            }
            assertThat(systemEnv).as("systemEnvironment 必须在属性源列表里").isNotNegative();
            assertThat(firstConfigData).as("config data 文件（含 .env）必须被导入").isNotNegative();
            assertThat(systemEnv).as("环境变量优先级高于 .env（列表越靠前越优先）").isLessThan(firstConfigData);
        });
    }

    /** optional: 的意义 —— 没有 .env 也要能起（CI 与只 export 变量的用法）。 */
    @Test
    void missingDotenvFileDoesNotBreakStartup() {
        runnerWithImport("optional:file:" + tmp.resolve("nope/.env") + "[.properties]").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(ConfigurableEnvironment.class).getPropertySources())
                    .noneMatch(source -> source.getName().contains("nope"));
        });
    }
}
