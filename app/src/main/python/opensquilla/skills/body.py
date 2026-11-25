"""Shared instruction body reads and truthful load receipts."""

from __future__ import annotations

from typing import Any


def expanded_skill_body(skill: Any) -> str:
    """Expand runtime locations only in a body that is actually invoked."""

    body = str(getattr(skill, "content", "") or "")
    base_dir = str(getattr(skill, "base_dir", "") or "")
    if not body or not base_dir:
        return body
    return body.replace("{baseDir}", base_dir).replace("{base_dir}", base_dir)


async def emit_skill_load(
    tool_context: Any,
    skill: Any,
    *,
    source: str,
    status: str,
    name: str = "",
    error: str = "",
) -> None:
    emitter = getattr(tool_context, "skill_load_emitter", None)
    if emitter is None:
        return
    receipt = {
        "name": str(getattr(skill, "name", name)),
        "instanceId": str(getattr(skill, "instance_id", "")),
        "digest": str(getattr(skill, "tree_digest", "")),
        "source": source,
        "status": status,
    }
    if error:
        receipt["error"] = error
    await emitter(receipt)
