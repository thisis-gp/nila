# NILA deck v2: humanized slide text

Source: Google Slides deck (13 slides). Same story, tighter words, two new slides (7 and 11).
Lines marked [CONFIRM] need a team check before they go on a slide.

---

## 1. Title
NILA
The phone after apps
Say what you need. NILA checks the services for you and only shows a screen when you have a choice to make.
Gurupriyan · Devananda · Habis
"Chicken biryani under ₹250, in 30 minutes."

## 2. The problem
One sentence of intent. Seven steps of work.
What you meant: "Chicken biryani under ₹250, delivered in 30 minutes."
What your phone made you do: pick an app, search, filter, open a second app to compare, check delivery time, choose payment, confirm.
Your phone understands taps, not goals. So you end up doing the work of connecting apps yourself.

## 3. The idea
Say the outcome, skip the steps.
Today: you pick the app, learn each interface, repeat your preferences, and compare options by hand.
With NILA: you say what you want, services compete for it, NILA remembers your preferences, and the options show up side by side.
Apps don't go away. They keep running in the background as services NILA can call.

## 4. How it works
From a sentence to a finished task in four steps.
01 Say it: speak or type what you want in plain words.
02 NILA searches: it finds the providers that can do it and compares them.
03 A screen appears: cards, a map or a button, only when it helps you decide.
04 You confirm: nothing is paid for, sent or shared until you say yes.

## 5. Demo: dinner in one sentence (live on the iQOO 15)
1 Ask  2 Compare  3 Confirm  4 Track
"Chicken biryani under ₹250, delivered in 30 minutes."
Spice Route ₹245, 4.5 stars, 28 min | Biryani House ₹229, 4.3 stars, 24 min | Dum Kitchen ₹210, 4.1 stars, 30 min
Touch to pay ₹245 → Order placed, arriving in 26 min.
We never opened a food delivery app.
Note: restaurants and prices are sample data from an ONDC-style sandbox.

## 6. Talk. Show. Act.
Talk: say it normally. NILA only asks about what's missing.
Show: a screen appears for the choice, then goes away.
Act: nothing important happens until you confirm.
Person mode: "Tell Rahul I'll reach by 6:30." NILA writes the draft and waits for you to tap Send.

## 7. NEW: Isn't this just Gemini?
Gemini and other assistants are starting to run tasks inside apps. NILA is different in three ways:
- It doesn't favour one company. It compares providers over open networks like ONDC.
- It understands your request on the phone. Providers only get the order details they need.
- The home screen itself is built around requests. NILA isn't an assistant sitting on top of the app grid.

## 8. Trust
It searches on its own. It asks before it acts.
Read (automatic): search, filter and compare.
Prepare (you review): draft messages, plans and carts.
Commit (fingerprint): pay, buy, send, or share personal data.
The bigger the consequence, the stronger the check.
Speech and intent parsing happen on the phone. [CONFIRM: true in the build we demo?]

## 9. Architecture
One pipeline from voice to transaction.
On the phone: voice or text → local intent model → structured request (dish, budget, delivery time) → permission check (read / prepare / commit) → generated cards → confirm.
Service layer: service router → provider adapters (sample data / ONDC sandbox) → results in one format so they can be compared.
Office Kit connects to the laptop for builds, evals and a fallback model.

## 10. Why a phone
A launcher is the most phone-native thing you can build. It's the first screen you see every time you unlock.
- The model runs on the phone. Intent parsing runs on the Snapdragon NPU, so it's fast, private and works offline. [CONFIRM: NPU or GPU in the demo build?]
- It is the home screen of the iQOO 15 we're presenting from.
- Every payment or send needs a fingerprint.
- Office Kit links the phone to a laptop for building and testing.

## 11. NEW: What works today
- Live site: nila-vercel-site.vercel.app
- Working flows: food order, message to a person, ride. [CONFIRM which flows run on the phone]
- Restaurants and prices are sample data. We'll add real ONDC sandbox calls next.

## 12. Roadmap
A launcher today. An operating system next.
Phase 1, NILA Launcher (we are here): a home screen you talk to, intent parsing, generated cards, person mode, confirmation.
Phase 2, NILA Runtime: an open registry of services, a personal context layer, permissions, action history.
Phase 3, NILA OS: an operating system designed around requests from the start.

## 13. Team
We've built on-device AI before.
Gurupriyan, Software Engineer: launcher, conversation UI, generated cards.
Devananda, Data Scientist: intent extraction, provider ranking, fallback. [CONFIRM roles]
Habis, Biostatistician: service integration, normalising results, evaluation.
- MINDSPACE: Best Intervention Award, TYCL Mental Health Exhibition 2024, Puducherry. A multilingual chatbot with spoken replies, used by around 1,000 students. Rebuilt in Dec 2024 as a five-agent system with a booking server.
- JIMI: a voice-first mental health listener that runs fully on the phone (Gemma 4, on-device speech, crisis triage, journal). Built at the iQOO Hackathon City Battle, Hyderabad, Sept 2026. [CONFIRM result/placement]

## 14. Close
Don't open an app. Say what you need.
NILA, the phone after apps. Thank you.
