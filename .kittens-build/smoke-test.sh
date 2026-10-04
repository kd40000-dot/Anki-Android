#!/usr/bin/env bash
set -euo pipefail

dump_debug() {
  echo "===== Kittens Wear diagnostic status ====="
  curl -sS http://127.0.0.1:18742/diagnostic/status || true
  echo
  echo "===== Recent app logcat ====="
  adb logcat -d -t 500 | grep -Ei 'kittens|gecko|chromium|AndroidRuntime|FATAL EXCEPTION|JavaScript|console' | tail -250 || true
}
trap dump_debug ERR

adb install -r wear-kittens/app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.balthazar.kittenswear || true
adb shell am start -W -n com.balthazar.kittenswear/.MainActivity
adb forward tcp:18742 tcp:18742

ok=0
for i in $(seq 1 120); do
  if curl -fsS http://127.0.0.1:18742/diagnostic/status -o /tmp/diag.json; then
    cat /tmp/diag.json
    echo
    state=$(python3 -c 'import json; print(json.load(open("/tmp/diag.json")).get("state",""))' 2>/dev/null || true)
    if [ "$state" = success ]; then
      ok=1
      break
    fi
    if [ "$state" = error ]; then
      echo "Runtime self-test failed"
      exit 1
    fi
  fi
  sleep 1
done
if [ "$ok" != 1 ]; then
  echo "Timed out waiting for runtime save self-test"
  exit 1
fi

python3 - <<'PY'
import json
x=json.load(open('/tmp/diag.json'))
assert x.get('state')=='success', x
assert x.get('stage')=='roundtrip', x
assert x.get('resources',0)>0, x
assert x.get('characters',0)>20, x
PY

curl -fsS -X POST http://127.0.0.1:18742/diagnostic/start-transfer -o /tmp/start.json
cat /tmp/start.json
echo
python3 - <<'PY'
import json
x=json.load(open('/tmp/start.json'))
assert x.get('state')=='success', x
assert x.get('sha256') and len(x['sha256'])==64, x
assert x.get('characters',0)>20, x
url=x['url']
assert url.startswith('http://'), x
parts=url.split('/',3)
assert len(parts)==4, x
open('/tmp/path','w').write('/'+parts[3])
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
echo
python3 - <<'PY'
import json
imp=json.load(open('/tmp/import.json'))
expected=open('/tmp/sha').read().strip()
assert imp.get('state')=='success', imp
assert imp.get('sha256')==expected, (imp, expected)
assert imp.get('characters',0)>20, imp
PY

curl -fsS http://127.0.0.1:18742/transfer/status -o /tmp/status.json
cat /tmp/status.json
echo
python3 - <<'PY'
import json
x=json.load(open('/tmp/status.json'))
expected=open('/tmp/sha').read().strip()
assert x.get('active') is True, x
assert x.get('pending') is True, x
assert x.get('pendingSha256')==expected, (x, expected)
assert x.get('pendingCharacters',0)>20, x
PY

curl -fsS http://127.0.0.1:18742/transfer/import-text -o /tmp/pending.json
python3 - <<'PY'
import json,hashlib
x=json.load(open('/tmp/pending.json'))
assert x.get('state')=='success', x
text=x['text']
sha=hashlib.sha256(text.encode()).hexdigest()
assert sha==x['sha256']==open('/tmp/sha').read().strip(), (sha,x)
PY

curl -fsS -X POST http://127.0.0.1:18742/transfer/stop -o /tmp/stop.json
python3 - <<'PY'
import json
assert json.load(open('/tmp/stop.json')).get('state')=='success'
PY

echo "KITTENS_RUNTIME_SMOKE_TEST_OK"
