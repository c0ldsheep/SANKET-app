# SANKET: from a test app to real deliveries

Team Invicti, Terna Engineering College, Navi Mumbai. Mentor: Dr. Rohini Palve. October 2026.

Our mentor asked how SANKET could become a real feature for delivery platforms such as Swiggy or Zomato, so that it
can be taken to market. This is our plan: where SANKET stands today, the three steps from here, what each step
needs, and the roadblocks we expect.

## Where SANKET stands today

- **The study** (PRAYAS-COMP-26-S-020): on data it had never seen, SANKET warned at least 10 seconds before the
  loss in 43.8% of basement entries, against 10.2% for a static threshold, at 1.76 false alarms per hour on real
  rides. In a simulated fleet that shares the places where riders lose signal, 95% of entries were warned at least
  10 seconds ahead by day 7.
- **The app**: SANKET 0.4.0 for Android 10 and newer runs the same detector on the phone's real signal. It saves
  downloads before the drop and resumes them after, remembers where signal was lost, notices lifts, and records
  test rides for the field test. It has been checked on an Android emulator and a phone. Website: https://c0ldsheep.github.io/SANKET-app/
- **Not done yet**: tests in real buildings. The basement entries in our study are simulated, and the real
  recordings come from 4G networks in Ireland.

## What a delivery platform gets

When a rider heads into a basement, a lift or a cloud-kitchen hub, the order's critical data is already on the
phone before the signal goes: the order token and OTP, the customer's address and drop notes, the route and the
offline map. The aim is fewer orders stuck at the door, fewer calls to support, and less waiting for a rider who
is paid by the delivery.

## Three steps

### Step 1. Prove it in real buildings (October to November 2026)

- Record at least 50 real entries in Navi Mumbai: basement ramps, lifts, malls and cloud-kitchen hubs, on Jio,
  Airtel and Vi, on 4G and 5G.
- Measure what a platform cares about: seconds of warning before the loss, false alarms per hour, how much of the
  order was saved, and the battery and data used in a shift.
- Needs: two or three Android phones on different networks, the app's test rides (in 0.4.0), and permission from
  building managers.
- Done when: the results of 50 or more real entries are published, good or bad.

### Step 2. A pilot with a small fleet (December 2026 to February 2027)

- 20 to 50 riders from one local fleet: a cloud kitchen's own riders, a pharmacy or grocery delivery service, or a
  courier company.
- Turn on the shared map of dead zones (a small server), so each rider is warned about places other riders found.
- Compare riders with and without SANKET: minutes stuck at the door, orders that failed to load, calls to support.
- Needs: a partner fleet, a small cloud server, Hindi and Marathi screens, and a Play Store release.

### Step 3. Inside a big platform's app (from 2027)

- Ship SANKET as a small Android library (an SDK) that runs inside the platform's delivery partner app. Its core is
  plain Java with no outside libraries, so it is easy to add.
- The platform's server builds the dead-zone map from its whole fleet. Our study suggests this is where most of the
  gain is: in our fleet simulation, the signal alone warned about 40% of entries in time, and shared memory took
  that to 95% within a week.
- One simple hook for the platform's developers: "the signal may drop in N seconds", and their app saves the
  current order first.
- Needs: pilot results to show, an engineering contact at the platform, and security and privacy reviews.

## Roadblocks and how we plan to handle them

| Roadblock | How we plan to handle it |
|---|---|
| The basement entries in our study are simulated | Step 1: record real entries on Indian networks and publish the results |
| On fast ramps the signal can go in under 10 seconds, too fast for any warning that reads only the signal (19% of entries in our study) | Dead-zone memory: once one rider loses signal at a place, the next rider is warned before arriving |
| Phone makers' battery savers close apps running in the background | A foreground service with a visible notification (already in the app), a one-time guide to allow it, and tests on the phone brands riders use most |
| Android shows nearby towers only with location permission | Ask only for what is needed and explain each permission (already in the app); places stay on the phone unless the rider agrees to share |
| India's data protection law (DPDP Act, 2023) | Consent before sharing, only rounded places with no rider identity, and one-tap delete (already in the app) |
| False reports in the shared map | Count a place only after several riders confirm it, and use Android's Play Integrity checks to reject tampered apps |
| Battery and data use | Measured in step 1; SANKET reads the signal once a second only while protection is on, and less in Battery Saver |
| 2G, 3G and 5G standalone behave differently | Downloads stay protected on every network; early warning on 5G standalone stays "experimental" until our recordings confirm it |
| Getting a big platform to listen | Start with small fleets, bring the pilot's numbers, and use our mentor's and the college's network, incubation support and startup programmes |

## How it could pay for itself

- **Platforms and fleets** pay a monthly licence per active rider for the SDK and the dead-zone map.
- **Riders** keep the app for free. That is how riders, and then fleets, find SANKET.
- **Later**, the same warning helps any app that cannot afford a drop in the middle of a task: payments,
  ride-hailing, field service.

## What we need next

- Our mentor's help to reach a first partner fleet for the pilot.
- Two or three phones on Jio, Airtel and Vi for the field test.
- Guidance on a privacy review and on the college's incubation support.
