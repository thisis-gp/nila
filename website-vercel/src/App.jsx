import { useEffect, useRef, useState } from 'react'
import { ArrowDown, Check, ChevronRight, MapPin, Mic, ShieldCheck, Sparkles } from 'lucide-react'

const demos = {
  food: {
    label: 'Order food',
    prompt: 'Chicken biryani under ₹250 in 30 minutes',
    response: 'I found 12 options. These two balance price, rating and arrival time.',
    cards: [
      { name: 'Meghana Foods', detail: 'Chicken boneless biryani', meta: '4.6 · 24 min', price: '₹239', best: true },
      { name: 'Mani’s Dum Biryani', detail: 'Chicken dum biryani', meta: '4.4 · 29 min', price: '₹219' },
    ],
  },
  message: {
    label: 'Message someone',
    prompt: 'Tell Arjun I’ll reach the studio by 6:30',
    response: 'Arjun is on WhatsApp. I’ve prepared the message and kept your usual tone.',
    cards: [
      { name: 'Arjun Menon', detail: '“Running a little late — I’ll be at the studio by 6:30.”', meta: 'WhatsApp · Draft', price: 'Review', best: true },
    ],
  },
  ride: {
    label: 'Get somewhere',
    prompt: 'Get me to Koramangala before 5:15',
    response: 'Traffic is building. Metro + auto is 11 minutes faster than a cab.',
    cards: [
      { name: 'Metro + Auto', detail: 'Indiranagar → Trinity → Koramangala', meta: 'Arrive 5:08 · 2 changes', price: '₹94', best: true },
      { name: 'Cab', detail: 'Door to door', meta: 'Arrive 5:19 · traffic', price: '₹286' },
    ],
  },
}

const steps = [
  ['01', 'Understand', 'NILA turns ordinary language into a clear intent, then asks only when something important is missing.'],
  ['02', 'Compare', 'Services compete in one neutral view. Price, time and trade-offs stay visible.'],
  ['03', 'Confirm', 'Nothing leaves your phone until you approve the exact action.'],
]

function Demo({ compact = false }) {
  const [mode, setMode] = useState('food')
  const [phase, setPhase] = useState('results')
  const timer = useRef()
  const data = demos[mode]

  useEffect(() => () => clearTimeout(timer.current), [])

  function run(next = mode) {
    clearTimeout(timer.current)
    setMode(next)
    setPhase('thinking')
    timer.current = setTimeout(() => setPhase('results'), 650)
  }

  function confirm() {
    setPhase('confirmed')
    timer.current = setTimeout(() => setPhase('results'), 1600)
  }

  return (
    <div className={`device ${compact ? 'device--compact' : ''}`} aria-label="Interactive NILA operating system demo">
      <div className="device__bar"><span>9:41</span><span className="device__name">NILA</span><span>78%</span></div>
      <div className="device__body">
        <div className="modes" role="tablist" aria-label="Try an intent">
          {Object.entries(demos).map(([key, item]) => (
            <button key={key} className={mode === key ? 'active' : ''} onClick={() => run(key)}>{item.label}</button>
          ))}
        </div>

        <div className="conversation">
          <p className="eyebrow">YOU</p>
          <h2>{data.prompt}</h2>
          <button className="voice" onClick={() => run()} aria-label="Run this request"><Mic size={18} /> Ask NILA</button>
        </div>

        <div className="answer" aria-live="polite">
          {phase === 'thinking' ? (
            <div className="thinking"><i /><i /><i /><span>Checking services on your device</span></div>
          ) : phase === 'confirmed' ? (
            <div className="confirmed"><span><Check size={24} /></span><strong>Approved</strong><small>The action is ready.</small></div>
          ) : (
            <>
              <p>{data.response}</p>
              <div className="result-list">
                {data.cards.map((card) => (
                  <button className="result" key={card.name} onClick={confirm}>
                    <span className="result__copy">
                      <b>{card.name} {card.best && <em>BEST FIT</em>}</b>
                      <span>{card.detail}</span><small>{card.meta}</small>
                    </span>
                    <span className="result__price">{card.price}<ChevronRight size={16} /></span>
                  </button>
                ))}
              </div>
            </>
          )}
        </div>
      </div>
      <div className="composer"><Sparkles size={17} /><span>What do you want to do?</span><Mic size={18} /></div>
    </div>
  )
}

function App() {
  return (
    <main>
      <nav className="nav">
        <a className="brand" href="#top" aria-label="NILA home"><span>N</span>NILA</a>
        <div className="nav__links"><a href="#system">System</a><a href="#principles">Principles</a><a href="#roadmap">Roadmap</a></div>
        <a className="nav__cta" href="#early">Join early access</a>
      </nav>

      <section className="hero" id="top">
        <div className="hero__copy">
          <p className="kicker">AN INTENT-FIRST OPERATING SYSTEM</p>
          <h1>Your phone should start with <em>what you mean.</em></h1>
          <p className="lede">No app hunting. No tab switching. Tell NILA what you want, compare the options, and approve the action.</p>
          <div className="hero__actions"><a className="button" href="#system">Try the system</a><span>Launcher first.<br />Independent OS next.</span></div>
        </div>
        <div className="hero__demo"><Demo /></div>
        <a className="scroll-cue" href="#problem"><ArrowDown size={18} /> See the problem</a>
      </section>

      <section className="problem" id="problem">
        <figure>
          <img src="/assets/delhi-phone.jpg" alt="A person using a smartphone beside an auto-rickshaw in Delhi" />
          <figcaption>Delhi, India · Photo by Ravi Sharma / Unsplash</figcaption>
        </figure>
        <div className="problem__copy">
          <p className="kicker">THE INTERFACE IS BACKWARDS</p>
          <h2>You have one intention.<br />Your phone gives you twelve apps.</h2>
          <div className="app-strip" aria-label="Typical app switching"><span>Maps</span><span>Food</span><span>Pay</span><span>Chat</span><span>Ride</span><span>Search</span></div>
          <p>People think in outcomes—not brands, menus, or app hierarchies. NILA makes intent the home screen and turns apps into services behind it.</p>
        </div>
      </section>

      <section className="system" id="system">
        <div className="system__intro"><p className="kicker">ONE CONVERSATION. REAL ACTIONS.</p><h2>The interface changes.<br />Your control does not.</h2></div>
        <div className="walkthrough">
          <div className="walkthrough__device"><Demo compact /></div>
          <div className="steps">
            {steps.map(([number, title, text]) => <article key={number}><span>{number}</span><h3>{title}</h3><p>{text}</p></article>)}
          </div>
        </div>
      </section>

      <section className="services" aria-label="Service categories">
        <p>APPS BECOME SERVICES</p>
        <div className="ticker"><div>FOOD · RIDES · PEOPLE · MONEY · MUSIC · PLACES · WORK · FOOD · RIDES · PEOPLE · MONEY · MUSIC · PLACES · WORK ·</div></div>
        <p className="services__note">One language for everything your phone can do.</p>
      </section>

      <section className="principles" id="principles">
        <div><p className="kicker">PRIVATE BY ARCHITECTURE</p><h2>Your context belongs on your device.</h2></div>
        <div className="principle-grid">
          <article><span><ShieldCheck /></span><h3>Read locally</h3><p>Personal context stays on-device whenever possible.</p></article>
          <article><span><Sparkles /></span><h3>Prepare, don’t presume</h3><p>NILA drafts the action. You make the decision.</p></article>
          <article><span><Check /></span><h3>Commit with consent</h3><p>Payments, messages and bookings need explicit approval.</p></article>
        </div>
      </section>

      <section className="roadmap" id="roadmap">
        <p className="kicker">THE PATH</p>
        <div className="roadmap__line">
          <article><b>NOW</b><h3>Android launcher</h3><p>Prove the intent layer on phones people already own.</p></article>
          <article><b>NEXT</b><h3>System runtime</h3><p>Let services expose actions without exposing your data.</p></article>
          <article><b>THEN</b><h3>NILA OS</h3><p>An independent operating system built around people, not apps.</p></article>
        </div>
      </section>

      <section className="closing" id="early">
        <div><p className="kicker">BUILD THE POST-APP PHONE</p><h2>The home screen is a conversation.</h2></div>
        <a className="button button--light" href="mailto:hello@nila.systems">Talk to the team</a>
      </section>

      <footer><a className="brand brand--light" href="#top"><span>N</span>NILA</a><p>Intent-first computing, built in India.</p><a href="https://unsplash.com/es/fotos/mujer-en-camisa-blanca-de-manga-larga-de-pie-junto-al-autobus-verde-durante-el-dia-nXZmcTzOC1E" target="_blank" rel="noreferrer">Photo: Ravi Sharma / Unsplash</a></footer>
    </main>
  )
}

export default App
