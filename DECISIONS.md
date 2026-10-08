# NILA: decisions and open items

## Decisions
- **Track:** Open Innovation (any domain, local/open-source model at the core).
- **Product:** NILA, an intent-first phone. Phase 1 is an Android launcher (the hackathon build). Phase 2 is the NILA Runtime. Phase 3 is an independent NILA OS, not a skin on Android.
- **Tagline:** "Say it. Skip the apps." (title slide). The closing line is "Don't open an app. Say what you need."
- **Brand:** the colours come from the website: `#151513` near-black, `#d9df36` lime, `#f2f0e9` off-white, `#e84619` orange. The logo is a dark circle with a lime "N" (website-vercel/public/favicon.svg).
- **Deck:** Canva https://www.canva.com/d/snrXma0kTsJy39r. It's the purple template with humanized copy. Footers say "NILA" only, with no "iQOO Hackathon 2026" and no "Grand Finale".
- **Team split:** equal, with Gurupriyan leading.
  - Gurupriyan (lead): architecture, integration, the confirmation and permission step, reviewing all work, the final demo.
  - Devananda: launcher screens, choice cards, person mode, the deck.
  - Habis: the on-phone model that understands requests, the food and ride provider connections, ranking, testing.
- **Writing rules:** humanize all external text, with no em dashes and no jargon. Don't mention Gurupriyan's workplace (Claro / Nova Benefits) in submissions.

## Submission form answers (final)

**Idea title:** NILA: A Phone That Understands What You Want

**Description:**
We use our phones to get things done, but even simple tasks can involve too many apps and steps. If you want to order biryani, for example, you may open several apps, search repeatedly, compare prices and delivery times, and then complete the payment.

NILA starts with the user's intention instead. You could simply say, "Find me chicken biryani under ₹250 that can arrive within 30 minutes." NILA understands the request, checks available services and presents the best options in one place. It prepares the next step, but the user always decides before anything is ordered, booked, paid for or sent.

For the hackathon, we plan to build NILA as an Android launcher and demonstrate everyday tasks such as ordering food, sending a message and planning a journey. The larger goal is to build a new, privacy-focused operating system where people interact through their intentions instead of opening and navigating individual apps.

**Prototype URL:** https://nila-vercel-site.vercel.app

**Prior builds & hackathons:**
MINDSPACE won the Best Intervention Award at the TYCL Mental Health Exhibition 2024 in Puducherry. It was a multilingual chatbot that replied out loud, and around 1,000 students used it. In December 2024 we rebuilt it as a five-agent system with a booking server.

In September 2026 we built JIMI at the iQOO City Battle in Hyderabad. It's a voice-first mental health listener that runs fully on the phone, using Gemma 4, on-device speech-to-text and text-to-speech, crisis triage and a journal, with no server.

NILA is a new idea and hasn't been entered in any other hackathon.

**What makes you and your team stand out:**
The three of us have already built the hardest part of NILA, together. At the iQOO City Battle in Hyderabad, Gurupriyan, Devananda and Habis built JIMI in 30 hours. It's a voice app that runs fully on the phone: Gemma 4 on the device, speech-to-text and replies in English, Hindi and Malayalam, and no server. Building it taught us what breaks when a language model runs on a phone (the app size limit, speed, speech accuracy) and how to work around it. We've worked as a team under hackathon pressure before, and we know how we split the work.

We've also shipped AI that real people use. MINDSPACE, our multilingual voice chatbot, won Best Intervention at TYCL 2024 and was used by around 1,000 students.

The work is split evenly across the three of us, and Gurupriyan leads. Gurupriyan owns the overall design, connects the pieces and builds the confirmation step that stops NILA from paying or sending anything without a fingerprint. He also reviews everyone's work before it ships. Devananda builds what you see: the launcher, the cards NILA shows for each choice, and person mode for messaging. Habis builds what happens behind it: the language model on the phone that understands requests, the connections to food and ride providers, and the tests that check NILA picks the right option.

We chose this problem because we live it. Ordering food, booking a ride or texting someone still means jumping between apps that don't talk to each other. NILA starts with what you want, keeps your personal data on the phone, and never pays, books or sends anything without your fingerprint.

**Still to fill:** video walkthrough URL, deck upload (PDF), Android and LLM proficiency levels (pick honestly).

## Open items
- [ ] Canva: delete slides 14 to 18 (leftover template: Company Overview, Our Solutions, Our Services, Market Analysis, Get in touch).
- [ ] Canva: replace the clip art on slide 6 (leopard), slide 7 (green sticker) and slide 9 (lotus).
- [ ] Verify the claims before judging: "works offline" (slide 9), the model running on the phone (slides 8 and 9), the demo running as the iQOO 15 home screen.
- [ ] Is NILA in the Grand Finale or the idea round? (This decides whether "Grand Finale" goes back on the slides.)
- [ ] JIMI's City Battle placement isn't recorded. Add it to the team slide and form if it placed.
- [ ] Build the actual Android launcher (Phase 1).
