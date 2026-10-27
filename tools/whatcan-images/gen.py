"""Generate What can Bridget do? card images via sponic's Azure gpt-image deployment."""
import base64, json, os, re, subprocess, sys, urllib.request
from concurrent.futures import ThreadPoolExecutor

MD, OUT = sys.argv[1], sys.argv[2]
only = set(sys.argv[3:])
ENDPOINT = "https://sponic-openai-prod-eastus2.openai.azure.com/"
DEPLOY = "gpt-image-2.5-sunburst"
KEY = subprocess.run([os.path.expanduser("~/Documents/codingprojects/sponic/infra/bin/bw-sponic"), "field",
                      "5ef0be7f-20b0-4fa0-874f-b49301246146", "key1"], capture_output=True, text=True, check=True).stdout.strip()

text = open(MD).read()
style = re.search(r"## Style.*?```text\n(.*?)```", text, re.S).group(1).strip()
rows = re.findall(r"^\| `([a-z0-9-]+)\.png` \| [^|]+ \| (.+?) \|$", text, re.M)
os.makedirs(OUT, exist_ok=True)

def gen(row):
    name, content = row
    path = f"{OUT}/{name}.png"
    if os.path.exists(path) and not only: return f"skip {name}"
    body = json.dumps({"prompt": f"{style}\n\n{content}\n\nStrictly follow the style above; no text of any kind.",
                       "size": "1536x1024", "quality": "low", "n": 1, "output_format": "png"}).encode()
    for attempt in range(4):
        try:
            req = urllib.request.Request(f"{ENDPOINT}openai/deployments/{DEPLOY}/images/generations?api-version=2025-04-01-preview",
                                         data=body, headers={"api-key": KEY, "Content-Type": "application/json"})
            with urllib.request.urlopen(req, timeout=600) as r:
                data = json.load(r)
            open(path, "wb").write(base64.b64decode(data["data"][0]["b64_json"]))
            return f"ok {name}"
        except Exception as e:
            err = e.read().decode()[:300] if hasattr(e, "read") else str(e)
            print(f"retry {name} {attempt}: {err}", flush=True)
            import time; time.sleep(30 * (attempt + 1))
    return f"FAIL {name}"

todo = [r for r in rows if not only or r[0] in only]
print(f"{len(todo)} images", flush=True)
with ThreadPoolExecutor(4) as ex:
    for res in ex.map(gen, todo): print(res, flush=True)
