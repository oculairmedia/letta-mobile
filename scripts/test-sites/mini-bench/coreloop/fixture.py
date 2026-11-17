import time
from .device import dump, tap_node


def has_node(tree, attribute, value):
    return any((n.get(attribute) == value for n in tree.iter("node")))


def ensure_idle(tree, message):
    if has_node(tree, "content-desc", "Stop"):
        raise RuntimeError(message)


def open_navigation(directory, tree, name):
    if not has_node(tree, "content-desc", "Close navigation menu"):
        tap_node(tree, "content-desc", "Agents and conversations")
        return dump(directory, name)
    return tree


def select_menu_conversation(directory, before, title):
    if not has_node(before, "content-desc", "Close navigation menu"):
        tap_node(before, "content-desc", "Agents and conversations")
    menu = dump(directory, "conversation-menu")
    if not has_node(menu, "text", title):
        tap_node(menu, "content-desc", "mini-bench")
        menu = dump(directory, "fixture-agent-chat")
        menu = open_navigation(directory, menu, "fixture-agent-menu")
    tap_node(menu, "text", title)


def select_conversation(directory, scope):
    before = dump(directory, "before")
    if not has_node(before, "text", "mini-bench") and (not has_node(before, "content-desc", "mini-bench")):
        raise RuntimeError("mini-bench must be open; refusing to send to another agent")
    ensure_idle(before, "existing run active; refusing to interrupt another run")
    title = scope.get("conversationTitle")
    if not isinstance(title, str) or not title.startswith("core-loop-harness"):
        raise RuntimeError("fixture conversation needs its unique core-loop-harness title")
    if has_node(before, "text", title):
        tap_node(before, "text", title)
    else:
        select_menu_conversation(directory, before, title)
    time.sleep(1.5)
    selected = dump(directory, "selected-conversation")
    if not has_node(selected, "text", "mini-bench"):
        raise RuntimeError("fixture chat did not open")
    ensure_idle(selected, "fixture already active after selection")
    return None
