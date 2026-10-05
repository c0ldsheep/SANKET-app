# SANKET features

SANKET predicts a mobile-signal drop a few seconds before it happens, so the phone can save what matters
first. Every feature below says when it helps, what SANKET does, and how far it has got.

**Status labels**
- **Tested in the study**: measured in the PRAYAS research (`research/`), with numbers from unseen test data.
- **Built and unit-tested**: in the Android app (`app/`), with automated tests that pass.
- **Checked on an emulator**: run on an Android 13 emulator, with real downloads over the internet.
- **Built**: in the app and passing Android's code checker, but not yet tried on real phones in real buildings.
- **Planned**: needs the shared fleet server, which is not built yet.

The app has been checked by 44 automated tests, by Android's lint tool and on an Android 13 emulator. It has
not yet been tested on real phones in real buildings; that field test is the next step.

---

## A. Seeing the drop coming

### 1. Signal trend watcher
- **When it helps:** phones report signal in rough steps, often repeating an old value for 2 to 3 seconds.
- **What SANKET does:** a Kalman filter smooths the readings and tracks how fast the signal is falling, in dB per
  second, together with how sure it is.
- **Status:** Tested in the study. Built and unit-tested (the app's detector gives exactly the same output as the
  study's Python code on all 1,737 reference readings).

### 2. Early warning with a probability
- **When it helps:** a rider is about to go down a basement ramp.
- **What SANKET does:** every second it works out the chance of having no signal (below -124 dBm) 10 seconds from
  now. At 70% or more, with the signal falling by at least 1 dB/s, it raises the alarm. The screen shows the chance
  as a bar with a mark at 70%.
- **Result:** on unseen data it warned at least 10 s ahead in 43.8% of basement entries, against 10.2% for a simple
  threshold, with 1.76 false alarms per hour on real rides.
- **Status:** Tested in the study. Built and unit-tested.

### 3. Ignoring handover zones (neighbour check)
- **When it helps:** the signal dips while riding past a building or between two towers, but nothing is wrong.
- **What SANKET does:** inside concrete every nearby tower fades together. If a neighbouring tower stays steady,
  SANKET cancels the alarm. It asks the mobile-data SIM's own radio for a fresh list of towers every two seconds,
  because the list Android keeps for apps can be out of date.
- **Result:** at 1 false alarm per hour, detection rose from 29.7% to 37.1%.
- **Status:** Tested in the study. Built.

### 4. Most important data first
- **When it helps:** there are only a few seconds before the signal goes.
- **What SANKET does:** downloads in order of importance (order token and OTP, customer details, route, map tiles).
  Data that has not changed costs only a short "not modified" reply, about 1.6 KB per false alarm.
- **Result:** the critical data needs only 3 s of warning; SANKET got it onto the phone before the loss in 80.2% of
  entries (simple threshold: 46.4%).
- **Status:** Tested in the study (download model). In a delivery app this needs the company's server, so it is
  Planned for the app; the app protects ordinary downloads instead (feature 6).

### 5. Places the fleet remembers
- **When it helps:** the same basements and cloud kitchens cause trouble every day.
- **What SANKET does:** once two different trips lose signal at an entrance, every rider heading there is warned
  before the signal even starts to fall.
- **Result:** in a 7-day fleet simulation, warnings of at least 10 s rose from 76% on day 1 to 95% on day 7, above the
  85% target from day 2.
- **Status:** Tested in the study (simulation). On one phone it is Built (features 9 to 12); sharing between phones
  is Planned.

---

## B. Keeping your work safe

### 6. Protected downloads
- **When it helps:** you are downloading something, step into a lift, and the signal dies. Normally the download
  fails and starts again from zero.
- **What SANKET does:** writes straight into Downloads/SANKET as the bytes arrive, so a finished file needs no copy
  and no extra space. It saves progress every second, and at once when it expects a drop. When the signal is back
  it asks the server only for the missing part, and checks that the file has not changed (ETag or date), so parts
  of two different versions are never joined. There are no error pop-ups: the download waits and continues by
  itself, and each download shows plain buttons: Open, Try now, Use new link, Cancel, Clear.
- **Result on the emulator:** an 8.6 MB download cut three times, once by airplane mode, finished byte for byte
  identical to a direct download (same SHA-256).
- **Status:** Checked on an emulator. File names, link cleaning and retry timing are unit-tested.

### 7. Share a link to download it safely
- **When it helps:** you find a file in your browser or WhatsApp.
- **What SANKET does:** "Share" the message to SANKET and it finds the link inside it, without the full stop or
  bracket that ends the sentence. Only secure https links are accepted.
- **Status:** Built and unit-tested. Checked on an emulator.

### 8. Demo trip
- **When it helps:** showing the project without going to a basement.
- **What SANKET does:** replays the study's test trip (a scooter going down a basement ramp) through the real app,
  clearly labelled "Demo replay", while it downloads a 4 MB sample map. The alarm fires at 63 s, 16 s before the
  signal disappears at 79 s, with the real warning marked "Demo". The download pauses when the replayed signal
  dies and finishes from the same point when the demo ends. Real protection carries on afterwards if it was on.
- **Result on the emulator:** the sample paused at 2.7 MB of 4.0 MB, continued from there, and finished byte for
  byte identical to the original (same SHA-256).
- **Status:** Checked on an emulator.

### 22. Downloads finish after a restart
- **When it helps:** the phone restarts, a battery saver stops SANKET, or Android's six-hour daily limit for
  background work runs out, all in the middle of a download.
- **What SANKET does:** while downloads are unfinished, it keeps one Android background job waiting for a network.
  The job continues the downloads by itself, without anyone opening the app, and removes itself when they are done.
- **Result on the emulator:** a download interrupted by a phone restart finished about 10 seconds after the phone
  came back, byte for byte identical to a direct download.
- **Status:** Checked on an emulator.

### 23. Busy servers, expired links and web pages
- **When it helps:** a server is overloaded, a download link has expired, or a link opens a web page (such as a
  login page or Google Drive's "can't scan this file" page) instead of the file.
- **What SANKET does:** tries busy or unreachable servers again by itself after 30 s, 1, 2, 5 and then every
  10 minutes, and says when. An expired link gets a "Use new link" button that continues from where it stopped.
  A web page is never saved as the download; SANKET explains what to do instead.
- **Status:** Built and unit-tested.

### 24. Space and data care
- **When it helps:** the phone is nearly full, or the day's data pack is small.
- **What SANKET does:** checks the free space before writing and says exactly how much is needed. A download can be
  set to "Only download on Wi-Fi". File names come from the server, keep Hindi and Marathi letters, and get the
  right extension.
- **Status:** Built and unit-tested.

---

## C. Knowing the place

### 9. Lift and floor awareness
- **When it helps:** lifts in tall buildings cut the signal for 30 to 90 seconds, often just before you need it.
- **What SANKET does:** air pressure drops about 0.12 hPa for every metre you go up, so the phone's barometer shows
  vertical speed. A lift moves at 1 to 2.5 m/s, much faster than stairs. Fast vertical movement makes SANKET save
  progress straight away, and each lost-signal place is labelled lift, basement or indoors.
- **Status:** Built and unit-tested. Phones without a barometer skip this. Needs field testing.

### 10. Places this phone remembers
- **When it helps:** you lose signal in the same lift or basement again and again.
- **What SANKET does:** after losses on two separate visits, the place becomes "known", and SANKET protects your
  downloads as soon as you are back there, before the signal moves. Places show the network by name, such as Jio.
- **Status:** Built and unit-tested.

### 11. Notes for a place
- **When it helps:** a big society or mall where finding the right gate or lift takes minutes.
- **What SANKET does:** you can add a short note to a place ("use gate 3; lift B has no signal"). It shows up the
  next time you are there. Phone numbers in notes are removed automatically.
- **Status:** Built and unit-tested on this phone. Sharing notes so the next rider sees them is Planned.

### 12. Places that update themselves
- **When it helps:** a building gets a new tower or an indoor booster, and the old warning becomes wrong.
- **What SANKET does:** visits that keep signal lower a place's score, and all counts halve every 30 days. Places
  that most visits now pass with signal, or that have faded out, are deleted.
- **Status:** Built and unit-tested.

---

## D. Network and phone smarts

### 13. "Signal is fine, but the internet is dead"
- **When it helps:** festival crowds jam the network, a Wi-Fi login page blocks traffic, or the data pack runs out.
- **What SANKET does:** checks whether the connection really reaches the internet and tracks how fast its own
  requests are answered. If responses keep getting slower, it saves progress early. If the connection is not
  working at all, it says so plainly ("check your data pack or the Wi-Fi login page") instead of failing silently.
- **Status:** Built and unit-tested.

### 14. Dual SIM
- **When it helps:** many riders carry two SIMs, and a basement that is dead on one network can be fine on the other.
- **What SANKET does:** reads both SIMs, remembers dead places separately for each network, and suggests switching
  mobile data when the other SIM is clearly better at that spot. When mobile data moves to the other SIM, the trend
  starts afresh, so the jump is not mistaken for a fall.
- **Status:** Built. Needs field testing.

### 15. 5G early warning
- **When it helps:** on 5G phones that keep 4G running alongside (such as Airtel's 5G), near a building entrance.
- **What SANKET does:** 5G at 3.5 GHz gets through concrete worse than 4G, so it usually fades first. When a weak 5G
  reading disappears or drops fast while 4G is still fine, SANKET saves progress early. It never raises the full
  alarm on this alone, because field data must confirm it first.
- **Status:** Built and unit-tested. Needs field confirmation.

### 25. Standalone 5G (Jio True 5G)
- **When it helps:** Jio's 5G runs without 4G underneath, so the phone reports no 4G reading at all.
- **What SANKET does:** feeds the 5G signal into the same detector, with the 5G towers nearby for the neighbour
  check, and labels it "5G standalone, experimental" until real recordings confirm the loss level.
- **Status:** Built. Needs field data.

### 26. Honest on 2G, 3G and airplane mode
- **When it helps:** the phone falls back to 2G or 3G, the user switches on airplane mode, or there is no SIM.
- **What SANKET does:** on 2G or 3G it shows the real signal and says it predicts drops on 4G and 5G only. Airplane
  mode and a missing SIM are choices, not signal losses: nothing goes into the safety log and no warning appears.
  With mobile data off, waiting downloads say so instead of "waiting for signal".
- **Status:** Checked on an emulator.

### 16. Learns your phone
- **When it helps:** phones differ: some refresh the signal every second, others every 5 seconds. The study showed
  this matters (47.9% of entries warned in time with 1 s updates, 29.3% with 5 s updates).
- **What SANKET does:** measures how often your phone refreshes signal and how noisy it is, and sets its own timing
  to match.
- **Status:** Built and unit-tested.

### 17. Light on battery and data
- **When it helps:** riders switch off anything that drains the battery or eats data.
- **What SANKET does:**
  - Runs only while protection is on or a download is running, and stops itself 2 minutes after the last download.
  - In Battery Saver or below 15%, it turns off GPS and uses network location only.
  - Reads the barometer in batches; the detector itself takes about 1.4 µs per reading (measured in the study).
  - Saves download progress by time, not every megabyte, so fast 5G downloads are not slowed down.
- **Status:** Built.

---

## E. Safety and trust

### 18. Safety log
- **When it helps:** someone falls or gets into trouble in an empty basement, and later the family or the police
  need to know when and where the phone lost contact.
- **What SANKET does:**
  - Records every signal loss longer than 10 seconds, and the recovery, with the time, a rounded position and the
    last signal level. Shorter dropouts do not fill the log.
  - Each line is locked to the one before it with a SHA-256 hash, so a changed or deleted line is detected.
  - Offline for more than 10 minutes, much longer than usual for that place: the app shows an alert when back.
  - The log can be shared with one tap, after a reminder that it contains times and places.
- **Status:** Built and unit-tested. Alerting a safety team while the rider is still offline is Planned.

### 19. Telling dispatch before going quiet
- **When it helps:** a delivery platform marks a rider "unresponsive" or reassigns the order while they are in a lift.
- **What SANKET does:** just before the drop it prepares a short message: "going offline at a known dead zone, back in
  about N minutes", with N learned from that place. The screen shows that it is ready.
- **Status:** Built and unit-tested (the message is prepared and logged). Sending it is Planned.

### 20. Fake GPS and bad reports refused
- **When it helps:** someone tries to plant fake dead zones, for example to excuse delays.
- **What SANKET does:** a report is refused if the location is from a fake-GPS app, if the position is vaguer than
  50 m, or if the phone did not record a real signal drop (or a lift or ramp movement) just before.
- **Status:** Built and unit-tested. Device checks for the shared map (Google Play Integrity) are Planned.

---

## F. Privacy

### 21. Privacy by design (DPDP Act 2023)
- **When it helps:** always. Location and signal data are personal.
- **What SANKET does:**
  - Asks for consent in plain language before it starts, and explains in advance why Android's permission screen
    mentions phone calls (SANKET only uses it to see the SIM cards).
  - Keeps everything on the phone, encrypted with AES-256 using a key that cannot leave the phone.
  - Never backs data up to the cloud.
  - Rounds positions to about 11 m and keeps the safety log for 30 days.
  - Deletes everything with one tap.
  - There is no account and nothing is uploaded, and the app uses no third-party libraries.
- **Status:** Built.

---

## G. Everyday use

### 27. Status at a glance
- **When it helps:** a rider glances at the phone for half a second.
- **What SANKET does:** one card says what is happening in a few words, in a colour that always comes with those
  words: green "Signal is steady", amber "Signal is changing", orange "Signal may drop soon", red "No signal",
  grey when protection is off or paused. Below it, the chance of losing signal, and below that every detail in
  plain language ("Strong, -92 dBm"). Designed separately for light and dark mode.
- **Status:** Checked on an emulator.

### 28. Hints that fix problems
- **When it helps:** notifications are off, Location is off or only approximate, or the phone's battery saver is
  known to stop background apps (Xiaomi, Samsung, OnePlus, Realme, OPPO, vivo and others).
- **What SANKET does:** shows a short note with the button that fixes it, and step-by-step battery settings for the
  phone's brand. It does not blame the battery saver after a normal phone restart.
- **Status:** Checked on an emulator.

### 29. Controls without opening the app
- **When it helps:** you are about to step into a lift and have no time to open apps.
- **What SANKET does:** a Quick Settings tile turns protection on or off from the notification shade. The status
  notification shows download progress and a Stop button, warnings vibrate twice so riders feel them, at most one
  every three minutes, and a finished download gets a notice that opens the file.
- **Status:** Checked on an emulator.

---

## Next steps
1. **Field test:** basements and lifts in Navi Mumbai on Jio, Airtel and Vi, recorded with the phone tool in
   `research/live`, to confirm features 9, 14, 15 and 25 and tune their thresholds.
2. **Fleet server:** shared places and notes, the dispatch notice and safety alerts, with Play Integrity checks.
3. **Hindi and Marathi** screens.
4. **Website with early sign-up**, then a Play Store release.
