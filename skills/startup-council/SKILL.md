---
name: startup-council
description: Stress-test and refine a startup or product idea through independent investor, customer, execution, technical, and contrarian lenses, followed by peer challenge and one evidence-aware BUILD, PIVOT, or STOP recommendation. Use when asked to validate, critique, roast, refine, or convene a council on an idea.
---

# Startup Council

Evaluate the idea, not the founder. Seek disconfirming evidence and finish with a smaller, testable version of the strongest surviving idea.

This workflow combines the independent-review and peer-ranking pattern popularized by [Karpathy's LLM Council](https://github.com/karpathy/llm-council) with startup evaluation lenses. Institutional labels are archetypes, not impersonations or endorsements.

## Evidence rules

- Separate **Fact**, **Assumption**, and **Test**. Never turn an assumption into a market fact.
- Scores are heuristic decision aids, not predictions of funding or success.
- Name concrete failure modes. Avoid generic claims such as "execution is key".
- Use current sources for market, competitor, platform, policy, pricing, or adoption claims when those claims affect the verdict.
- Missing evidence lowers confidence; it does not automatically prove the idea is bad.

## Minimum brief

Establish the problem, specific first user, solution, current stage, team advantage, business-model hypothesis, and smallest wedge. If three or more are unknown, ask only the questions needed to fill them before convening the council.

## Council workflow

When parallel agents are available, run the three panels independently. Otherwise evaluate them sequentially without letting later panels rewrite earlier conclusions.

### 1. Founder and early-demand panel

Use three archetypal lenses:

- **YC-style:** urgent pain, narrow wedge, user pull, speed and potential to expand.
- **Antler-style:** founder-problem fit, complementary team, ability to reach a real MVP quickly.
- **Lean experimenter:** customer discovery, falsifiable assumptions, distribution and willingness to pay.

### 2. Market and strategy panel

Use these lenses:

- **Venture strategist:** why now, market structure, expansion path and defensibility.
- **GTM operator:** first reachable users, acquisition channel, buyer, switching trigger and business model.
- **Contrarian:** argues that the problem is weak, incumbents win, the opposite product is better, or nothing should be built.

### 3. Product and system panel

Use these lenses:

- **User advocate:** actual workflow improvement, trust, accessibility, habit change and failure recovery.
- **Pragmatist:** smallest experience that proves the thesis without speculative infrastructure.
- **Architect and risk critic:** platform constraints, privacy, security, permissions, reliability and irreversible choices.

Each panel must return:

1. Strongest reason to proceed.
2. Strongest reason to stop or pivot.
3. Three named failure modes.
4. Two killer questions.
5. The smallest validation test.
6. Confidence from 1–10 and what evidence would change it.

### 4. Peer challenge

Give each panel the other panels' conclusions without author identity where practical. Ask it to identify unsupported claims, rank the strongest objection, and state what it would change in its own position. Preserve real disagreement instead of forcing consensus.

### 5. Synthesis

Score these dimensions from 1–10: problem intensity, first-user clarity, differentiation, timing, distribution, defensibility, team fit, business model, and execution feasibility. Label the scorecard **heuristic**.

Choose exactly one verdict:

- **BUILD:** the wedge is clear enough to test now.
- **PIVOT:** a nearby problem or wedge is stronger than the current framing.
- **STOP:** no credible low-cost test or valuable problem remains.

## Output

Return:

1. **One-sentence thesis**
2. **Heuristic scorecard**
3. **What survived the attack**
4. **Strongest objections and disagreements**
5. **Refined idea** — user, problem, promise and boundary
6. **Smallest wedge**
7. **Next validation tests** — each with hypothesis, method, success threshold and stop condition
8. **Verdict and confidence**
9. **Killer questions still unanswered**

For a hackathon, separate what proves the product thesis from theatrical breadth. Prefer one convincing end-to-end journey over many disconnected demos.

## Boundaries

- Do not modify product code, publish documents, contact users, spend money, or create external resources unless separately authorized.
- Do not claim to speak for YC, a16z, Antler, Harvard, or any named person.
- Do not manufacture TAM, CAC, conversion, retention, user-interview, or benchmark numbers.
