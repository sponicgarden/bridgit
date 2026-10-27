# What can Bridget do? card pictures

`docs/whatcan-image-prompts.md` holds the shared style and one content line per card; file names follow the card title
(lower case, runs of other characters become one hyphen).

1. `python3 -I gen.py ../../docs/whatcan-image-prompts.md raw [name ...]` renders 1536x1024 PNGs through sponic's Azure image
   deployment (`gpt-image-2.5-sunburst`, see sponic `infra/runbook/ai-services.md`). The key comes from
   sponic's `infra/bin/bw-sponic`. Existing files are skipped unless names are given. The deployment allows
   only a few images a minute, so a full set takes about 50 minutes.
2. `./convert.sh raw ../../app/src/main/res/drawable-nodpi` makes 720x480 WebP `whatcan_<name>.webp`.

The full-size originals of the first set are kept outside the repo in
`~/Documents/codingprojects/bridget-assets/whatcan-raw/`.
