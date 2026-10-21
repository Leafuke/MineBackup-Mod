#!/usr/bin/env python3
"""Check the 3.4.1 join-notification contract across the maintained matrix."""

import json
from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parents[1]
BASELINE = ROOT / "Fabric/Fabric-26.1"
PACKAGE = Path("src/main/java/com/leafuke/minebackup")
LANG = Path("src/main/resources/assets/minebackup/lang")
KEY = "automation.joinStatusNotification.mode"
PREFIX = "minebackup.message.auto.welcome."


def config_contract(project):
    source = (project / PACKAGE / "config/Config.java").read_text(encoding="utf-8")
    enum = re.search(r"public enum JoinStatusNotificationMode \{(.*?)\n    \}", source, re.S)
    default = re.search(
        r"JOIN_STATUS_NOTIFICATION_MODE_DEFAULT\s*=\s*JoinStatusNotificationMode\.(\w+)",
        source,
    )
    if not enum or not default:
        raise ValueError("missing join notification enum or default")
    if source.count('"' + KEY + '"') != 2:
        raise ValueError("join notification key must be read and written")
    return default.group(1), re.sub(r"\s+", "", enum.group(1))


def language_contract(project, locale):
    data = json.loads((project / LANG / (locale + ".json")).read_text(encoding="utf-8"))
    return {key: value for key, value in data.items() if key.startswith(PREFIX)}


def version(project):
    source = (project / "gradle.properties").read_text(encoding="utf-8")
    match = re.search(r"^mod_version=(.+)$", source, re.M)
    if not match:
        raise ValueError("missing mod_version")
    return match.group(1).strip()


def main():
    workflow = (ROOT / ".github/workflows/release-maintained-builds.yml").read_text(encoding="utf-8")
    projects = re.findall(r"^\s+project_dir:\s*['\"]?([^'\"\s]+)['\"]?\s*$", workflow, re.M)
    if not projects or len(projects) != len(set(projects)):
        raise ValueError("missing or duplicated maintained project directories")
    if "Fabric/Fabric-26.1" not in projects:
        raise ValueError("baseline is absent from the maintained matrix")
    expected_config = config_contract(BASELINE)
    if expected_config[0] != "ALWAYS":
        raise ValueError("baseline default must remain ALWAYS")
    expected_lang = {locale: language_contract(BASELINE, locale) for locale in ("en_us", "zh_cn")}
    if expected_lang["en_us"].keys() != expected_lang["zh_cn"].keys():
        raise ValueError("baseline welcome translation keys differ between languages")
    for locale, messages in expected_lang.items():
        if PREFIX + "remind" not in messages:
            raise ValueError(locale + ": missing reminder welcome text")
        for suffix in ("enabled", "remind"):
            if messages[PREFIX + suffix].count("%s") != 3:
                raise ValueError(locale + ": welcome format argument count differs")

    errors = []
    for relative in projects:
        project = ROOT / relative
        try:
            if config_contract(project) != expected_config:
                raise ValueError("notification enum/parser or default differs from baseline")
            if version(project) != version(BASELINE):
                raise ValueError("mod_version differs from baseline")
            for locale, expected in expected_lang.items():
                if language_contract(project, locale) != expected:
                    raise ValueError(locale + ": welcome keys/text differ from baseline")
            print(relative + ": OK")
        except (OSError, ValueError) as error:
            errors.append(relative + ": " + str(error))
    for error in errors:
        print(error, file=sys.stderr)
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
