#!/bin/zsh
# raw/<slug>.png (1536x1024) -> drawable-nodpi/whatcan_<slug_with_underscores>.webp (720x480)
RAW=$1; OUT=$2; mkdir -p $OUT
for f in $RAW/*.png; do
  n=whatcan_${${f:t:r}//-/_}
  [[ -f $OUT/$n.webp && $OUT/$n.webp -nt $f ]] && continue
  cwebp -quiet -q 80 -resize 720 480 $f -o $OUT/$n.webp
done
