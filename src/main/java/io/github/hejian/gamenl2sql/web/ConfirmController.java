package io.github.hejian.gamenl2sql.web;

import io.github.hejian.gamenl2sql.hitl.ConfirmationGate;
import io.github.hejian.gamenl2sql.hitl.Decision;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * AG-UI 之外的自加端点（PLAN §9）：agent 的问答走 /agui/run，人的处置走这里。
 * 分开的理由——确认门在工具内部挂起，走的是另一条长连接，不能指望模型那一侧把它带过去。
 */
@RestController
@RequestMapping("/api/confirm")
public class ConfirmController {

    /** 人的处置（前端提交）。sql 非空即"我改过了"。 */
    public record Verdict(boolean approved, String sql, String reason) {
    }

    private final ConfirmationGate gate;

    public ConfirmController(ConfirmationGate gate) {
        this.gate = gate;
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ConfirmationGate.Request> stream() {
        return gate.notifications();
    }

    @GetMapping("/pending")
    public Flux<ConfirmationGate.Request> pending() {
        return Flux.fromIterable(gate.pendingSnapshot());
    }

    @PostMapping("/{id}")
    public Mono<ResponseEntity<String>> decide(@PathVariable String id, @RequestBody Verdict verdict) {
        Decision decision = verdict.approved()
                ? (verdict.sql() == null || verdict.sql().isBlank() ? Decision.approve() : Decision.edit(verdict.sql()))
                : Decision.deny(verdict.reason());
        boolean accepted = gate.decide(id, decision);
        return Mono.just(accepted
                ? ResponseEntity.ok("已记录处置 " + id)
                : ResponseEntity.status(410).body("这条确认已经结束（超时或已处置过）"));
    }
}
