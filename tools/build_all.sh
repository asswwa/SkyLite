#!/usr/bin/env bash
# Собирает SkyLite и складывает готовый JAR в dist/. Запуск из корня проекта:
#
#     bash tools/build_all.sh
#
# Нужны JDK 25 и Gradle (те же, что для TranslateBar).
set -euo pipefail
cd "$(dirname "$0")/.."

mkdir -p dist

# build <суффикс> <mc-версия> <fabric-api> <диапазон игры> <совместимость> [доп. -P...]
build() {
    local suffix="$1" mc="$2" api="$3" range="$4" compat="$5"
    shift 5
    echo "########## SkyLite для $suffix ##########"
    gradle build -Pminecraft_version="$mc" -Pfabric_version="$api" \
        -Pminecraft_range="$range" -Pmc_compat="$compat" "$@" --console=plain | grep -E "BUILD (SUCCESSFUL|FAILED)"
    cp build/libs/skylite-0.1.0.jar "dist/skylite-0.1.0+$suffix.jar"
}

# Пока цель одна — 26.3 (инстанс "FPS Modpack").
build 26.3 26.3 "0.163.0+26.3" "~26.3" modern

# Остальные линии подключим позже, когда понадобится:
# build 26.1 26.1.2 "0.155.3+26.1.2" "~26.1" legacy
# build 26.2 26.2 "0.161.0+26.2" "~26.2" modern

echo
echo "Готово. JAR для теста: dist/"
ls -la dist/
