"""
Приводит заголовки всех classTweaker / accessWidener файлов во всех jar'ах внутри
указанной директории к нужному namespace (по умолчанию "named"), правя jar'ы на месте.

Зачем: Fabric API 26.2 в этом окружении публикует classTweaker'ы в namespace "official",
а Fabric Loom при identity-маппинге (см. build.gradle / SETUP.md) ремапит их в "named"
лишь частично и недетерминированно. Fabric Loader на dev-запуске падает на несовпадении
namespace. Скрипт вызывается из doFirst задач runClient / runServer и единообразно
проставляет namespace во всех модах в кэше Loom (.gradle/loom-cache/remapped_mods).
На имена классов не влияет — при identity-маппинге "official" и "named" совпадают.

Использование:
    python tools/normalize-classtweakers.py <директория> [namespace]
"""
import io
import os
import sys
import zipfile

TARGET = "named"


def fix_header(name: str, data: bytes, target: str) -> tuple[bytes, bool]:
    low = name.lower()
    if not (low.endswith(".classtweaker") or low.endswith(".accesswidener")):
        return data, False
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError:
        return data, False
    lines = text.split("\n")
    if not lines:
        return data, False
    parts = lines[0].split()
    if len(parts) >= 3 and parts[-1] in ("official", "intermediary", "named") and parts[-1] != target:
        sep = "\t" if "\t" in lines[0] else " "
        parts[-1] = target
        lines[0] = sep.join(parts)
        return "\n".join(lines).encode("utf-8"), True
    return data, False


def process_jar_bytes(jar_bytes: bytes, target: str) -> tuple[bytes, bool]:
    zin = zipfile.ZipFile(io.BytesIO(jar_bytes))
    out = io.BytesIO()
    changed = False
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            data = zin.read(item.filename)
            if item.filename.startswith("META-INF/jars/") and item.filename.lower().endswith(".jar"):
                data, c = process_jar_bytes(data, target)
            else:
                data, c = fix_header(item.filename, data, target)
            changed = changed or c
            zi = zipfile.ZipInfo(item.filename, date_time=item.date_time)
            zi.compress_type = item.compress_type
            zi.external_attr = item.external_attr
            zout.writestr(zi, data)
    return out.getvalue(), changed


def main() -> None:
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    root = sys.argv[1]
    target = sys.argv[2] if len(sys.argv) > 2 else TARGET
    patched = 0
    for dirpath, _dirs, files in os.walk(root):
        for fn in files:
            if not fn.lower().endswith(".jar"):
                continue
            path = os.path.join(dirpath, fn)
            with open(path, "rb") as f:
                new_bytes, changed = process_jar_bytes(f.read(), target)
            if changed:
                with open(path, "wb") as f:
                    f.write(new_bytes)
                patched += 1
    print(f"normalize-classtweakers: patched {patched} jar(s) under {root} -> {target}")


if __name__ == "__main__":
    main()
