#!/usr/bin/env python3
"""Download the Android APK of a free itch.io game the way the page's Download button does.
   itch-get.py <game page url> <out dir>   (prints the saved path)"""
import http.cookiejar, json, os, re, sys, urllib.parse, urllib.request

page, out = sys.argv[1], sys.argv[2]
jar = http.cookiejar.CookieJar()
op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
op.addheaders = [("User-Agent", "Mozilla/5.0 (XR Store test)")]
html = op.open(page, timeout=30).read().decode("utf-8", "replace")
uploads = re.findall(r'data-upload_id="(\d+)".*?class="name">([^<]+)<.*?class="download_platforms">(.*?)</span>\s*</div>', html, re.S)
apks = [(u, n) for u, n, plats in uploads if "icon-android" in plats or n.lower().endswith(".apk")]
if not apks:
    sys.exit("no free Android upload on " + page)
upload, name = apks[0]
csrf = re.search(r'name="csrf_token" value="([^"]+)"', html)
csrf = csrf.group(1) if csrf else next((c.value for c in jar if c.name == "itchio_token"), "")
base = page.rstrip("/")
data = urllib.parse.urlencode({"csrf_token": csrf}).encode()
r = json.load(op.open(f"{base}/file/{upload}?source=view_game&as_props=1&after_download_lightbox=1", data, timeout=30))
url = r.get("url")
if not url:
    sys.exit("no download url: " + json.dumps(r)[:200])
os.makedirs(out, exist_ok=True)
dest = os.path.join(out, re.sub(r"[^A-Za-z0-9._-]", "_", name))
with op.open(url, timeout=600) as f, open(dest, "wb") as o:
    while chunk := f.read(1 << 20):
        o.write(chunk)
print(dest)
