"""Builds the FunctionGemma fine-tuning set for NILA's router.

Writes data/nila_router.jsonl in the google/mobile-actions row format:
{"metadata": "train"|"eval", "tools": [...], "messages": [developer, user, assistant]}

The tools and the developer prompt mirror what the app sends at runtime
(NilaEngine.capabilities, NilaBrain.CHAT_TOOL and modelRoute's systemInstruction).
If you change a tool there, change it here and regenerate.

Usage: python make_dataset.py [--seed 7]
"""

import argparse
import json
import random
from pathlib import Path

DEVELOPER = "You are a model that can do function calling with the following functions"
APPS = {"youtube": "YouTube", "netflix": "Netflix", "instagram": "Instagram", "bgmi": "BGMI"}


def tool(name, description, params, required):
    properties = {key: {"type": kind, "description": label} for key, kind, label in params}
    return {
        "type": "function",
        "function": {
            "name": name,
            "description": description,
            "parameters": {"type": "object", "properties": properties, "required": required},
        },
    }


TOOLS = [
    tool(
        "create_alarm",
        'Set an alarm or wake-up time, for example "wake me up at 6" or "alarm for 7:30 pm". '
        "Give the hour as the user said it and meridiem (am or pm) only if they said one.",
        [("hour", "integer", "hour"), ("minute", "integer", "minute"), ("meridiem", "string", "meridiem")],
        ["hour"],
    ),
    tool(
        "remove_alarm",
        "Turn off, cancel, delete or remove an alarm. Give the time if the user said one; with no time it means their next alarm.",
        [("hour", "integer", "hour"), ("minute", "integer", "minute"), ("meridiem", "string", "meridiem")],
        [],
    ),
    tool(
        "open_app",
        "Open one of these apps by name: " + ", ".join(APPS),
        [("app", "string", "app")],
        ["app"],
    ),
    tool(
        "remember_fact",
        "Store a fact the user explicitly asked NILA to remember",
        [("fact", "string", "fact")],
        ["fact"],
    ),
    tool(
        "search_food",
        "Order food or find restaurants and dishes to eat, for example biryani, pizza or dinner.",
        [("query", "string", "Dish or restaurant name only, like biryani"),
         ("preference", "string", "Taste or diet words the user said, like light, spicy or veg")],
        ["query"],
    ),
    tool(
        "get_directions",
        "Show a map with the route, distance or travel time to a place, for example take me to or how far is.",
        [("destination", "string", "Place name only, like Indiranagar")],
        ["destination"],
    ),
    tool(
        "set_timer",
        'Start a countdown timer, for example "timer for 10 minutes".',
        [("hours", "integer", "hours"), ("minutes", "integer", "minutes"), ("seconds", "integer", "seconds")],
        [],
    ),
    tool("flashlight", "Turn the phone's flashlight (torch) on or off.", [("on", "boolean", "on")], ["on"]),
    tool(
        "create_event",
        "Add an event, meeting or appointment to the calendar. Give the title, the day (today, tomorrow or a weekday) and the time if said.",
        [("title", "string", "title"), ("day", "string", "day"), ("hour", "integer", "hour"),
         ("minute", "integer", "minute"), ("meridiem", "string", "meridiem")],
        ["title"],
    ),
    tool("add_note", 'Save a note the user dictates, for example "take a note that the gate code is 4521".', [("text", "string", "text")], ["text"]),
    tool("open_camera", "Open the camera to take a photo or record a video.", [("video", "boolean", "video")], []),
    tool("call_contact", "Call a person by name or a phone number. NILA opens the dialer; the user presses call.", [("who", "string", "who")], ["who"]),
    tool(
        "chat",
        "Talk with the user: greetings, questions about NILA or anything else, small talk, opinions, "
        "or any request the other functions cannot do",
        [("message", "string", "message")],
        ["message"],
    ),
]

FOODS = [
    "biryani", "chicken biryani", "mutton biryani", "veg biryani", "pizza", "margherita pizza", "burger",
    "masala dosa", "idli", "vada", "butter chicken", "paneer butter masala", "paneer tikka", "shawarma",
    "momos", "noodles", "hakka noodles", "fried rice", "chole bhature", "pav bhaji", "rolls", "kathi roll",
    "ice cream", "cake", "brownie", "sandwich", "pasta", "thali", "north indian thali", "south indian meals",
    "fish curry", "appam", "porotta", "kerala parotta", "dal khichdi", "salad", "coffee", "filter coffee",
    "Meghana Foods", "Empire", "Truffles", "Domino's", "KFC", "McDonald's", "Burger King", "Paradise",
    "A2B", "Leon's", "Behrouz Biryani", "Chai Point",
]
FOOD_TEMPLATES = [
    "im hungry get me some {x}", "I'm hungry, get me {x}", "order {x}", "order some {x} for me",
    "I want {x}", "i want {x} for dinner", "get me {x} for lunch", "find {x} near me", "craving {x}",
    "can you order {x}", "order {x} under 300", "{x} for 2 people please", "find me good {x}",
    "show me {x} places", "where can I get {x}", "I feel like eating {x}", "let's order {x} tonight",
    "food from {x}", "get {x} delivered", "book me some {x}", "need {x} asap", "starving, {x} please",
    "mujhe {x} khana hai", "{x} order karo", "{x} mangwa do", "kuch {x} order kar do",
    "search swiggy for {x}", "what {x} is available", "dinner: {x}", "{x}",
]
# Generic hunger without a dish: the model still searches, with the meal word as the query.
MEAL_ONLY = [
    ("I'm hungry, find me dinner", "dinner"), ("get me lunch", "lunch"), ("order breakfast", "breakfast"),
    ("find something to eat", "food"), ("im starving", "food"), ("find dinner under 800", "dinner"),
    ("what can I eat tonight", "dinner"), ("order some snacks", "snacks"), ("get me dessert", "dessert"),
    ("bhook lagi hai", "food"),
]

PLACES = [
    "Indiranagar", "Koramangala", "MG Road", "Whitefield", "HSR Layout", "Jayanagar", "Electronic City",
    "the airport", "Kempegowda Airport", "Majestic", "Cubbon Park", "Lalbagh", "Malleshwaram",
    "Hebbal", "Marathahalli", "BTM Layout", "Bannerghatta Road", "Phoenix Marketcity", "Orion Mall",
    "UB City", "Bangalore Palace", "Yeshwanthpur station", "Kochi", "Mysore", "Chennai Central",
    "Thiruvananthapuram", "Infosys Electronic City", "Manyata Tech Park", "Nandi Hills", "Church Street",
]
PLACE_TEMPLATES = [
    "take me to {x}", "how far is {x}", "directions to {x}", "navigate to {x}", "how long to reach {x}",
    "route to {x}", "show me the way to {x}", "how do I get to {x}", "distance to {x}",
    "how much time to {x}", "drive to {x}", "map to {x}", "let's go to {x}", "travel time to {x}",
    "is {x} far from here", "{x} kaise jaun", "{x} kitna door hai", "{x} ka rasta batao",
    "get me to {x}", "show {x} on the map",
]

ALARMS = []
for hour in range(1, 13):
    for minute in (0, 15, 30, 45):
        for meridiem in ("am", "pm", None):
            ALARMS.append((hour, minute, meridiem))
ALARM_TEMPLATES = [
    "set an alarm for {t}", "wake me up at {t}", "alarm for {t}", "set alarm {t}", "remind me at {t} with an alarm",
    "please set an alarm at {t}", "{t} alarm", "I need to wake up at {t}", "alarm laga do {t}",
]

APP_TEMPLATES = [
    "open {x}", "launch {x}", "start {x}", "open the {x} app", "go to {x}", "can you open {x}",
    "{x} kholo", "I want to watch something on {x}", "take me to {x} app",
]

FACTS = [
    "I prefer vegetarian food on Fridays", "my wife's birthday is on 12 March", "I park on level B2",
    "my gym is at 7 am", "I am allergic to peanuts", "my office is in Whitefield", "the wifi password is on the fridge",
    "I like my coffee without sugar", "my mom's flight lands at 9 pm", "I take medicine after lunch",
    "my favourite restaurant is Meghana Foods", "the car service is due next week",
]
FACT_TEMPLATES = ["remember that {x}", "please remember {x}", "note that {x}", "keep in mind that {x}", "don't forget {x}"]

# Conversation, including hard negatives that mention food, places, apps or time but ask for no action.
CHAT = [
    "hi", "hello", "hey NILA", "good morning", "how are you", "who are you", "what can you do", "thanks",
    "thank you so much", "tell me a joke", "what's your name", "are you an AI", "good night", "you are awesome",
    "what is the capital of France", "explain black holes simply", "write a short poem about rain",
    "what is 2 plus 2", "who won the world cup in 2011", "give me a motivational quote", "I'm bored",
    "I feel sad today", "how do I stay focused", "translate hello to Hindi", "what day is it",
    "is biryani healthy", "who invented pizza", "how many calories are in a dosa", "what is the best cuisine",
    "how do I cook biryani at home", "give me a recipe for paneer butter masala", "why is pizza round",
    "what is Koramangala famous for", "tell me about Mysore palace", "what is the history of Cubbon Park",
    "is Bangalore traffic bad", "what's a good time to visit Nandi Hills",
    "what is YouTube", "is Netflix worth it", "why do people play BGMI",
    "what time is it in London", "how much sleep do I need", "is waking up at 5 good",
    "what do you remember about me", "what is NILA", "can you help me plan my day", "summarise my day",
    "what should I name my dog", "do you like music", "namaste", "kya haal hai", "sukhamano",
]


NAMES = ["Deva", "Habis", "Amma", "Appa", "Priya", "Rahul", "the office", "Nidula", "Arjun", "Meera"]
EVENTS = [("team meeting", "tomorrow", 4, "pm"), ("dentist", "friday", 11, "am"), ("lunch with Priya", "today", 1, "pm"),
          ("demo review", "monday", 10, "am"), ("gym", "tomorrow", 6, "am"), ("call with Habis", "today", 5, "pm"),
          ("hackathon finale", "saturday", 9, "am"), ("movie", "sunday", 7, "pm")]
NOTES = ["the gate code is 4521", "buy milk and eggs", "the car is on level B2", "send the deck to Deva",
         "pay the electricity bill", "the wifi password is on the fridge", "call the plumber on Monday"]
TASTES = [("light", "dinner"), ("spicy", "biryani"), ("veg", "pizza"), ("healthy", "lunch"), ("cheesy", "pasta")]
PARTY = ["for 2", "for three", "for 4 people", "under 500", "under ₹800", "for two under 600"]


def alarm_text(hour, minute, meridiem):
    text = f"{hour}" if minute == 0 else f"{hour}:{minute:02d}"
    return f"{text} {meridiem}" if meridiem else text


def call(name, arguments):
    return {"role": "assistant", "tool_calls": [{"type": "function", "function": {"name": name, "arguments": arguments}}]}


def rows(rng):
    out = []
    for x in FOODS:
        for t in rng.sample(FOOD_TEMPLATES, 6):
            out.append((t.format(x=x), call("search_food", {"query": x})))
    for text, query in MEAL_ONLY:
        out.append((text, call("search_food", {"query": query})))
    for x in PLACES:
        for t in rng.sample(PLACE_TEMPLATES, 8):
            out.append((t.format(x=x), call("get_directions", {"destination": x.removeprefix("the ")})))
    for hour, minute, meridiem in rng.sample(ALARMS, 80):
        args = {"hour": hour, "minute": minute}
        if meridiem:
            args["meridiem"] = meridiem
        text = rng.choice(ALARM_TEMPLATES).format(t=alarm_text(hour, minute, meridiem))
        out.append((text, call("create_alarm", args)))
    for key, name in APPS.items():
        for t in APP_TEMPLATES:
            for x in (name, key):
                out.append((t.format(x=x), call("open_app", {"app": name})))
    for x in FACTS:
        for t in FACT_TEMPLATES:
            out.append((t.format(x=x), call("remember_fact", {"fact": x})))
    for hour, minute, meridiem in rng.sample(ALARMS, 30):
        args = {"hour": hour, "minute": minute}
        if meridiem:
            args["meridiem"] = meridiem
        t = rng.choice(["turn off my {t} alarm", "cancel the {t} alarm", "delete alarm {t}", "remove my alarm at {t}"])
        out.append((t.format(t=alarm_text(hour, minute, meridiem)), call("remove_alarm", args)))
    for t in ["turn off my alarm", "cancel my next alarm", "stop my alarm", "delete the alarm"]:
        out.append((t, call("remove_alarm", {})))
    for amount, unit in [(5, "minutes"), (10, "minutes"), (25, "minutes"), (1, "hours"), (2, "hours"), (30, "seconds"), (90, "seconds"), (45, "minutes")]:
        for t in rng.sample(["set a timer for {n} {u}", "timer {n} {u}", "start a {n} {u} timer", "{n} {u} timer please", "count down {n} {u}"], 3):
            out.append((t.format(n=amount, u=unit), call("set_timer", {unit: amount})))
    for on, phrases in [(True, ["turn on the flashlight", "torch on", "switch on the torch", "flashlight please", "it's dark, turn on the light"]),
                        (False, ["turn off the flashlight", "torch off", "switch off the torch", "flashlight off"])]:
        for t in phrases:
            out.append((t, call("flashlight", {"on": on})))
    for title, day, hour, meridiem in EVENTS:
        for t in rng.sample(["add {x} {d} at {h} {m}", "schedule {x} {d} at {h}{m}", "put {x} on my calendar for {d} {h} {m}", "remind me of {x} {d} at {h} {m} in the calendar"], 2):
            out.append((t.format(x=title, d=day, h=hour, m=meridiem),
                        call("create_event", {"title": title, "day": day, "hour": hour, "minute": 0, "meridiem": meridiem})))
    for x in NOTES:
        for t in rng.sample(["take a note that {x}", "note: {x}", "write down {x}", "add a note {x}", "save a note saying {x}"], 3):
            out.append((t.format(x=x), call("add_note", {"text": x})))
    for video, phrases in [(False, ["open the camera", "take a photo", "click a selfie", "camera kholo", "I want to take a picture"]),
                           (True, ["record a video", "start a video", "open the video camera"])]:
        for t in phrases:
            out.append((t, call("open_camera", {"video": video} if video else {})))
    for who in NAMES:
        for t in rng.sample(["call {x}", "phone {x}", "dial {x}", "give {x} a call", "{x} ko call karo"], 2):
            out.append((t.format(x=who), call("call_contact", {"who": who})))
    for taste, dish in TASTES:
        for extra in rng.sample(PARTY, 3):
            out.append((f"find a {taste} {dish} {extra}", call("search_food", {"query": dish, "preference": taste})))
    for text in CHAT:
        for variant in {text, text.capitalize(), text + "?" if not text.endswith("?") else text}:
            out.append((variant, call("chat", {"message": variant})))
    return out


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--seed", type=int, default=7)
    parser.add_argument("--eval-share", type=float, default=0.1)
    args = parser.parse_args()
    rng = random.Random(args.seed)

    examples = rows(rng)
    rng.shuffle(examples)
    eval_count = int(len(examples) * args.eval_share)
    out = Path(__file__).parent / "data" / "nila_router.jsonl"
    out.parent.mkdir(exist_ok=True)
    counts = {}
    with out.open("w", encoding="utf-8") as f:
        for i, (user, answer) in enumerate(examples):
            name = answer["tool_calls"][0]["function"]["name"]
            counts[name] = counts.get(name, 0) + 1
            row = {
                "metadata": "eval" if i < eval_count else "train",
                "tools": TOOLS,
                "messages": [{"role": "developer", "content": DEVELOPER}, {"role": "user", "content": user}, answer],
            }
            f.write(json.dumps(row, ensure_ascii=False) + "\n")
    print(f"wrote {len(examples)} rows ({eval_count} eval) to {out}")
    print(json.dumps(counts, indent=1))


if __name__ == "__main__":
    main()
