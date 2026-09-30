package io.github.hejian.gamenl2sql.hitl;

import io.github.hejian.gamenl2sql.hitl.ConfirmProperties.Mode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具内挂起式确认门（PLAN §4 决定 D）。
 *
 * <p>agent 的一次 run 不因确认而中断：{@code run_sql} 把 SQL 推给前端，然后在自己的
 * {@link Sinks.One} 上等着。没有用框架原生 permission + resume —— 那条路径上的 #3096/#3104
 * 在 2.0.3 还没修好。
 *
 * <p>这里是纯响应式，不占线程；执行 SQL 的阻塞在 {@code RunSqlTool} 里另切线程池。
 */
@Component
@EnableConfigurationProperties(ConfirmProperties.class)
public class ConfirmationGate {

    private static final Logger log = LoggerFactory.getLogger(ConfirmationGate.class);

    /** 推给前端的一条待确认请求。 */
    public record Request(String id, String sql) {
    }

    private record Pending(Sinks.One<Decision> sink, String sql) {
    }

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final Sinks.Many<Request> requests = Sinks.many().multicast().onBackpressureBuffer();
    private final int timeoutSeconds;
    private final Mode mode;

    public ConfirmationGate(ConfirmProperties props) {
        if (props.timeoutSeconds() <= 0) {
            throw new IllegalStateException("game.confirm.timeout-seconds 必须是正数，当前=" + props.timeoutSeconds());
        }
        this.timeoutSeconds = props.timeoutSeconds();
        this.mode = props.mode();
    }

    /** 前端 SSE 流：谁先订阅谁收到，页面没开时就只能等超时（W2 接 demo 页时再定回放策略）。 */
    public Flux<Request> notifications() {
        return requests.asFlux();
    }

    public List<Request> pendingSnapshot() {
        return pending.entrySet().stream().map(e -> new Request(e.getKey(), e.getValue().sql())).toList();
    }

    /**
     * 挂起等人处置。订阅时才登记，所以不会被"组装了但没跑"的流污染。
     * 超时、被取消、被拒绝都会把条目清掉，人再来点就得到 410。
     */
    public Mono<Decision> await(String sql) {
        if (mode == Mode.AUTO_APPROVE) {
            log.info("[confirm] 自动放行（{}）: {}", mode, sql);
            return Mono.just(Decision.approve());
        }
        return Mono.defer(() -> {
            String id = UUID.randomUUID().toString();
            Sinks.One<Decision> sink = Sinks.one();
            pending.put(id, new Pending(sink, sql));
            requests.tryEmitNext(new Request(id, sql));
            log.info("[confirm] 等人确认 {} : {}", id, sql);
            return sink.asMono()
                    .timeout(Duration.ofSeconds(timeoutSeconds),
                            Mono.fromSupplier(() -> Decision.timeout(timeoutSeconds)))
                    .doFinally(signal -> pending.remove(id));
        });
    }

    /** @return false 表示这个 id 已经不在了（超时/已决/根本不存在） */
    public boolean decide(String id, Decision decision) {
        Pending removed = pending.remove(id);
        if (removed == null) {
            return false;
        }
        removed.sink().tryEmitValue(decision);
        log.info("[confirm] 收到处置 {} approved={} edited={}", id, decision.approved(), decision.edited());
        return true;
    }

    public Mode mode() {
        return mode;
    }
}
