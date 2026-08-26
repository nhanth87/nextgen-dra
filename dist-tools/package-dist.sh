#!/usr/bin/env bash
# package-dist.sh — Elisa Nextgen DRA dist assembler (house style)
# Usage: tools/package-dist.sh [output-dir]   (default: dist/dra relative to repo root)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${1:-$REPO_ROOT/dist/dra}"

JAVA_HOME="${JAVA_HOME:-}"
if [ -z "$JAVA_HOME" ]; then
  JAVA_HOME="$(mise where java@zulu-25 2>/dev/null || true)"
fi
if [ -z "$JAVA_HOME" ]; then
  echo "ERROR: JAVA_HOME not set and mise zulu-25 not found" >&2
  exit 1
fi
case "$JAVA_HOME" in
  *zulu-25*) ;;
  *) echo "ERROR: JDK must be zulu-25 (got $JAVA_HOME)" >&2; exit 1;;
esac
export PATH="$JAVA_HOME/bin:$PATH"
JAVA_MAJOR="$("$JAVA_HOME/bin/java" -version 2>&1 | head -1 | sed -n 's/.*version "\([0-9]*\).*/\1/p')"
if [ "$JAVA_MAJOR" != "25" ]; then
  echo "ERROR: Java 25 required, got major $JAVA_MAJOR" >&2
  exit 1
fi

echo "[1/5] mvn package -DskipTests"
( cd "$REPO_ROOT" && mvn -q package -DskipTests )

QUARKUS_APP="$REPO_ROOT/elisa-dra/target/quarkus-app"
LEGACY_JAR="$REPO_ROOT/elisa-dra/target/elisa-dra-0.1.0-SNAPSHOT.jar"
if [ ! -d "$QUARKUS_APP" ] && [ ! -f "$LEGACY_JAR" ]; then
  echo "ERROR: no build output (quarkus-app or legacy jar) in elisa-dra/target" >&2
  exit 1
fi

echo "[2/5] assemble $OUT"
rm -rf "$OUT"
mkdir -p "$OUT/logs" "$OUT/configs" "$OUT/html"

if [ -d "$QUARKUS_APP" ]; then
  cp -a "$QUARKUS_APP/." "$OUT/"
else
  cp "$LEGACY_JAR" "$OUT/"
fi

cat > "$OUT/run.sh" <<'RUNSH'
#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JH="${JAVA_HOME:-$(mise where java@zulu-25 2>/dev/null || true)}"
if [ -z "$JH" ]; then echo "ERROR: JDK 25 required (mise zulu-25)" >&2; exit 1; fi
MAJOR="$("$JH/bin/java" -version 2>&1 | head -1 | sed -n 's/.*version "\([0-9]*\).*/\1/p')"
[ "$MAJOR" = "25" ] || { echo "ERROR: Java 25 required, got $MAJOR" >&2; exit 1; }
MAIN_JAR="$(ls "$HERE"/quarkus-run.jar 2>/dev/null || ls "$HERE"/elisa-dra-*.jar)"
exec "$JH/bin/java" \
  -Dlog4j.configurationFile="$HERE/configs/log4j2.xml" \
  -Djava.util.logging.manager=org.apache.logging.log4j.jul.LogManager \
  -Dorg.jboss.logging.provider=log4j2 \
  -XX:+UseZGC \
  -XX:+ExitOnOutOfMemoryError \
  -Xlog:gc*:file="$HERE/logs/gc.log":time,uptime:filecount=3,filesize=10m \
  -jar "$MAIN_JAR"
RUNSH
chmod +x "$OUT/run.sh"

echo "[3/5] html + configs (no operator clobber)"
# htmx admin hub (source of truth: repo-root/app) -> dist/dra/html
# NOTE: must NOT collide with fast-jar's own app/ dir (quarkus-app/app holds jars)
APP_DIR="$REPO_ROOT/app"
if [ -d "$APP_DIR" ]; then
  cp -a "$APP_DIR/." "$OUT/html/" 2>/dev/null || true
fi
for tpl in "$REPO_ROOT/configs/"*.json "$REPO_ROOT/configs/"*.xml "$REPO_ROOT/configs/"*.sample; do
  [ -e "$tpl" ] || continue
  base="$(basename "$tpl")"
  target="$OUT/configs/${base%.sample}"
  if [ -e "$target" ]; then
    echo "  keep existing $base (operator-owned)"
  else
    cp "$tpl" "$target"
  fi
done

echo "[4/5] verify bytecode major 69 (Java 25)"
CLASS_FILE="$(find "$REPO_ROOT/elisa-dra/target/classes" -name 'DiaMsg.class' | head -1)"
MAJOR_BC="$("$JAVA_HOME/bin/javap" -verbose "$CLASS_FILE" | sed -n 's/.*major version: //p')"
if [ "$MAJOR_BC" != "69" ]; then
  echo "ERROR: bytecode major $MAJOR_BC != 69" >&2
  exit 1
fi

echo "[5/5] done -> $OUT"
ls -la "$OUT"
