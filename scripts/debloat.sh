#!/bin/zsh
# Disable everything in packages.debloat (reversible: rollback.sh re-enables).
[[ -n "$TV" ]] || { echo "Set TV=<tv-ip>:5555 (your TV's address)"; exit 1; }; cd "${0:A:h}"
LOG=../CHANGELOG.md
grep -v '^#' packages.debloat | grep . | while read p; do
  out=$(adb -s $TV shell pm disable-user --user 0 $p </dev/null 2>&1)
  echo "- $(date '+%F %T') disable-user $p → $out" | tee -a $LOG
done
