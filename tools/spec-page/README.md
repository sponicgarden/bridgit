# Spec page builder

Builds the readable HTML version of `docs/bridgit-spec-v2.md`
(published as a private Claude artifact: https://claude.ai/artifact/VgpQreXxSXza4MRVDxXWMr).

```bash
python3 -m venv /tmp/specpage && /tmp/specpage/bin/pip install markdown==3.7
/tmp/specpage/bin/python tools/spec-page/build_page.py \
  docs/bridgit-spec-v2.md /tmp/bridget-spec.html tools/spec-page/template.html
```

The overview (capabilities, roadmap, open questions, counts) is generated from
the spec's own tables, so rebuild after every spec change and republish.
