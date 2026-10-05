# SANKET design

**Feel:** a calm co-pilot. The screen is glanced at for half a second, often on a moving scooter, so the status
comes first, in a few words, and everything else is quiet until it is needed.

## Principles
- **One focal point.** The status card is the only coloured block on the screen. Everything else is neutral.
- **Colour always comes with words.** Green, amber, orange and red mean the same thing everywhere, and each one is
  paired with a sentence, so the screen still works for colour-blind users and in sunlight.
- **Say what happens next.** Buttons say what they do ("Use new link", "Try now"), and every problem shown on screen
  comes with the button that fixes it.
- **Show detail only while it helps.** The signal section appears only while protection runs.

## Colour (light / dark)

| Token | Light | Dark | Use |
|---|---|---|---|
| `bg` | `#F5F6F8` | `#0E1115` | Screen background |
| `surface` | `#FFFFFF` | `#161A20` | Cards |
| `outline` | `#DCE1E8` | `#2B323C` | Card borders, dividers |
| `text` / `text_2` / `text_3` | `#141A23` / `#47505D` / `#687180` | `#E7EBF0` / `#B3BCC8` / `#8C97A6` | Text, by importance |
| `accent` | `#1D5FD1` | `#8FB7FF` | Actions only |
| `status_clear` | `#0F7A3B` | `#5BD08A` | Signal is steady |
| `status_watch` | `#8A5800` | `#F0C04D` | Signal is changing; things to fix |
| `status_protect` | `#B3420C` | `#FF9C66` | Signal may drop soon |
| `status_offline` | `#B42318` | `#FF8A80` | No signal; failed downloads |
| `status_off` | `#5B6472` | `#A3ACB9` | Off, paused, airplane mode |

Each status colour has a light tint for its card. Text colours meet WCAG AA contrast on their backgrounds in both
themes. The dark theme is designed on its own, not inverted: surfaces get lighter as they come forward, and status
colours are lighter tones of the same hues.

## Type
The system sans-serif in two weights. Sizes: 13 (captions), 15 (body), 16 to 17 (titles), 22 (app name),
26 (status). Numbers that change use tabular figures, so they do not jump.

## Space and shape
An 8 dp grid: 20 dp screen margins, 32 dp between sections, 12 dp inside a group. One corner radius, 16 dp, for
cards and buttons. No shadows; cards have a 1 dp border.

## Motion
Buttons dip to 97% within 100 ms when pressed. The status colours fade over 220 ms, and the chance-of-loss bar
eases to its new value over 300 ms. With animations turned off in the system settings, everything simply jumps.

## Words
Plain, short and specific: "Signal may drop soon", not "Link degradation detected". Numbers carry their meaning
("Strong, -92 dBm"). No error codes without an explanation and a way forward.
