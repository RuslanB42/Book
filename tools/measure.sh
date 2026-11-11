mkdir -p out
for f in raw/*; do b=$(basename "$f"); d=$(ffmpeg -i "$f" 2>&1 | sed -n "s/.*Duration: \([0-9:.]*\).*/\1/p" | head -1 | awk -F: '{print $1*3600+$2*60+$3}'); ch=$(ffmpeg -i "$f" 2>&1 | grep -m1 "Audio:" | sed "s/,/;/g" | cut -c1-120); echo "$b,$d,$ch" >> out/durations.csv
  # 30 s from the middle, 96 kbps for listening, plus a spectrogram
  mid=$(python3 -c "print(max(0,float('$d' or 0)/2-15))"); ffmpeg -v error -ss $mid -t 30 -i "$f" -ac 2 -c:a libopus -b:a 64k "out/${b%.*}.opus" || true
  ffmpeg -v error -ss $mid -t 30 -i "$f" -lavfi showspectrumpic=s=640x240:legend=0 "out/${b%.*}.png" || true
done
cp candidates.json out/
