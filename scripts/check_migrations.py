#!/usr/bin/env python3
"""迁移版本号对账脚本。

在 CI 构建与本地合并含数据库迁移的 PR/分支时运行，校验三类不变量：

1. 连续性：assets/migrations 里的版本号严格递增、连续、无重复
   （防止增删迁移文件后出现缺档，导致某档用户升级被 fallback 清库）；
2. 一致性：AgentDatabase.kt 的 SCHEMA_VERSION 必须等于最大迁移版本；
3. 已发布冻结：版本号小于等于任一 v* tag 上 SCHEMA_VERSION 的迁移文件，
   内容必须与该 tag 上的一字不差——已打 tag 发布的迁移不可修改、不可复用。
   违反时说明 main 未在 hotfix 合流时把未发布迁移后移，或有人篡改了已发布迁移。

依赖本地 git refs：CI 用 actions/checkout fetch-depth: 0（含 tags）即可，本地请先 git fetch --tags。
用法：python3 scripts/check_migrations.py   （退出码 0 = 通过，1 = 有冲突）
"""

import glob
import json
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MIGRATIONS_REL = "app/src/main/assets/migrations"
DB_REL = "app/src/main/java/com/aicode/feature/agent/data/local/database/AgentDatabase.kt"
MIGRATIONS_DIR = os.path.join(ROOT, MIGRATIONS_REL)
DB_FILE = os.path.join(ROOT, DB_REL)
# Kotlin 迁移只允许写在这两个位置：Room 的 addMigrations 注册入口与 core/db 下的迁移实现。
KOTLIN_MIGRATION_FILES = [
    DB_REL,
    "app/src/main/java/com/aicode/core/db/MigrationRepair.kt",
]
SCHEMA_DIR = os.path.join(ROOT, "app/schemas/com.aicode.feature.agent.data.local.database.AgentDatabase")

errors = []
notes = []


def git(args):
    """返回 (returncode, stdout_text)，stdout 统一按 UTF-8 解码，避免 locale 影响。"""
    proc = subprocess.run(["git", "-C", ROOT] + args, capture_output=True)
    return proc.returncode, proc.stdout.decode("utf-8", errors="replace")


def norm(text):
    return text.replace("\r\n", "\n").rstrip("\n")


# ---------- 1. 读取本地迁移文件 ----------
versions = {}  # version -> file name
for name in sorted(os.listdir(MIGRATIONS_DIR)):
    m = re.match(r"(\d+)_", name)
    if not m or not name.endswith(".sql"):
        continue
    ver = int(m.group(1))
    if ver in versions:
        errors.append("版本号 %d 出现多次：%s 与 %s" % (ver, versions[ver], name))
    versions[ver] = name

if versions:
    all_vers = sorted(versions)
    notes.append("本地文件式迁移版本 %d..%d，共 %d 个" % (all_vers[0], all_vers[-1], len(all_vers)))

# ---------- 1b. Kotlin 迁移（文件式表达不了的按列条件补列等） ----------
kotlin_migs = {}  # to_version -> (file, from, to)
for rel in KOTLIN_MIGRATION_FILES:
    path = os.path.join(ROOT, rel)
    if not os.path.isfile(path):
        continue
    with open(path, encoding="utf-8", errors="replace") as f:
        src = f.read()
    for frm, to in re.findall(r"Migration\(\s*(\d+)\s*,\s*(\d+)\s*\)", src):
        frm, to = int(frm), int(to)
        if to in kotlin_migs:
            errors.append("Kotlin 迁移目标版本 %d 重复：%s 与 %s" % (to, kotlin_migs[to][0], rel))
        kotlin_migs[to] = (rel, frm, to)
for to, (rel, frm, _) in sorted(kotlin_migs.items()):
    if to != frm + 1:
        errors.append("Kotlin 迁移 %s:%d→%d 必须满足 to = from + 1" % (rel, frm, to))
    if to in versions:
        errors.append("版本 %d 同时有 SQL 文件（%s）与 Kotlin 迁移（%s），一个版本只能二选一" % (to, versions[to], rel))
    notes.append("Kotlin 迁移 %d→%d（%s）" % (frm, to, rel))

all_versions = sorted(set(versions) | set(kotlin_migs))
if all_versions:
    expected = list(range(all_versions[0], all_versions[-1] + 1))
    if all_versions != expected:
        missing = [v for v in expected if v not in set(all_versions)]
        errors.append("迁移版本不连续：缺失 %s，现有 %s" % (missing, all_versions))

# ---------- 2. SCHEMA_VERSION ----------
with open(DB_FILE, encoding="utf-8") as f:
    db_src = f.read()
m = re.search(r"const val SCHEMA_VERSION = (\d+)", db_src)
schema_ver = int(m.group(1)) if m else None
if schema_ver is None:
    errors.append("在 %s 中找不到 SCHEMA_VERSION" % DB_REL)
elif all_versions and schema_ver != max(all_versions):
    errors.append("SCHEMA_VERSION=%d 与最大迁移版本 %d 不一致" % (schema_ver, max(all_versions)))

# ---------- 3. AutoMigration 与文件式迁移的衔接 ----------
auto_migs = [
    (int(a), int(b))
    for a, b in re.findall(r"@AutoMigration\s*\(\s*from\s*=\s*(\d+)\s*,\s*to\s*=\s*(\d+)", db_src)
]
auto_migs.sort()
for frm, to in auto_migs:
    if to != schema_ver:
        errors.append("@AutoMigration(from=%d, to=%d) 的 to 与 SCHEMA_VERSION=%d 不一致" % (frm, to, schema_ver))
    if to != frm + 1:
        errors.append("@AutoMigration(from=%d, to=%d) 必须满足 to = from + 1" % (frm, to))
max_file = max(versions) if versions else None
if auto_migs and max_file is not None and auto_migs[0][0] != max_file:
    errors.append(
        "@AutoMigration 起始版本 %d 必须衔接文件式迁移最大版本 %d（一个版本只能二选一）"
        % (auto_migs[0][0], max_file)
    )

# ---------- 3b. ADD COLUMN 必须有实体字段 ----------
# 迁移里 ADD COLUMN 的物理列若没在实体声明，Room 在迁移后的列集合校验会直接失败
# （多一列即崩，整个升级事务回滚）。只检查出现在最新 schema 里的列，
# 被后续迁移重建表去掉的历史列（如 apiPath）不参与。
schema_jsons = sorted(
    (int(os.path.basename(p)[:-5]), p)
    for p in glob.glob(os.path.join(SCHEMA_DIR, "*.json"))
    if os.path.basename(p)[:-5].isdigit()
)
latest_columns = set()
if schema_jsons:
    with open(schema_jsons[-1][1], encoding="utf-8") as f:
        schema_doc = json.load(f)
    for table in schema_doc.get("database", {}).get("entities", []):
        for field in table.get("fields", []):
            latest_columns.add(field.get("columnName"))

entity_sources = []
for dirpath, _, filenames in os.walk(os.path.join(ROOT, "app/src/main/java")):
    for name in filenames:
        if name.endswith(".kt"):
            with open(os.path.join(dirpath, name), encoding="utf-8", errors="replace") as f:
                entity_sources.append(f.read())
entity_blob = "\n".join(entity_sources)

for ver, name in sorted(versions.items()):
    with open(os.path.join(MIGRATIONS_DIR, name), encoding="utf-8", errors="replace") as f:
        sql = f.read()
    for column in re.findall(r"ADD\s+COLUMN\s+`?(\w+)`?", sql, flags=re.IGNORECASE):
        if column.upper() == "IF" or column not in latest_columns:
            continue
        declared = re.search(r"val\s+%s\s*:" % re.escape(column), entity_blob) or re.search(
            r'@ColumnInfo\(\s*name\s*=\s*"%s"' % re.escape(column), entity_blob
        )
        if not declared:
            errors.append(
                "迁移 %s 加了列 %s，但实体里没有对应字段；Room 迁移后列集合校验会失败"
                % (name, column)
            )

# ---------- 3c. 当前版本必须有 Room 导出的 schema 与匹配的初始化语句 ----------
if schema_ver is not None:
    if not schema_jsons or schema_jsons[-1][0] != schema_ver:
        errors.append("缺少当前版本的 Room schema %d.json，请运行 KSP 导出" % schema_ver)
    else:
        database = schema_doc["database"]
        identity = database.get("identityHash", "")
        setup = "\n".join(database.get("setupQueries", []))
        if database.get("version") != schema_ver or not re.fullmatch(r"[0-9a-f]{32}", identity):
            errors.append("Room schema %d.json 的版本或 identityHash 无效" % schema_ver)
        elif "VALUES(42, '%s')" % identity not in setup:
            errors.append("Room schema %d.json 的 identityHash 与初始化 SQL 不一致" % schema_ver)

# ---------- 4. 已发布冻结校验 ----------
_, tags_out = git(["for-each-ref", "--sort=-creatordate", "--format=%(refname:short)", "refs/tags/v*"])
tags = [t.strip() for t in tags_out.splitlines() if t.strip()]
published_max = 0
tag_schema = {}  # tag -> schema version
for tag in tags:
    rc, content = git(["show", "%s:%s" % (tag, DB_REL)])
    if rc != 0:
        continue
    m = re.search(r"const val SCHEMA_VERSION = (\d+)", content)
    if m:
        tag_schema[tag] = int(m.group(1))
        published_max = max(published_max, int(m.group(1)))

if tags and not tag_schema:
    notes.append("找到 v* tag 但未能解析其中的 SCHEMA_VERSION，跳过已发布冻结校验")
elif versions and published_max > 0:
    published_content = {}  # version -> (tag, file_name, content)
    for tag in tags:  # 已按创建时间倒序，先写入的即含该版本的最新 tag
        rc, listing = git(["ls-tree", "--name-only", tag, MIGRATIONS_REL + "/"])
        if rc != 0:
            continue
        for line in listing.splitlines():
            name = line.strip().split("/")[-1]
            m = re.match(r"(\d+)_", name)
            if not m:
                continue
            ver = int(m.group(1))
            if ver > published_max or ver in published_content:
                continue  # 只看已发布号段，且仅取最新 tag 的内容
            rc2, content = git(["show", "%s:%s/%s" % (tag, MIGRATIONS_REL, name)])
            if rc2 == 0:
                published_content[ver] = (tag, name, norm(content))
    for ver, name in versions.items():
        if ver in published_content:
            tag, tag_name, tag_content = published_content[ver]
            with open(os.path.join(MIGRATIONS_DIR, name), encoding="utf-8", errors="replace") as f:
                local_content = norm(f.read())
            if local_content != tag_content:
                errors.append(
                    "版本 %d（%s）已发布（最早出现于 tag %s，当时文件 %s），但本地内容与之不同："
                    "已发布迁移不可修改或复用。若是 hotfix 合流后 main 未后移，"
                    "请把该迁移重编号为 %d+1 及之后的连续号再提交。" % (ver, name, tag, tag_name, published_max)
                )
    # 空迁移只推进版本并让 Room 重新校验结构，可以出现在不同版本。
    by_content = {}
    for ver, name in versions.items():
        with open(os.path.join(MIGRATIONS_DIR, name), encoding="utf-8", errors="replace") as f:
            content = norm(f.read())
            if content.strip():
                by_content.setdefault(content, []).append((ver, name))
    for content, entries in by_content.items():
        if len(entries) > 1:
            errors.append(
                "迁移内容重复（疑似只改了编号）：%s；同一段 SQL 不要在不同版本重复，"
                "否则老库的 migration_history 与 user_version 会对不上"
                % "、".join("%d（%s）" % e for e in entries)
            )
    notes.append("已发布版本号上限（全部 tag 最大 SCHEMA_VERSION）= %d" % published_max)

# ---------- 输出 ----------
if errors:
    print("[FAIL] 迁移版本号对账未通过：")
    for e in errors:
        print("  - %s" % e)
    sys.exit(1)
print("[OK] 迁移版本号对账通过")
for n in notes:
    print("  - %s" % n)