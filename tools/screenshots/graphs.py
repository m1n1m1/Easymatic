"""The six macros the website and store screenshots show, as workflow JSON.

Every graph is laid out on the same two-column grid so that the editor's
fit-to-screen lands on the same zoom for all of them: a trunk column at x=0
and two arms at x=-115 / x=+115, one card width apart. A row is 132 dp, a card
190 x 72 dp, so the bounding box is 420 dp wide whatever the graph does.

Node names are at most 16 characters: the card's title area is ~118 dp at
13 sp semibold, and anything longer is ellipsized on the canvas.
"""

import json
import os
import sys

ROW = 132
LEFT, MID, RIGHT = -115, 0, 115

AI_MODEL = "ai-demo-fast"
HUE_HALL = "sh:hue-demo|LIGHT|l1|Hall"
HA_HUB = "ha:ha-demo||Home"
HA_DOOR = "ha:ha-demo|binary_sensor.front_door|Front door"
HA_BLINDS = "ha:ha-demo|cover.living_room_blinds|Living room blinds"
HA_OPEN = "ha:ha-demo|cover.open_cover|Open"
HA_CLIMATE = "ha:ha-demo|climate.hallway|Hallway thermostat"
HA_SET_TEMP = "ha:ha-demo|climate.set_temperature|Set target temperature"


def node(nid, type_id, name, col, row, config=None, inputs=()):
    assert len(name) <= 16, f"{name!r} is {len(name)} chars; the card shows 16"
    return {
        "id": nid,
        "typeId": type_id,
        "name": name,
        "x": float(col),
        "y": float(row * ROW),
        "config": config or {},
        "visibleDataInputs": list(inputs),
    }


def exec_(src, dst, port="out"):
    return {"id": f"e-{src}-{port}-{dst}", "fromNodeId": src, "fromPort": port, "toNodeId": dst, "toPort": "in"}


def data(src, src_port, dst, dst_port):
    return {"id": f"d-{src}-{src_port}-{dst}-{dst_port}", "fromNodeId": src, "fromPort": src_port,
            "toNodeId": dst, "toPort": dst_port}


def workflow(wid, name, nodes, execs, datas=(), enabled=False, icon="BOLT", accent="SYSTEM"):
    return {
        "id": wid,
        "name": name,
        "nodes": nodes,
        "execConnections": execs,
        "dataConnections": list(datas),
        "enabled": enabled,
        "icon": icon,
        "accent": accent,
    }


GRAPHS = {
    # The hero: the whole phone is shown, so this one has to say "this is an
    # app" and "wires carry typed data" at once. The clock value is wired into
    # the comparison rather than read as a `val:` source so the data wire is
    # visible. The trigger sits left and the value right because `in` is the
    # if card's left-hand port and `source` its right-hand one: swapped, the
    # exec wire and the data wire cross right under the trigger.
    "hero-editor": workflow("hero-editor", "Coming home", [
        node("now", "value.now", "Time now", RIGHT, 0),
        node("t", "trigger.wifi_network", "I get home", LEFT, 0,
             {"ssid": "Home", "event": "CONNECTED"}),
        node("if", "action.if", "After 9 pm?", MID, 1,
             {"operator": "GREATER_THAN", "value": "21:00"}, inputs=["source"]),
        node("light", "action.light_control", "Hall light on", LEFT, 2,
             {"target": HUE_HALL, "op": "TURN_ON"}),
        node("say", "action.speak", "Welcome home", RIGHT, 2, {"text": "Welcome home"}),
        node("silent", "action.ringer_mode", "Phone on silent", LEFT, 3, {"mode": "silent"}),
        node("tell", "action.notify", "Tell me it ran", MID, 4,
             {"title": "Coming home", "text": "Your evening routine ran."}),
    ], [
        exec_("t", "if"),
        exec_("if", "light", "true"),
        exec_("if", "say", "false"),
        exec_("light", "silent"),
        exec_("silent", "tell"),
        exec_("say", "tell"),
    ], [
        data("now", "now", "if", "source"),
    ], enabled=True, icon="HOME"),

    "ai-agent": workflow("ai-agent", "AI assistant", [
        node("t", "trigger.message", "Message arrives", MID, 0),
        node("ai", "action.ai_prompt", "Ask the agent", MID, 1, {
            "modelRef": AI_MODEL,
            "prompt": "Answer this message for me.",
            "useTools": "true",
        }),
        # `answer` is the right-hand output, so the consumer it feeds sits on
        # the right; a data wire heading left would cross the exec fan-out.
        node("show", "action.notify", "Tell me about it", LEFT, 2, {"title": "Answered for you"}),
        node("reply", "action.reply_message", "Send the answer", RIGHT, 2, inputs=["text"]),
        node("done", "action.notification_action", "Mark as read", MID, 3),
    ], [
        exec_("t", "ai"),
        exec_("ai", "show"),
        exec_("ai", "reply"),
        exec_("show", "done"),
        exec_("reply", "done"),
    ], [
        data("ai", "answer", "reply", "text"),
    ], icon="STAR"),

    "messaging": workflow("messaging", "Team on call", [
        node("t", "trigger.message", "Message arrives", MID, 0),
        node("if", "action.if", "From the team?", MID, 1,
             {"field": "sender", "operator": "CONTAINS", "value": "Team"}, inputs=["source"]),
        node("cal", "action.calendar_add", "Add to calendar", LEFT, 2,
             {"title": "Follow up", "durationMinutes": "30"}),
        node("tell", "action.notify", "Tell me about it", RIGHT, 2, {"title": "New message"}),
        node("ack", "action.reply_message", "Acknowledge it", LEFT, 3, {"text": "On it."}),
        node("mail", "action.send_mail", "Mail a summary", MID, 4,
             {"subject": "Today's messages"}),
    ], [
        exec_("t", "if"),
        exec_("if", "cal", "true"),
        exec_("if", "tell", "false"),
        exec_("cal", "ack"),
        exec_("ack", "mail"),
        exec_("tell", "mail"),
    ], [
        data("t", "message", "if", "source"),
    ], icon="MESSAGE"),

    "smart-home": workflow("smart-home", "Front door", [
        node("t", "trigger.ha_state", "Front door opens", MID, 0, {"entity": HA_DOOR}),
        node("if", "action.if", "After sunset?", MID, 1,
             {"source": "val:value.now", "operator": "GREATER_THAN", "value": "19:30"}),
        node("light", "action.light_control", "Hall light on", LEFT, 2,
             {"target": HUE_HALL, "op": "TURN_ON"}),
        node("blinds", "action.ha_service", "Open the blinds", RIGHT, 2,
             {"hub": HA_HUB, "target": HA_BLINDS, "service": HA_OPEN}),
        node("heat", "action.ha_service", "Heating to 21 °C", LEFT, 3,
             {"hub": HA_HUB, "target": HA_CLIMATE, "service": HA_SET_TEMP, "data": "temperature: 21"}),
        node("tell", "action.notify", "Door was opened", MID, 4, {"title": "Front door"}),
    ], [
        exec_("t", "if"),
        exec_("if", "light", "true"),
        exec_("if", "blinds", "false"),
        exec_("light", "heat"),
        exec_("heat", "tell"),
        exec_("blinds", "tell"),
    ], icon="HOME"),

    "location-time": workflow("location-time", "Leaving work", [
        node("geo", "trigger.geofence", "I leave work", LEFT, 0,
             {"placeId": "office", "onEnter": "false", "onExit": "true"}),
        node("clock", "trigger.schedule", "Every day at 6", RIGHT, 0,
             {"mode": "AT_TIME", "atTime": "18:00"}),
        node("if", "action.if", "Is it a weekday?", MID, 1,
             {"source": "val:value.now", "operator": "LESS_THAN", "value": "19:00"}),
        node("tell", "action.notify", "On my way home", LEFT, 2, {"title": "Leaving work"}),
        node("wait", "action.delay", "Wait ten minutes", RIGHT, 2, {"duration": "10", "unit": "MINUTES"}),
        node("text", "action.send_message", "Text my partner", LEFT, 3, {"text": "Leaving now."}),
        node("say", "action.speak", "Drive safely", MID, 4, {"text": "Drive safely"}),
    ], [
        exec_("geo", "if"),
        exec_("clock", "if"),
        exec_("if", "tell", "true"),
        exec_("if", "wait", "false"),
        exec_("tell", "text"),
        exec_("text", "say"),
        exec_("wait", "say"),
    ], icon="CAR"),

    "sensors": workflow("sensors", "Find the light", [
        node("shake", "trigger.shake", "Shake the phone", LEFT, 0),
        node("tap", "trigger.device_tap", "Double tap back", RIGHT, 0, {"taps": "DOUBLE_TAP"}),
        node("if", "action.if", "Is it dark?", MID, 1,
             {"source": "val:value.light", "operator": "LESS_THAN", "value": "30"}),
        node("torch", "action.flashlight", "Torch on", LEFT, 2, {"state": "on"}),
        node("buzz", "action.vibrate", "Buzz instead", RIGHT, 2, {"durationMs": "300"}),
        node("wait", "action.delay", "Wait a minute", LEFT, 3, {"duration": "1", "unit": "MINUTES"}),
        node("off", "action.flashlight", "Torch off", MID, 4, {"state": "off"}),
    ], [
        exec_("shake", "if"),
        exec_("tap", "if"),
        exec_("if", "torch", "true"),
        exec_("if", "buzz", "false"),
        exec_("torch", "wait"),
        exec_("wait", "off"),
    ], icon="FLASHLIGHT"),
}


def write_all(directory):
    os.makedirs(directory, exist_ok=True)
    for wid, wf in GRAPHS.items():
        with open(os.path.join(directory, f"{wid}.json"), "w", encoding="utf-8") as f:
            json.dump(wf, f, indent=2, ensure_ascii=False)


if __name__ == "__main__":
    write_all(sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), "workflows"))
