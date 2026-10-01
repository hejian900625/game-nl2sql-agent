# game-nl2sql

[中文](README.md) | [English](README.en.md)

[![CI](https://github.com/hejian900625/game-nl2sql-agent/actions/workflows/ci.yml/badge.svg)](https://github.com/hejian900625/game-nl2sql-agent/actions/workflows/ci.yml)

A read-only natural-language-to-SQL agent built on **AgentScope Java 2.0.3** + **DeepSeek**, with a real guardrail stack and a human approval gate. Chinese-language project; this file is the short version — see [README.md](README.md) for the design notes and the honest list of known issues.

![a human edits the SQL before it runs](docs/demo.gif)

What makes it more than an API wrapper:

- **Four-step guardrail** (`guard/SqlGuard`): JSqlParser AST → table allowlist → `LIMIT` injection → read-only JDBC connection. No regex, no `startsWith("SELECT")`.
- **Suspended approval gate** (`hitl/ConfirmationGate`): the pause happens *inside* the `run_sql` tool as a long-pending reactive stream, so one agent run stays one continuous loop. A timeout counts as a denial. Humans may edit the SQL, and the answer quotes the statement that actually ran.
- **25 hand-written eval questions** with declared calibers; every expected number is recomputed in SQL (`tools/verify_eval.py`), none typed by hand.

## Quick start

Needs JDK 21 and Maven 3.9+.

```bash
git clone https://github.com/hejian900625/game-nl2sql-agent.git && cd game-nl2sql-agent
cp .env.example .env      # set DEEPSEEK_API_KEY
mvn spring-boot:run       # data/game.db is generated from schema + seed on first boot
```

Open <http://localhost:8080>.

Without an API key you still get 87 of the 88 tests (`mvn test`): guardrail, database build, approve/edit/deny/timeout paths, prompt content, AG-UI routing and thread-session isolation, eval scoring arithmetic, the two-layer probe of the framework's native pause/resume (#3096), and the measured DeepSeek model-id / context-window facts.

## Accuracy

`deepseek-flash`, thinking off, same database and same 25 questions; the only variable is model sampling. Strict = expected value found in a result cell; Lenient = also counting answers that state it in prose.

| Run | Prompt | Strict | Lenient | Missed |
|---|---|---|---|---|
| `eval-20261001-102834` | v1 | 88.0% (22/25) | 92.0% (23/25) | B03, B05, B07 |
| `eval-20261001-103058` | v1 | 88.0% (22/25) | 92.0% (23/25) | B04, B05, B07 |
| `eval-20261001-113017` | v2 | 84.0% (21/25) | 88.0% (22/25) | A03, B03, B05, B07 |

Two v1 runs score the same 22/25 while missing *different* questions, so a single run's total proves nothing; compare which questions flipped. B07 fails every run and is a defect in the question itself (its text contradicts its own caliber declaration).

Reproduce:

```bash
mvn spring-boot:run -Dspring-boot.run.arguments=--eval \
  -Dspring-boot.run.jvmArguments="-Dgame.confirm.mode=auto_approve"
```

## License

Apache-2.0 — see [LICENSE](LICENSE). `static/index.html` and `static/js/agui-client.js` are adapted from the official AgentScope `examples/agui` page with their original copyright headers intact; the adaptation removes the example's frontend `request_approval` tool and replaces it with this project's own approval card.
