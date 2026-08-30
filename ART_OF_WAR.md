# The Art of War and Battlecode: A Cross-Millennium Synthesis

Sun Tzŭ's *The Art of War* (c. 5th century B.C.) is a treatise on managing armies with
scarce information, scarce resources, and an adversary who is actively trying to deceive
you back. Battlecode strips a real war down to exactly that core: two programs, a shared
64-int communication channel, a fog-of-war vision model, and a resource economy that
punishes hesitation. The abstraction is different — chariots and infantry instead of
Miners and Sages — but the *decision problem* Sun Tzŭ is solving turns out to be
strikingly close to the one this bot has spent 93 iterations solving.

This document draws direct lines between Sun Tzŭ's maxims and concrete decisions in this
project's own code and training history (see `TRAINING_LOG.md`), in the same spirit as
`RESEARCH.md`'s synthesis of modern Battlecode postmortems — except the source here
predates the game itself by about 2,500 years.

**Source:** Sun Tzŭ, *The Art of War*, translated by Lionel Giles (1910), a public-domain
edition (Project Gutenberg #132). All quotations below are from that translation.

---

## 1. "All warfare is based on deception" — information asymmetry is the real battlefield

> Hence, when able to attack, we must seem unable; when using our forces, we must seem
> inactive; when we are near, we must make the enemy believe we are far away; when far
> away, we must make him believe we are near. (I.19)

Battlecode formalizes this literally: every unit has a bounded `visionRadiusSquared`, and
what you cannot see, you cannot react to. Deception in Sun Tzŭ's sense — making yourself
look weaker or farther away than you are — has a precise mechanical analogue whenever one
unit's action range exceeds another's vision range. This session found exactly such a gap
directly in the game's own numbers (`javap` against the real engine): `SAGE.
actionRadiusSquared = 25` exceeds `SOLDIER/MINER/BUILDER.visionRadiusSquared = 20` — a
literal ~1-tile band in which a Sage can strike without ever being seen. The 2022 "5
Musketeers" postmortem independently names this exact gap as their strongest late-season
strategy, in language that could be a paraphrase of Sun Tzŭ himself: "you could attack
people without them ever seeing you." Iteration 89 of this project's own bot (shelved, not
because the idea was wrong, but because our gold economy never got a live Sage onto the
board to use it) tried to build exactly this.

The broader lesson for a Battlecode bot: any asymmetry between one unit type's *reach*
(action range) and another's *awareness* (vision range) is a place where Sun Tzŭ's whole
first chapter becomes directly actionable, not metaphorical.

---

## 2. "Know the enemy and know yourself" — the actual arithmetic of confidence

> If you know the enemy and know yourself, you need not fear the result of a hundred
> battles. If you know yourself but not the enemy, for every victory gained you will also
> suffer a defeat. If you know neither the enemy nor yourself, you will succumb in every
> battle. (III.18)

This is the most quoted line in the book for good reason — it's a genuine three-way
partition of outcomes, not a platitude. Applied to this project: "knowing yourself" is
what the peer Gauntlet (mirror matches against your own bot's evolutionary history) and
the matched-subset reproduction-sample discipline exist to establish — a change is only
trusted once its effect on *our own* play is measured precisely, on a wide enough sample
that chaos in individual games washes out (a lesson learned expensively in Iteration 91,
where an 8-peer sample looked clean but a full 22-peer Gauntlet caught a real regression).
"Knowing the enemy" is what benchmark-bot testing against `sample_camelcase` and
`sample_afinals` is for — and this session's own history shows the asymmetry Sun Tzŭ warns
about directly: extensive self-knowledge (dozens of accepted iterations tuned against our
own peer lineage) coexisted for a long time with near-zero enemy-knowledge (no full
benchmark tally had been run in the entire visible session until the user asked for one),
and the moment that gap was closed, it immediately surfaced a real, previously-invisible
bug (the Archon heal-priority defect, Iteration 91) that pure self-play against a
co-evolved peer pool had never been positioned to expose.

---

## 3. "The general who wins a battle makes many calculations in his temple ere the battle is fought" — simulate before you commit

> Now the general who wins a battle makes many calculations in his temple ere the battle
> is fought. The general who loses a battle makes but few calculations beforehand. Thus do
> many calculations lead to victory, and few calculations to defeat: how much more no
> calculation at all! (I.26)

This is, almost word for word, a description of the training loop this bot runs on: no
code change reaches the live bot without a Gauntlet — a systematic set of "calculations"
(games played out under controlled, repeatable conditions) — first. The project's own
retirement-and-verification machinery (mirror checks before spending a full Gauntlet,
matched-subset reproduction samples before a full-scale run, and — after Iteration 91's
lesson — full-scale verification specifically for changes touching continuously-fluctuating
game state) is a direct, mechanized instance of "many calculations in the temple." Sun
Tzŭ's stronger claim — that the *quantity and rigor* of pre-battle calculation is itself
predictive of victory, not just correctness of the calculation — matches this project's
own empirical pattern: the iterations with the most thorough verification (full-Gauntlet
checks, direct replay tracing of the specific mechanism, re-verification after a threshold
change) are consistently the ones that survive; the ones that skipped a step (Iteration
91's first attempt at the lead threshold) are the ones that had to be walked back.

---

## 4. "First put themselves beyond the possibility of defeat" — security before offense

> The good fighters of old first put themselves beyond the possibility of defeat, and then
> waited for an opportunity of defeating the enemy. To secure ourselves against defeat lies
> in our own hands, but the opportunity of defeating the enemy is provided by the enemy
> himself. (IV.1–2)

Sun Tzŭ draws a sharp asymmetry: defense is *within your control*; offense depends on the
enemy handing you an opening. This maps directly onto one of the earliest and most durable
structural decisions in this bot's history — Iteration 9's mass-gate, which refuses to let
a lone Soldier advance toward a speculative objective until at least three friendly units
are massed, specifically because early iterations found that "trickling" units forward one
at a time got them destroyed piecemeal before they ever became a threat. That is Sun
Tzŭ's principle in miniature: don't create your own vulnerability by acting before you're
secure, no matter how tempting the opportunity looks. The same logic underlies Archon and
Watchtower defensive investment (Iteration 30 onward) — building durable structure at home
*before* over-committing offensively — and, more subtly, the entire Watchtower/Laboratory
mutate-leveling thread (Iterations 82/83/85): spending lead to make an existing structure
harder to kill is a pure "beyond the possibility of defeat" investment, with zero offensive
payoff of its own.

---

## 5. "Attack where he is unprepared, appear where you are not expected" — the weak-points-and-strong doctrine

> You may advance and be absolutely irresistible, if you make for the enemy's weak points…
> Numerical weakness comes from having to prepare against possible attacks; numerical
> strength, from compelling our adversary to make these preparations against us. (VI.10, 18)

Chapter VI is the single most Battlecode-relevant chapter in the whole book, and its
central claim — force the enemy to spread thin while you stay concentrated — shows up
twice in this bot's own targeting logic. First, `betterTarget()`'s priority ordering
(Iteration 28: enemy Soldiers before Archons; Iteration 32: Laboratory ranked second only
to Soldier, above even Sage or Archon) is a direct implementation of "attack what he must
defend, not what merely looks valuable" — killing the gold pipeline denies the enemy's
entire downstream Sage economy, which is a much larger effect than the same damage spent
on any single unit. Second, `SA_FOCUS` (Iteration 12, concentrating every Soldier's fire
onto one shared target) is the mechanical embodiment of "we shall be many to the enemy's
few" (VI.14) even when raw unit counts are even — concentrated fire kills the focus target
before it can shoot back, converting a fair fight into a favorable one purely through
coordination. Sun Tzŭ's water metaphor a few lines later — "military tactics are like unto
water… avoid what is strong and strike at what is weak" (VI.29–30) — is also a fair
description of why this project's Miner-raid mechanic (`SA_ECON_THREAT`, Iteration 22)
exists at all: an economy is almost always a softer target than a fortified army, and
harassing it forces the enemy to divert combat units to defend production, exactly the
"compelling the adversary to prepare everywhere" effect Sun Tzŭ describes.

---

## 6. The five faults of a general — a checklist this project rediscovered the hard way

> There are five dangerous faults which may affect a general: (1) Recklessness, which
> leads to destruction; (2) cowardice, which leads to capture; (3) a hasty temper, which
> can be provoked by insults; (4) a delicacy of honour which is sensitive to shame; (5)
> over-solicitude for his men, which exposes him to worry and trouble. (VIII.12)

The fifth fault is the most interesting one, because this project independently
rediscovered it as a real, severe bug rather than a mere character flaw. Sun Tzŭ's gloss
is precise: over-solicitude "does not mean that the general is to be careless of the
welfare of his troops," only that sacrificing a real military advantage to the immediate
comfort of individual soldiers is a "shortsighted policy." Iteration 91 of this bot found
*exactly* that failure mode in code: the Archon's heal-vs-build priority logic let any
unit missing more than 6 HP (a mere scratch) pre-empt building, with **no** upper bound —
so in any sustained fight near the Archon, some unit was almost always mildly wounded,
permanently starving the build queue no matter how much lead piled up (confirmed: 6,815
unspent lead in one traced game, while the whole army collapsed from neglect). The fix —
only let a wound pre-empt building when it's genuinely critical, or when there's no real
production backlog to protect — is a direct, mechanical implementation of Sun Tzŭ's
distinction between legitimate care for troops and a "shortsighted policy" that trades a
real strategic asset for a minor, individual comfort.

---

## 7. "Place your army in deadly peril, and it will survive" — desperation as a combat multiplier

> Soldiers when in desperate straits lose the sense of fear. If there is no place of
> refuge, they will stand firm… Place your army in deadly peril, and it will survive;
> plunge it into desperate straits, and it will come off in safety. (XI.24, 58)

This project independently arrived at a narrower version of the same insight. Iteration 18
found, via `--metrics` on a losing benchmark game, that Soldiers retreating at the first
scratch of damage were spending 28% of their turns fleeing and only 7% actually fighting —
an army that constantly pulls back from danger never accumulates enough sustained pressure
to win a fight. The fix (raise the retreat threshold from "any damage" to "critical HP
only, and only if not already near home") is the *bot-scale* analogue of Sun Tzŭ's
observation that soldiers who believe escape is available will take it, at the cost of
ever finishing a fight, while soldiers committed to a fight (in Sun Tzŭ's terms, on
"desperate ground") fight at full effectiveness. Han Hsin's river-crossing battle (XI.58,
note) makes the same point at army scale: he deliberately placed a division with its back
to a river — textbook bad terrain — specifically *because* it removed the option of a
half-hearted retreat and forced maximum effort. A retreat threshold set too low is,
mechanically, giving every individual unit its own permanent avenue of retreat — exactly
the condition Sun Tzŭ warns dilutes fighting spirit.

---

## 8. "There is no instance of a country having benefited from prolonged warfare" — the game clock is real

> If victory is long in coming, the men's weapons will grow dull and their ardour will be
> damped… There is no instance of a country having benefited from prolonged warfare.
> (II.2, 6)

Battlecode enforces this with an actual mechanical deadline: a 2000-round cap, with a
tiebreaker that favors whoever holds more (surviving Archons, then gold, then lead) —
meaning a bot that only knows how to grind indefinitely without a plan to end the game can
still lose a "won" position to the clock. Several of this project's own closed threads are
symptoms of exactly the problem Sun Tzŭ describes: long, close mirror-match games (the
`g_iter22-26`/valley opponent-family thread, and its later extensions to `g_iter27-39` and
to `squer`) are the games most prone to a small early edge compounding, unpredictably, over
1000+ rounds — the modern equivalent of "weapons growing dull." Sun Tzŭ's related maxim,
"one cartload of the enemy's provisions is equivalent to twenty of one's own" (II.15), also
has a precise Battlecode reading: raiding the enemy's economy (denying lead, killing
Miners) is worth far more than the same effort spent growing your own from scratch, because
it costs the enemy *and* saves you the production cost simultaneously — the same logic
behind `SA_ECON_THREAT`'s raid-response mechanism.

---

## 9. "There are roads which must not be followed… commands of the sovereign which must not be obeyed" — adaptability over fixed rules

> The general who thoroughly understands the advantages that accompany variation of
> tactics knows how to handle his troops. The general who does not understand these, may
> be well acquainted with the configuration of the country, yet he will not be able to turn
> his knowledge to practical account. (VIII.4–5)

This chapter's whole argument is that a rule which is correct in general can be actively
wrong in a specific circumstance, and a good commander must be willing to override it. This
project has run into the code equivalent of this principle repeatedly, and not always
successfully: Iteration 90's fix (lowering the Builder-economy lead-surplus gate from 300
to 120) was itself a correction of a *fixed threshold that had stood unchanged since
Iteration 30*, chosen once and never revisited even as the surrounding economy evolved
across 60 later iterations. More strikingly, Iteration 91's own two-pass history is a
compressed demonstration of the chapter's central warning: the first fix (a lead threshold
of 150) was a fixed rule that happened to be *right* in most situations and *catastrophically
wrong* in one — a single ordinary economic fluctuation on the `highway` map — because a
static number can't distinguish "genuine crisis" from "healthy economy having a normal day"
without being tied to the actual scale of the problem it's meant to catch. The corrected
version (600, calibrated against the *actual observed scale* of the pathology rather than a
round-number guess) is Sun Tzŭ's "variation of tactics" applied to threshold-tuning: know
*why* the rule exists, not just what number it currently says, so you can tell when the
number itself is the bug.

---

## 10. "Spies are the most important element in war" — vision *is* the resource

> Knowledge of the enemy's dispositions can only be obtained from other men… Spies are a
> most important element in war, because on them depends an army's ability to move.
> (XIII.6, 27)

Sun Tzŭ's closing chapter argues that all the strategic sophistication of the previous
twelve chapters is worthless without reliable information about the enemy, and that such
information cannot be obtained by pure reasoning ("it cannot be obtained inductively from
experience, nor by any deductive calculation") — it must be actively gathered. In
Battlecode this is not a metaphor: `visionRadiusSquared` is a hard mechanical limit, and
literally every strategic decision this project has made about *where the army marches
when nothing is in sight* (`armyObjective()`) is a guess standing in for the "foreknowledge"
Sun Tzŭ says only spies can provide. The entire, extensively-investigated symmetry-detection
thread (Iterations 76, 87, 87v2, 88 — four different attempts, all ultimately closed) was
this project's own, repeatedly-frustrated attempt to build a "spy": some cheap way to learn
the map's true layout before blind guessing commits the army to the wrong side of the map.
That every attempt failed for a *different* reason — reactive detection too late to matter,
Miner-reporting causing premature low-information commitments, and finally even a fully
correct, verified terrain-scout still losing more from its own economic cost than it
gained — is itself a very Sun-Tzŭ-shaped lesson: foreknowledge that costs more to obtain
than the value of the information it buys is not actually foreknowledge in the useful
sense, and Chapter XIII's own insistence that spies must be "most liberally rewarded" is,
read backwards, an implicit admission that good intelligence is never free.

---

## 11. "Move not unless you see an advantage" — the discipline against acting for its own sake

> Move not unless you see an advantage; use not your troops unless there is something to be
> gained; fight not unless the position is critical… A kingdom that has once been destroyed
> can never come again into being; nor can the dead ever be brought back to life. (XII.17,
> 21)

The starkness of that last line — no amount of subsequent cleverness undoes a genuinely
fatal mistake — is the thematic anchor for this project's whole verify-before-committing
discipline. Iteration 84 (active gold-seeking, investigated and abandoned *before* spending
any Gauntlet budget, once direct replay evidence showed the passive-detection design could
never engage often enough to matter) is the clearest single example: the hypothesis was
tested cheaply, found wanting, and dropped without ever risking a live change. The same
restraint shows up at larger scale in this project's explicit policy of accepting rejection
as a fine outcome for high-risk structural attempts (Archon relocation, the symmetry-
detection project) precisely *because* the verification step happens before acceptance, not
after — a rejected experiment costs a Gauntlet run; an *accepted* regression costs
regression debugging on top of it, and potentially a compounding one if later iterations
build on the broken foundation. Sun Tzŭ's version of the same idea — "anger may in time
change to gladness… but a kingdom that has once been destroyed can never come again into
being" — is the ancient-warfare argument for exactly the same asymmetry: reversible caution
is cheap, irreversible mistakes are not, so the bar for action should scale with how hard
the action is to undo.

---

## Closing note

None of this is to claim Sun Tzŭ anticipated shared arrays or rubble-cooldown mechanics.
The convergence runs the other way: real-time strategy under resource constraints and
imperfect information has a small number of genuinely hard sub-problems — how to act on
incomplete knowledge, how to concentrate force efficiently, how to know when a plan has
stopped being a good idea — and a text that has survived 2,500 years of being read by
people solving exactly those problems, in every medium from cavalry to silicon, turns out
to have already named most of them.
