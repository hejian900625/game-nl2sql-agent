package io.github.hejian.gamenl2sql.guard;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** SqlGuard 保持不带注解的纯类，才谈得上"可离线测"（§9）。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GuardProperties.class)
public class GuardConfig {

    @Bean
    SqlGuard sqlGuard(GuardProperties props) {
        if (props.maxRows() <= 0) {
            throw new IllegalStateException("game.guard.max-rows 必须是正数，当前=" + props.maxRows());
        }
        return new SqlGuard(props.maxRows());
    }
}
