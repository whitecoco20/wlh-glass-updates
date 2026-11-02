"""Fetch Google News world headlines in WLH Glass's 13 languages and write news/<lang>.json.
Run by .github/workflows/news.yml. WLH Glass Web reads the files (browsers can't read Google News directly)."""
import json, os, re, html, urllib.request, time
LOC = {"en": ("en-US", "US", "US:en"), "es": ("es-419", "US", "US:es-419"), "pt": ("pt-BR", "BR", "BR:pt-419"), "fr": ("fr", "FR", "FR:fr"),
       "de": ("de", "DE", "DE:de"), "it": ("it", "IT", "IT:it"), "nl": ("nl", "NL", "NL:nl"), "pl": ("pl", "PL", "PL:pl"), "tr": ("tr", "TR", "TR:tr"),
       "ru": ("ru", "RU", "RU:ru"), "zh": ("zh-CN", "CN", "CN:zh-Hans"), "ja": ("ja", "JP", "JP:ja"), "hi": ("hi", "IN", "IN:hi")}
os.makedirs("out", exist_ok=True)
for code, (hl, gl, ceid) in LOC.items():
    url = f"https://news.google.com/rss/headlines/section/topic/WORLD?hl={hl}&gl={gl}&ceid={ceid}"
    try:
        x = urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 WLH-Glass"}), timeout=20).read().decode("utf-8", "replace")
    except Exception as e:
        print(code, "failed", e); continue
    items = []
    for t in re.findall(r"<item>.*?<title>(.*?)</title>", x, re.S)[:20]:
        t = html.unescape(t.replace("<![CDATA[", "").replace("]]>", "")).strip()
        i = t.rfind(" - ")
        if 0 < i and 0 < len(t) - i - 3 <= 50: t = t[i + 3:].strip() + "  ·  " + t[:i].strip()
        if t: items.append(t)
    if items:
        json.dump({"at": int(time.time()), "items": items}, open(f"out/{code}.json", "w", encoding="utf-8"), ensure_ascii=False)
    print(code, len(items))
