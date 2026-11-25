"""Bound, explicit skill identities carried by one user turn."""

from __future__ import annotations

MAX_SELECTED_SKILLS = 16


def normalize_selected_skills(value: object) -> tuple[dict[str, str], ...]:
    """Validate identities and deduplicate without changing selection order."""
    if value is None:
        return ()
    if not isinstance(value, (list, tuple)):
        raise ValueError("selectedSkills must be an array")
    if len(value) > MAX_SELECTED_SKILLS:
        raise ValueError(f"selectedSkills supports at most {MAX_SELECTED_SKILLS} skills")
    result: list[dict[str, str]] = []
    names: dict[str, dict[str, str]] = {}
    for item in value:
        if not isinstance(item, dict) or set(item) != {"name", "instanceId", "digest"}:
            raise ValueError("selectedSkills items require name, instanceId and digest")
        ref: dict[str, str] = {}
        for field in ("name", "instanceId", "digest"):
            raw = item[field]
            if not isinstance(raw, str) or not raw.strip() or len(raw) > 512:
                raise ValueError(f"selectedSkills.{field} must be a non-empty bounded string")
            ref[field] = raw.strip()
        previous = names.get(ref["name"])
        if previous is not None:
            if previous != ref:
                raise ValueError("selectedSkills contains conflicting identities for one skill")
            continue
        names[ref["name"]] = ref
        result.append(ref)
    return tuple(result)
