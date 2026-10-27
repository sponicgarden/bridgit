"""Builds bridget-spec.html from android/docs/bridget-spec-v2.md.

The front section (capabilities, roadmap, open questions) is generated from
the spec's own tables, so the page never drifts from the spec.
"""
import html, re, sys
import markdown

SRC, OUT = sys.argv[1], sys.argv[2]
md = open(SRC, encoding="utf-8").read()


def section(title_prefix):
    m = re.search(r"^## " + re.escape(title_prefix) + r".*?$(.*?)(?=^## |\Z)", md, re.S | re.M)
    return m.group(1) if m else ""


def table_rows(block, start_marker):
    """Rows (lists of cells) of the first markdown table after start_marker."""
    i = block.find(start_marker)
    if i < 0:
        return []
    rows = []
    for line in block[i:].splitlines()[2:]:
        if not line.startswith("|"):
            if rows:
                break
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        rows.append(cells)
    return rows


def inline(text):
    """Tiny inline markdown for table cells: code, bold, links."""
    t = html.escape(text, quote=False)
    t = re.sub(r"`([^`]+)`", r"<code>\1</code>", t)
    t = re.sub(r"\*\*([^*]+)\*\*", r"<strong>\1</strong>", t)
    return t


# ---- facts for the front section ----------------------------------------
version = re.search(r"\| \*\*Version\*\* \| ([^|]+)\|", md).group(1).strip()
caps = table_rows(section("8. Capabilities"), "| # | Capability |")
roadmap = table_rows(section("16. Roadmap"), "| Phase |")
questions = table_rows(section("17. Open questions"), "| # | Question |")
decisions = table_rows(section("1. Decisions log"), "| # | Date |")
req_count = len(set(re.findall(r"\*\*([A-Z]{2,3}-\d{2}[a-z]?)\b", md)))


def phase_class(p):
    p = p.lower()
    if "built" in p:
        return "built"
    if "not scheduled" in p or p.strip() in ("—", "-"):
        return "later"
    return "planned"


cap_cards = "\n".join(
    f'<li class="cap"><span class="cap-no">{html.escape(c[0])}</span>'
    f'<span class="cap-name">{inline(c[1])}</span>'
    f'<span class="chip chip-{phase_class(c[3])}">{inline(c[3])}</span></li>'
    for c in caps
)
road_items = "\n".join(
    f'<li class="phase"><div class="phase-head"><span class="phase-name">{inline(r[0])}</span>'
    f'<span class="phase-ver">{inline(r[1])}</span></div>'
    f'<p class="phase-body">{inline(r[2])}</p>'
    f'<p class="phase-done"><span>Done when</span> {inline(r[3])}</p></li>'
    for r in roadmap
)
q_items = "\n".join(
    f'<li class="q"><span class="q-no">{html.escape(q[0])}</span>'
    f'<div><p class="q-text">{inline(q[1])}</p>'
    f'<p class="q-default"><span>Default</span> {inline(q[2])}</p></div></li>'
    for q in questions
)

# ---- the full spec body ---------------------------------------------------
body_md = md[md.index("## 0. How to read"):]
conv = markdown.Markdown(extensions=["tables", "fenced_code", "sane_lists", "toc"],
                         extension_configs={"toc": {"toc_depth": "2-3", "anchorlink": False}})
body = conv.convert(body_md)
toc_tokens = conv.toc_tokens

TAG_CLASS = {"decided": "decided", "proposed": "proposed", "open": "open", "verify": "verify",
             "built": "built", "superseded": "superseded"}


def tag_span(tag):
    first = re.split(r"[ ,→]", tag.strip())[0].lower()
    cls = TAG_CLASS.get(first, "phase" if re.match(r"p\d", first) else "proposed")
    return f'<span class="tag tag-{cls}">{tag.strip()}</span>'


# "**AGT-09 [Decided]**" → id + chip
body = re.sub(
    r"<strong>([A-Z]{2,3}-\d{2}[a-z]?) \[([^\]]+)\]((?:[^<])*)</strong>",
    lambda m: f'<span class="req"><code class="rid" id="{m.group(1).lower()}">{m.group(1)}</code>'
              f'{tag_span(m.group(2))}{("<strong>" + m.group(3).strip() + "</strong>") if m.group(3).strip() else ""}</span>',
    body)
# bare inline status markers like [Verify]
body = re.sub(r"\[(Verify|Decided|Proposed|Open)\]", lambda m: tag_span(m.group(1)), body)
# wrap tables for horizontal scroll
body = body.replace("<table>", '<div class="table-wrap"><table>').replace("</table>", "</table></div>")


def toc_html(tokens):
    out = ['<ol class="toc">']
    for t in tokens:
        out.append(f'<li><a href="#{t["id"]}">{t["name"]}</a>')
        if t.get("children"):
            out.append('<ol>' + "".join(
                f'<li><a href="#{c["id"]}">{c["name"]}</a></li>' for c in t["children"]) + '</ol>')
        out.append("</li>")
    out.append("</ol>")
    return "".join(out)


toc = toc_html(toc_tokens)

ICON = """<svg viewBox="0 0 108 108" aria-hidden="true" class="mark"><defs><linearGradient id="bg" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#17897C"/><stop offset="1" stop-color="#0A4640"/></linearGradient><clipPath id="c"><circle cx="54" cy="54" r="54"/></clipPath></defs><g clip-path="url(#c)"><rect width="108" height="108" fill="url(#bg)"/><path d="M45.5,35.5 A12,12 0 0 1 62.5,35.5" fill="none" stroke="#FFC15E" stroke-width="2.6" stroke-linecap="round" opacity="0.85"/><path d="M40.3,30.3 A19.4,19.4 0 0 1 67.7,30.3" fill="none" stroke="#FFC15E" stroke-width="2.6" stroke-linecap="round" opacity="0.5"/><path d="M42,50 V66 M54,45 V66 M66,50 V66" stroke="#fff" stroke-width="2.4" stroke-linecap="round" opacity="0.75"/><path d="M28,67 Q54,21 80,67" fill="none" stroke="#fff" stroke-width="6.5" stroke-linecap="round"/><path d="M23,66 H85 A3.5,3.5 0 0 1 85,73 H23 A3.5,3.5 0 0 1 23,66 Z" fill="#fff"/><path d="M34,80 H74 M42,86 H66" stroke="#fff" stroke-width="2.4" stroke-linecap="round" opacity="0.35"/><circle cx="54" cy="44" r="5.2" fill="#FFC15E"/></g></svg>"""

page = open(sys.argv[3], encoding="utf-8").read()
page = (page.replace("{{VERSION}}", html.escape(version))
            .replace("{{ICON}}", ICON)
            .replace("{{CAP_COUNT}}", str(len(caps)))
            .replace("{{REQ_COUNT}}", str(req_count))
            .replace("{{Q_COUNT}}", str(len(questions)))
            .replace("{{D_COUNT}}", str(len(decisions)))
            .replace("{{PHASE_COUNT}}", str(len([r for r in roadmap if re.match(r"\*\*P", r[0])])))
            .replace("{{CAPS}}", cap_cards)
            .replace("{{ROADMAP}}", road_items)
            .replace("{{QUESTIONS}}", q_items)
            .replace("{{TOC}}", toc)
            .replace("{{BODY}}", body))
open(OUT, "w", encoding="utf-8").write(page)
print(f"wrote {OUT}: {len(page)//1024} KB, {len(caps)} caps, {len(roadmap)} phases, {len(questions)} questions, {req_count} requirements")
