"""
Восстанавливает поле "jars" (jar-in-jar декларация) в fabric.mod.json мода
Simple Voice Chat после ремапа Loom.

Зачем: обнаружено 11.09.2026 при откате голосового пайплайна на сервер (см.
ТЗ проекта, раздел 5). В скачанном jar-е simple-voice-chat-fabric-2.6.23+26.2
физически лежит META-INF/jars/voicechat-api-2.6.20.jar (jar-in-jar, декларирован
полем "jars" в fabric.mod.json — это то, откуда Fabric Loader берёт мод с id
"voicechat_api", от которого зависит verity-server). При identity-маппинге в этом
окружении Loom при ремапе мода обнуляет поле "jars" целиком (сам вложенный jar
остаётся физически внутри, но без декларации Fabric Loader не знает, что его нужно
распаковать/просканировать как отдельный мод) — тот же класс проблемы, что и с
namespace classTweaker'ов (см. normalize-classtweakers.py), только с другим полем
манифеста. Без этого поля сервер падает при старте:
"HARD_DEP_NO_CANDIDATE ... voicechat_api" — verity-server требует voicechat_api,
а Fabric Loader его просто не видит.

Список вложенных jar-ов ниже — точная копия поля "jars" из ОРИГИНАЛЬНОГО
(до ремапа) fabric.mod.json мода simple-voice-chat-fabric-2.6.23+26.2, снятая
руками при разборе jar-а 11.09.2026. Если версия мода (voicechat_version в
gradle.properties) поменяется, этот список может тоже поменяться — стоит
перепроверить содержимое нового jar-а тем же способом (unzip -l + fabric.mod.json).

Использование:
    python tools/fix-svc-jar-in-jar.py <директория с remapped_mods>
"""
import io
import json
import os
import sys
import zipfile

# Копия поля "jars" из fabric.mod.json simple-voice-chat-fabric-2.6.23+26.2 (снята
# 11.09.2026 из maven.modrinth:simple-voice-chat:fabric-2.6.23+26.2, см. javadoc выше).
EXPECTED_JARS = [
    {"file": "META-INF/jars/fabric-api-base-2.0.4+ece063239c.jar"},
    {"file": "META-INF/jars/fabric-command-api-v2-3.1.0+00cb03469c.jar"},
    {"file": "META-INF/jars/fabric-key-mapping-api-v1-2.0.5+e2bdee789c.jar"},
    {"file": "META-INF/jars/fabric-lifecycle-events-v1-4.1.3+4575b05f9c.jar"},
    {"file": "META-INF/jars/fabric-networking-api-v1-6.3.3+72073ef09c.jar"},
    {"file": "META-INF/jars/fabric-permission-api-v1-1.0.2+7c33558a9c.jar"},
    {"file": "META-INF/jars/fabric-rendering-v1-25.1.6+46a6d00c9c.jar"},
    {"file": "META-INF/jars/fabric-resource-loader-v1-2.0.13+9edec1269c.jar"},
    {"file": "META-INF/jars/voicechat-api-2.6.20.jar"},
]


def patch_jar(path: str) -> bool:
    with open(path, "rb") as f:
        data = f.read()
    zin = zipfile.ZipFile(io.BytesIO(data))
    manifest = json.loads(zin.read("fabric.mod.json"))
    if manifest.get("jars") == EXPECTED_JARS:
        return False  # уже актуально (например, повторный запуск на том же кэше)

    manifest["jars"] = EXPECTED_JARS
    new_manifest_bytes = json.dumps(manifest, ensure_ascii=False, indent=2).encode("utf-8")

    out = io.BytesIO()
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            content = new_manifest_bytes if item.filename == "fabric.mod.json" else zin.read(item.filename)
            zi = zipfile.ZipInfo(item.filename, date_time=item.date_time)
            zi.compress_type = item.compress_type
            zi.external_attr = item.external_attr
            zout.writestr(zi, content)

    with open(path, "wb") as f:
        f.write(out.getvalue())
    return True


def main() -> None:
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    root = sys.argv[1]
    patched = 0
    for dirpath, _dirs, files in os.walk(root):
        for fn in files:
            if fn.lower().startswith("simple-voice-chat") and fn.lower().endswith(".jar"):
                path = os.path.join(dirpath, fn)
                try:
                    if patch_jar(path):
                        patched += 1
                        print(f"fix-svc-jar-in-jar: восстановлено поле jars в {path}")
                except KeyError:
                    print(f"fix-svc-jar-in-jar: {path} без fabric.mod.json — пропуск")
    print(f"fix-svc-jar-in-jar: патчено {patched} jar(s) под {root}")


if __name__ == "__main__":
    main()
