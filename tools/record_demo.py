"""录 §2 验收项 3 那一帧：人在页面上把 SQL 改了再执行。

用系统里已有的 Edge（Playwright channel=msedge），不下浏览器。截图是真的页面，不是画出来的示意图。

    先起服务：mvn -o -B spring-boot:run -Dspring-boot.run.jvmArguments="-Dserver.port=18085 -Dgame.confirm.mode=human"
    再跑：    python tools/record_demo.py 18085
产物：target/demo-frames/*.png 与 docs/demo.gif
"""
import sys
from pathlib import Path

from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parent.parent
FRAMES = ROOT / "target" / "demo-frames"
GIF = ROOT / "docs" / "demo.gif"

PORT = sys.argv[1] if len(sys.argv) > 1 else "18085"
BASE = "http://localhost:%s/" % PORT
QUESTION = "1月31日当天，登录次数大于3次的账号有多少个？"
EDIT_FROM, EDIT_TO = "> 3", "> 5"
FRAME_MS = [1400, 1800, 1800, 3200]


def shot(page, name):
    path = FRAMES / name
    page.screenshot(path=str(path))
    print("captured %s" % path.name)
    return path


def main():
    FRAMES.mkdir(parents=True, exist_ok=True)
    with sync_playwright() as pw:
        browser = pw.chromium.launch(channel="msedge", headless=True)
        page = browser.new_page(viewport={"width": 940, "height": 800}, locale="zh-CN")
        page.goto(BASE, wait_until="networkidle")
        page.wait_for_selector("#thread-chip")

        page.fill("#input", QUESTION)
        f1 = shot(page, "01-question.png")
        page.press("#input", "Enter")

        page.wait_for_selector("textarea.confirm-sql", timeout=120_000)
        f2 = shot(page, "02-confirm-card.png")

        area = page.locator("textarea.confirm-sql").first
        sql = area.input_value()
        if EDIT_FROM not in sql:
            raise SystemExit("确认卡片里的 SQL 不含 %r，换一道题或改脚本：%s" % (EDIT_FROM, sql))
        area.fill(sql.replace(EDIT_FROM, EDIT_TO))
        f3 = shot(page, "03-edited-by-human.png")

        page.locator("button.approve").first.click()
        page.wait_for_function(
            """() => document.getElementById('status-text').textContent === 'Ready'
                 && document.getElementById('messages').innerText.includes('口径')""",
            timeout=120_000)
        f4 = shot(page, "04-answer.png")
        browser.close()

    from PIL import Image

    images = []
    for path in (f1, f2, f3, f4):
        img = Image.open(path).convert("RGB")
        img.thumbnail((760, 760 * img.height // img.width))
        images.append(img)
    GIF.parent.mkdir(exist_ok=True)
    images[0].save(
        GIF, save_all=True, append_images=images[1:], duration=FRAME_MS, loop=0, optimize=True, disposal=2)
    print("wrote %s (%d bytes)" % (GIF, GIF.stat().st_size))


if __name__ == "__main__":
    main()
