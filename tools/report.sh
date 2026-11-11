mkdir -p out
for f in src/*.*; do case "$f" in *.json) continue;; esac; b=$(basename "${f%.*}")
  ffmpeg -hide_banner -i "$f" -af "astats=metadata=1:reset=44100,ametadata=print:key=lavfi.astats.Overall.RMS_level:file=out/$b.rms" -f null - 2>/dev/null
  ffmpeg -hide_banner -i "$f" 2>&1 | grep -E "Duration|Audio:" > "out/$b.info"
  ffmpeg -v error -i "$f" -lavfi showspectrumpic=s=900x200:legend=0 "out/$b.png"
  ffmpeg -v error -i "$f" -t 40 -ac 2 -c:a libopus -b:a 64k "out/$b.opus"
done
cp src/meta.json out/
