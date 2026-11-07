#!/system/bin/sh
# Run inside the app (run-as): sets up a store + links, runs cowtest.exe, checks the store.
#   run.sh <files dir> <store mode: rw|ro>
set -e
F="$1"; MODE="$2"
P="$F/prefix-cow"; S="$F/store-cow"; W="$F/arm64-v8a/lib/wine/aarch64-unix/wine"
export WINE_COW_STORE="$S" WINEPREFIX="$P" LD_LIBRARY_PATH="$F/arm64-v8a/lib" WINEDEBUG=-all
[ -d "$P/drive_c" ] || "$W" wineboot -i >/dev/null 2>&1
"$F/arm64-v8a/bin/wineserver" -k 2>/dev/null || true
chmod -R u+w "$S" 2>/dev/null || true; rm -rf "$S" "$P/drive_c/cowtest"; mkdir -p "$S" "$P/drive_c/cowtest"
for n in read delete move create_always truncate overwrite attrib map; do
  echo "STORE $n" > "$S/$n"
  ln -s "$S/$n" "$P/drive_c/cowtest/$n.txt"
done
if [ "$MODE" = ro ]; then chmod 0444 "$S"/*; chmod 0555 "$S"; fi
cp "$F/cowtest.exe" "$P/drive_c/cowtest.exe"
"$W" 'C:\cowtest.exe' 2>&1 | grep -v "_r_debug"
echo "--- store (must be unchanged: STORE <name>)"
for n in read delete move create_always truncate overwrite attrib map; do
  printf '%-15s ' "$n"; [ -e "$S/$n" ] && head -c 40 "$S/$n" || echo "MISSING"
done
echo "--- prefix entries"
for f in "$P"/drive_c/cowtest/*; do if [ -L "$f" ]; then echo "link  ${f##*/}"; else printf "file  %-20s " "${f##*/}"; head -c 20 "$f" | tr "\n" " "; echo; fi; done
