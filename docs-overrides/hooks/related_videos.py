import html
import json
import re
from pathlib import Path

REGISTRY = json.loads((Path(__file__).parent / "videos.json").read_text(encoding="utf-8"))
HEADING = re.compile(r"^# .+$", re.MULTILINE)


def video_keys(page):
    raw = page.meta.get("videos")
    if not raw:
        return []
    if isinstance(raw, str):
        raw = [raw]
    return [key for item in raw for key in re.split(r"[\s,]+", str(item)) if key]


def card(key, depth):
    video = REGISTRY[key]
    prefix = "../" * depth
    return (
        f'<a class="related-video" href="https://youtu.be/{video["id"]}" target="_blank" rel="noopener">'
        f'<img src="{prefix}assets/images/videos/{key}.jpg" alt="" width="320" height="180" loading="lazy">'
        f'<span class="related-video__text"><span class="related-video__kicker">{html.escape(video["kicker"])}</span>'
        f'<span class="related-video__title">{html.escape(video["title"])}</span></span></a>'
    )


def on_page_markdown(markdown, page, config, files):
    keys = video_keys(page)
    if not keys:
        return markdown
    unknown = [key for key in keys if key not in REGISTRY]
    if unknown:
        raise ValueError(f"{page.file.src_path}: unknown video key {unknown}, see docs-overrides/hooks/videos.json")
    depth = page.file.url.rstrip("/").count("/") + 1 if page.file.url else 0
    cards = "".join(card(key, depth) for key in keys)
    label = "Related video" if len(keys) == 1 else "Related videos"
    block = (
        f'\n\n<div class="related-videos" markdown="0"><p class="related-videos__label">{label}</p>'
        f'<div class="related-videos__list">{cards}</div></div>\n'
    )
    match = HEADING.search(markdown)
    if not match:
        return markdown
    return markdown[:match.end()] + block + markdown[match.end():]
