# DESIGN.md · SANKET website ("Liquid Glass")

One page that sells one idea: **you lose signal at the worst moment; SANKET warns you before it happens.** One
piece of liquid glass carries the whole story and is always moving. It becomes each scene's object in turn:
signal bars that die → (pulled out of them as a string of liquid that flies across the screen) an old lift car
that climbs as you scroll → at the top it melts, falls and lands as a delivery scooter that rides ahead leaving
smoke → it slides into the study's test ride as columns → a phone whose download waits out the drop → (a string
again) a pin on a map → a padlock that locks → full signal bars. Every paragraph has its own object.

## Feel
Glass objects on warm paper, lit like product photographs: dark-edged, clear, softbox highlights, light shadows
with a bright tinted middle. Calm and exact; the glass does the talking, the type stays quiet. Built from the
subject's own world: signal bars, an old Mumbai cage lift, a delivery scooter, the study's test ride, the app's
download screen.

## Colour (one visual world, light by choice)
| Token | Hex | Use |
|---|---|---|
| `--paper` | `#F3F0E8` | page and the paper the glass stands on (the canvas is see-through except where glass, shadow or smoke is) |
| `--paper-2` | `#EBE6DA` | the last call band |
| `--card` | `#FBFAF6` | QR card, labels on the glass |
| `--ink` | `#16181D` | headings, buttons (paper text on ink, 15.6:1) |
| `--ink-2` / `--ink-3` | `#3C3E44` / `#64666C` | body / fine print (9.4:1 / 5.0:1 on paper) |
| `--good` / `--watch` / `--lost` | `#1F9D57` / `#E8A317` / `#D93A2F` | signal states only: glass that carries meaning, label dots |

Glass is clear by default, and clear while it flies. Tint means something: green for a strong signal (and the
padlock), amber and orange as it falls, red when it is gone (and the pin); the lift's dial is amber and the
delivery box orange. The dying bar glows red; the phone's screen is lit.

## Type
- Display: **Instrument Serif** 400, headlines only, leading 1.0, −0.012em. Hero 44–82px, scenes 37–64px.
- Text: **Geist** 400/500/600. Body 17/1.6 (16 on phones), lead 16–18px, max 34em.
- Labels: Geist 13px, uppercase, +0.08em, with a scene counter in tabular figures (`03 / 08`).
- **संकेत** in Tiro Devanagari Hindi (subset of five glyphs), beside the wordmark only.

## Layout
- Sticky stage (100svh) for the story: 8 scenes over ~900svh of scroll (860 on phones), about a screen each.
- Desktop: words left (max 31rem), glass right. Phone: glass in the top half, words at the bottom on a paper fade.
  Between scenes there are no words: the string uses the whole screen.
- After the story: questions (two columns on desktop), a last call with a QR code for computer visitors, footer.
- One radius: 14px (buttons). Pills only for the labels on the glass.

## 3D (our own WebGL2 engine, no libraries: `glass.js`, `glass-glsl.js`, `story.js`)
- Ray marched glass: one distance function holds the scene's object, the drop it melts into and the string of
  liquid it travels as, so melting, pouring and forming are smooth. Refraction in and out of the glass with a
  little dispersion, tint by thickness, a studio of soft boxes and dark cards to reflect.
- Sharp at the screen's own resolution (2x on a Retina Mac), drawn in five passes so that's affordable: the
  shadow into a small texture; a coarse pass at a third of the resolution that finds where the glass is; the
  paper only where it's marked; a stencil of the pixels near glass; then the glass itself, finished from the
  coarse pass's start at full resolution with its edge softened over one pixel. The four costly still parts (lift
  car, scooter body, map pin, padlock body) are measured once into small 3D textures at load.
- `story.js` turns the scroll position (followed on a spring, so it never jumps) and the clock into the scene:
  the lift climbs and its needle turns as you scroll; the scooter rides ahead, its wheels turning with the
  distance, puffing smoke that stays behind; the bars die on load; the phone's download waits out a drop and
  carries on; the pin's ripple spreads; the padlock shuts as you scroll; the last bars wave.
- Changes: a string of liquid pulled out of the old shape that flies across the screen (bars → lift, phone →
  pin), a fall (lift → scooter), a slide (scooter → the ride), a drop (the rest). The new shape fills from the
  bottom like a mould. Tap: a ripple runs through the glass. Mouse: the view leans.
- The ride's columns are the study's simulated test ride, the one the app's demo replays, in 8 s steps (two
  readings per column) so twelve chunky columns stand well apart: thin columns packed close read as black lines.
  The warning post stands in the gap at 64 s. The labels "63 s SANKET warns" and "79 s Signal gone" are HTML
  pinned to points on the glass while the columns stand.
- Speed: it times the chip before the first frame and picks the richest of four settings that holds 60 frames a
  second (dispersion, steps, then resolution); it steps down if frames still run slow. Measured on the M1 at
  2880x1560: 3.7–13.4 ms a frame at the richest setting. Pauses off screen and in hidden tabs.
- No flash on load: a one-line script in `<head>` picks the motion layout before the first paint; the canvas is
  see-through, so the page is complete at once and the glass pours itself in (a drop falls and the bars grow).
- Reduced motion or no WebGL2: still pictures of each scene (rendered by the same engine), same words.

## Motion
`--ease-out: cubic-bezier(.23,1,.32,1)`. Words fade and rise 14px in 500–600ms while their object rests;
buttons press to 0.97; hover only on `(hover: hover)`. Scrolling is the browser's own, never hijacked: the glass
follows it on a quick spring, and keeps moving on its own (ripples, smoke, needle, the download) when you stop.

## Don't
Fake reviews or user counts, "unlock/seamless/revolutionise", feature-card grids with icons, purple gradients,
claims the study doesn't support. Every number on the page comes from the study or the release.
