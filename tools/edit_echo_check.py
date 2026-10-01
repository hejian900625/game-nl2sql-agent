"""一次性的缺陷复验：人编辑 SQL 后，模型的答复必须抄「执行 SQL」而不是自己原来那条。

不进 CI：要打真实模型 + 真实确认门。用法：先以 human 模式起服务，再 python tools/edit_echo_check.py <port>
"""
import json
import sys
import threading
import time
import urllib.request

# Windows 控制台是 GBK，答复里的中文和符号会炸 stdout；统一改 UTF-8 并落盘一份。
sys.stdout.reconfigure(encoding="utf-8", errors="replace")
BASE = "http://localhost:%s" % (sys.argv[1] if len(sys.argv) > 1 else 18084)
QUESTION = "1月31日当天，登录次数大于3次的账号有多少个？"
EDIT_FROM = "> 3"
EDIT_TO = "> 5"


def post(path, payload):
    req = urllib.request.Request(
        BASE + path,
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json", "Accept": "text/event-stream"},
        method="POST",
    )
    return urllib.request.urlopen(req, timeout=180)


def get(path):
    with urllib.request.urlopen(BASE + path, timeout=30) as r:
        return json.loads(r.read().decode("utf-8"))


deltas = []
done = threading.Event()
run_error = []


def stream_run():
    body = {
        # 每次跑用新 threadId：服务端会话按 threadId 攒上下文，复用会看见上一轮的 SQL 和数字。
        "threadId": "t-edit-echo-%d" % int(time.time()),
        "runId": "r1",
        "messages": [{"id": "m1", "role": "user", "content": QUESTION}],
        "tools": [],
        "context": [],
        "state": {},
        "forwardedProps": {},
    }
    try:
        with post("/agui/run", body) as resp:
            for raw in resp:
                line = raw.decode("utf-8", "replace").strip()
                if not line.startswith("data:"):
                    continue
                try:
                    evt = json.loads(line[5:].strip())
                except json.JSONDecodeError:
                    continue
                if evt.get("type") == "TEXT_MESSAGE_CONTENT":
                    deltas.append(evt.get("delta", ""))
                elif evt.get("type") in ("RUN_FINISHED", "RUN_ERROR"):
                    if evt.get("type") == "RUN_ERROR":
                        run_error.append(json.dumps(evt, ensure_ascii=False))
                    done.set()
    except Exception as exc:  # noqa: BLE001
        run_error.append(repr(exc))
        done.set()


t = threading.Thread(target=stream_run, daemon=True)
t.start()

pending = []
for _ in range(120):
    pending = get("/api/confirm/pending")
    if pending:
        break
    time.sleep(0.5)
if not pending:
    print("FAIL: 60 秒内没有出现待确认项（human 模式没生效？）")
    sys.exit(1)

req = pending[0]
original = req["sql"]
edited = original.replace(EDIT_FROM, EDIT_TO)
if edited == original:
    print("FAIL: 无法构造编辑，原 SQL 不含 %r：%s" % (EDIT_FROM, original))
    sys.exit(1)

print("待确认 SQL : %s" % original)
post("/api/confirm/%s" % req["id"], {"approved": True, "sql": edited, "reason": None})
print("人已改写为 : %s" % edited)

done.wait(timeout=180)
answer = "".join(deltas)
with open("target/edit-echo-answer.md", "w", encoding="utf-8") as fh:
    fh.write("原 SQL: %s\n\n人改写为: %s\n\n模型答复:\n\n%s\n" % (original, edited, answer))
print("\n--- 模型答复 ---\n%s\n----------------" % answer)

if run_error:
    print("RESULT: FAIL，运行报错 %s" % run_error)
    sys.exit(1)

echoes_edited = EDIT_TO in answer
echoes_original = EDIT_FROM in answer and not echoes_edited
print("\n期望答复里出现 %r（实际执行的那条），不出现 %r" % (EDIT_TO, EDIT_FROM))
print("RESULT: %s" % ("PASS" if echoes_edited and not echoes_original else "FAIL"))
if echoes_original:
    print("缺陷仍在：答复抄回了人编辑前的原 SQL")
sys.exit(0 if echoes_edited else 1)
