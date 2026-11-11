set -e
mkdir -p out
for f in raw/*; do b=$(basename "$f"); d=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$f" || echo 0); ch=$(ffprobe -v error -select_streams a:0 -show_entries stream=channels,sample_rate,codec_name -of csv=p=0 "$f" || echo x); echo "$b,$d,$ch" >> out/durations.csv
  # 30 s from the middle, 96 kbps for listening, plus a spectrogram
  mid=$(python3 -c "print(max(0,float('$d' or 0)/2-15))"); ffmpeg -v error -ss $mid -t 30 -i "$f" -ac 2 -c:a libopus -b:a 64k "out/${b%.*}.opus" || true
  ffmpeg -v error -ss $mid -t 30 -i "$f" -lavfi showspectrumpic=s=640x240:legend=0 "out/${b%.*}.png" || true
done
cp candidates.json out/
