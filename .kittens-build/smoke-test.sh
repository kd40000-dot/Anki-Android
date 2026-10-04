#!/usr/bin/env bash
set -euo pipefail

adb install -r wear-kittens/app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.balthazar.kittenswear || true
adb shell am start -W -n com.balthazar.kittenswear/.MainActivity
adb forward tcp:18742 tcp:18742

ok=0
for i in $(seq 1 120); do
  if curl -fsS http://127.0.0.1:18742/diagnostic/status -o /tmp/diag.json; then
    cat /tmp/diag.json
    state=$(python3 -c 'import json; print(json.load(open("/tmp/diag.json")).get("state",""))' 2>/dev/null || true)
    if [ "$state" = success ]; then
      ok=1
      break
    fi
    if [ "$state" = error ]; then
      echo "Runtime self-test failed"
      adb logcat -d -t 500 || true
      exit 1
    fi
  fi
  sleep 1
done
if [ "$ok" != 1 ]; then
  echo "Timed out waiting for runtime save self-test"
  adb logcat -d -t 500 || true
  exit 1
fi

curl -fsS -X POST http://127.0.0.1:18742/diagnostic/start-transfer -o /tmp/start.json
cat /tmp/start.json
python3 - <<'PY'
import json
x=json.load(open('/tmp/start.json'))
assert x.get('state')=='success', x
assert x.get('sha256') and len(x['sha256'])==64, x
assert x.get('characters',0)>20, x
open('/tmp/path','w').write('/'+x['url'].split('/',3)[3])
open('/tmp/sha','w').write(x['sha256'])
PY

adb forward tcp:18743 tcp:18743
path=$(cat /tmp/path)
curl -fsS "http://127.0.0.1:18743${path}" -o /tmp/page.html
grep -q "Kittens Wear transfer" /tmp/page.html

curl -fsS "http://127.0.0.1:18743${path}download" -o /tmp/save.txt
test -s /tmp/save.txt
echo "$(cat /tmp/sha)  /tmp/save.txt" | sha256sum -c -

curl -fsS -X POST -H 'Content-Type: text/plain;charset=utf-8' --data-binary @/tmp/save.txt "http://127.0.0.1:18743${path}import" -o /tmp/import.json
cat /tmp/import.json
python3 - <<'PY'
import json
imp=json.load(open('/tmp/import.json'))
assert imp.get('state')=='success', imp
assert imp.get('sha256')==open('/tmp/sha').read().strip(), imp
PY

curl -fsS http://127.0.0.1:18742/transfer/status -o /tmp/status.json
cat /tmp/status.json
python3 - <<'PY'
import json
x=json.load(open('/tmp/status.json'))
assert x.get('active') is True, x
assert x.get('pending') is True, x
assert x.get('pendingSha256')==open('/tmp/sha').read().strip(), x
PY

echo "Kittens Wear runtime save-transfer smoke test PASSED"
