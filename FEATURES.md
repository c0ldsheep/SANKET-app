# SANKET features

SANKET predicts a mobile-signal drop a few seconds before it happens, so the phone can save what matters
first. Every feature below says when it helps, what SANKET does, and how far it has got.

**Status labels**
- **Tested in the study**: measured in the PRAYAS research (`research/`), with numbers from unseen test data.
- **Built and unit-tested**: in the Android app (`app/`), with automated tests that pass.
- **Built**: in the app and passing Android's code checker, but not yet tried on real phones in real buildings.
- **Planned**: needs the shared fleet server, which is not built yet.

The app has been checked by automated tests and by Android's lint tool. It has not yet been tested on real
phones in real buildings; that field test is the next step.

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
  now. At 70% or more, with the signal falling by at least 1 dB/s, it raises the alarm.
- **Result:** on unseen data it warned at least 10 s ahead in 43.8% of basement entries, against 10.2% for a simple
  threshold, with 1.76 false alarms per hour on real rides.
- **Status:** Tested in the study. Built and unit-tested.

### 3. Ignoring handover zones (neighbour check)
- **When it helps:** the signal dips while riding past a building or between two towers, but nothing is wrong.
- **What SANKET does:** inside concrete every nearby tower fades together. If a neighbouring tower stays steady,
  SANKET cancels the alarm.
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
- **What SANKET does:** saves every megabyte as it arrives, and saves again the moment it expects a drop. When the
  signal is back it asks the server only for the missing part. It checks that the file has not changed on the
  server (ETag or date), so parts of two different versions are never joined. There are no error pop-ups: the
  download simply waits and continues by itself. Finished files go to the phone's Downloads folder.
- **Status:** Built (the header parsing is unit-tested). Needs testing on real phones.

### 7. Share a link to download it safely
- **When it helps:** you find a file in your browser or WhatsApp.
- **What SANKET does:** "Share" the link to SANKET and it downloads with protection. Only secure https links are
  accepted.
- **Status:** Built.

### 8. Demo trip
- **When it helps:** showing the project without going to a basement.
- **What SANKET does:** replays the study's test trip (a scooter going down a basement ramp) through the real app,
  clearly labelled "Demo". The alarm fires at 63 s, 16 s before the signal disappears at 79 s.
- **Status:** Built.

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
  downloads as soon as you are back there, before the signal moves.
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
  working at all, it says so plainly ("check your data pack or Wi-Fi login") instead of failing silently.
- **Status:** Built and unit-tested.

### 14. Dual SIM
- **When it helps:** many riders carry two SIMs, and a basement that is dead on one network can be fine on the other.
- **What SANKET does:** reads both SIMs, remembers dead places separately for each network, and suggests switching
  mobile data when the other SIM is clearly better at that spot.
- **Status:** Built. Needs field testing.

### 15. 5G early warning
- **When it helps:** on 5G phones near a building entrance.
- **What SANKET does:** 5G at 3.5 GHz gets through concrete worse than 4G, so it usually fades first. When a weak 5G
  reading disappears or drops fast while 4G is still fine, SANKET saves progress early. It never raises the full
  alarm on this alone, because field data must confirm it first.
- **Status:** Built and unit-tested. Needs field confirmation.

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
  - Reads the barometer in batches; the detector itself takes about 1.4 µs per reading (measured in the study).
  - If a phone's battery saver kills it, the app notices and shows how to allow it.
- **Status:** Built.

---

## E. Safety and trust

### 18. Safety log
- **When it helps:** someone falls or gets into trouble in an empty basement, and later the family or the police
  need to know when and where the phone lost contact.
- **What SANKET does:**
  - Records every loss and recovery with the time, a rounded position and the last signal level.
  - Each line is locked to the one before it with a SHA-256 hash, so a changed or deleted line is detected.
  - Offline for more than 10 minutes, much longer than usual for that place: the app shows an alert when back.
  - The log can be shared with one tap.
- **Status:** Built and unit-tested. Alerting a safety team while the rider is still offline is Planned.

### 19. Telling dispatch before going quiet
- **When it helps:** a delivery platform marks a rider "unresponsive" or reassigns the order while they are in a lift.
- **What SANKET does:** just before the drop it prepares a short message: "going offline at a known dead zone, back in
  about N minutes", with N learned from that place.
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
  - Asks for consent in plain language before it starts.
  - Keeps everything on the phone, encrypted with AES-256 using a key that cannot leave the phone.
  - Never backs data up to the cloud.
  - Rounds positions to about 11 m and keeps the safety log for 30 days.
  - Deletes everything with one tap.
  - There is no account and nothing is uploaded, and the app uses no third-party libraries.
- **Status:** Built.

---

## Next steps
1. **Field test:** basements and lifts in Navi Mumbai on Jio, Airtel and Vi, recorded with the phone tool in
   `research/live`, to confirm features 9, 14 and 15 and tune their thresholds.
2. **Fleet server:** shared places and notes, the dispatch notice and safety alerts, with Play Integrity checks.
3. **Hindi and Marathi** screens.
4. **Website with early sign-up**, then a Play Store release.
