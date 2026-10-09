# NILA: decisions and open items

## Finale build lock: 9 October 2026

- **Build:** Android launcher with a declarative skill runtime. NILA generates validated skill manifests and trusted component trees, never executable code.
- **Primary service:** Swiggy Food end to end. Instamart and Dineout prove reuse of the same MCP client and UI catalogue. Google Maps supports places and routes.
- **Assistant:** Gemma 4 E2B through LiteRT-LM, using the GPU first.
- **Tool router:** FunctionGemma 270M, fine-tuned for NILA's fixed English, Hindi and Malayalam tool schemas.
- **Memory:** EmbeddingGemma 2 Text 270M with ObjectBox 5.4.2 structured storage and HNSW vector search.
- **Voice:** Lakshmi is the voice identity. Use Android on-device speech recognition and offline TTS voices for the reliable build.
- **Generated-skill demo:** the user asks NILA to build an expense tracker. The resulting skill requests SMS permission, stores derived transactions locally, renders trusted UI and can be disabled or deleted.
- **Safety:** Read and Prepare may run automatically. Commit always stops for explicit confirmation. Vector retrieval cannot authorize actions.
- **Pitch:** parked until the primary phone demo passes.

## Decisions
- **Track:** Open Innovation (any domain, local/open-source model at the core).
- **Product:** NILA, an intent-first phone. Phase 1 is an Android launcher (the hackathon build). Phase 2 is the NILA Runtime. Phase 3 is an independent NILA OS, not a skin on Android.
- **Tagline:** "Say it. Skip the apps." (title slide). The closing line is "Don't open an app. Say what you need."
- **Brand:** the colours come from the website: `#151513` near-black, `#d9df36` lime, `#f2f0e9` off-white, `#e84619` orange. The logo is a dark circle with a lime "N" (`website-vercel/public/favicon.svg`).
- **Deck:** Canva https://www.canva.com/d/snrXma0kTsJy39r. It's the purple template with humanized copy. Footers say "NILA" only, with no "iQOO Hackathon 2026" and no "Grand Finale".
- **Team split:** equal, with Gurupriyan leading.
  - Gurupriyan (lead): architecture, integration, the confirmation and permission step, reviewing all work, the final demo.
  - Devananda: launcher screens, choice cards, person mode, the deck.
  - Habis: on-device models, Swiggy and Maps adapters, vector memory, skill validation and evaluation.
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

## Integration decisions: 9 October 2026

- **Swiggy login:** OAuth 2.1 with PKCE. Email builders@swiggy.in to register `nila://oauth` as a redirect. Until it is approved, NILA runs a loopback server inside the app and uses the dev-allowed `http://localhost` redirect. Access tokens last 5 days and cannot be refreshed, so log in on the demo phone on Friday night and show a clear "sign in again" state when the token expires.
- **Swiggy fallback:** keep the labelled offline fixture so a login or network failure never blocks the demo.
- **Model delivery:** Gemma 4 E2B (2.58 GB) is over Android's 2 GB APK limit. Push models with `adb push` or download them on first launch. Never bundle them in the APK.
- **Embeddings:** EmbeddingGemma 2 Text 270M through LiteRT-LM. An Android-ready build exists (165 MB generic file, plus a Qualcomm SM8850 / iQOO 15 build). Measure RAM on the iQOO 15 ourselves; file size is not runtime RAM.
- **Tool router languages:** the official Mobile Actions dataset and FunctionGemma's published evaluation are English-only. Fine-tune on English first, then add our own Hindi and Malayalam examples with a held-out test set. Claim only the languages that pass that test.
- **Malayalam voice:** test offline `ml-IN` recognition on the iQOO 15. If it is not available offline, Malayalam uses typed input or online recognition, and the UI says so.
- **Maps:** Maps Grounding Lite MCP at `https://mapstools.googleapis.com/mcp` with an API key (a free demo key exists). It provides place search, routes and weather. It does not provide turn-by-turn navigation or live traffic.
- **Optional free fallback:** OpenStreetMap through Nominatim or Photon for places and OSRM for routes. No API key, about 1 request per second, and the app must show "© OpenStreetMap contributors". Public Overpass servers are too unreliable for the demo.
- **Not used:** Zomato (third-party apps blocked), Uber, Rapido and Ola (no usable official MCP), and community WhatsApp bridges (break WhatsApp's terms). Person mode uses Android share and SMS intents.

## Open items during the build

- [x] Email builders@swiggy.in to register the `nila://oauth` redirect (sent 9 October; awaiting reply).
- [ ] Build the loopback `http://localhost` OAuth fallback and test it on the event network.
- [ ] Measure EmbeddingGemma 2 load time and RAM on the iQOO 15 (try the SM8850 build first).
- [ ] Write Hindi and Malayalam routing examples plus a held-out test set.
- [ ] Test Malayalam speech recognition on the iQOO 15 with networking disabled.
- [ ] Get a Maps Grounding Lite API key.
- [ ] Confirm the loan iQOO 15 RAM and available storage.
- [ ] Confirm Swiggy OAuth and redirect behavior on the event network.
- [ ] Download and verify offline speech packs for English, Hindi and Malayalam.
- [ ] Measure every on-device claim before adding it to the submission.

## Parked pitch items

- Canva cleanup and replacement artwork.
- Final verified performance claims.
- JIMI City Battle placement.
- Video walkthrough, final PDF and proficiency-level answers.
