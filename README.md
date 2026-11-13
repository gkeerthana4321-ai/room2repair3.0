# Room2Repair: Android app for Meta glasses

*Walk the unit, talk to your glasses, leave with the repair tickets.*

**Cost: $0.** No Play Store, no WhatsApp, no server, no subscriptions.

- Built on Meta's official **Wearables Device Access Toolkit** (free).
- AI from **Groq's free tier** (no credit card).
- Tickets and photos are stored on the phone.
- The app is installed straight from a file.

## How it works

1. **"Take a photo"**: the glasses camera takes the shot.
2. The AI works out which room it is and compares the photo with that room's **move-in photo**.
3. The glasses **read the drafted ticket aloud**: *"Kitchen, new damage. Patch drywall hole by fridge. Medium priority, painting and drywall, about 120 to 250 dollars, tenant pays. Say approve, skip, or describe it."*
4. **"The hole is behind the door"** (optional): it redrafts the ticket with your description.
5. **"Approve"**: the ticket is saved, with before and after photos, on the **Tickets** tab. Share it or export a CSV from there.

Voice commands are heard through the **glasses' own microphones** using Meta's on-glasses speech recognition. If your glasses don't offer that yet, the app uses the phone's mic instead. Every voice command also has an on-screen button.

**What you need:**

- An **Android phone** (Android 12 or newer) with the **Meta AI** app installed.
- **Ray-Ban Meta** glasses (Gen 1 or Gen 2) or Oakley Meta.
- Newer Meta glasses such as the Adventurer may work, but Meta's toolkit doesn't officially list them yet.

---

## Step 1: Get the app file (pick one)

### Option A: GitHub builds it for you (free, no software to install)
1. Make a free account at [github.com](https://github.com), then click **+ → New repository**. Name it `room2repair`, choose **Private**, and click **Create**.
2. On the new repo page, click **uploading an existing file**. Drag in **everything inside this folder** and click **Commit changes**.
3. Open the **Actions** tab. A run called **Build Room2Repair APK** starts by itself and takes about 8 minutes. If the Actions tab is empty, your computer probably hid the `.github` folder while uploading:
   - Click **Actions → set up a workflow yourself**.
   - Paste in the contents of `BUILD-WORKFLOW.yml`.
   - Click **Commit**.
4. When the run shows a green ✓, click it, scroll down to **Artifacts**, and download **Room2Repair-apk**. Unzip it to get **Room2Repair.apk**.

If the run fails (red ✗), click it, copy the red error lines, and send them to Claude to fix.

### Option B: Android Studio (free, on any laptop)
1. Install [Android Studio](https://developer.android.com/studio). Choose **File → Open**, select this folder, and wait for "Gradle sync" to finish.
2. Plug in your phone with USB debugging on, then press **▶ Run**. The app installs and opens.

## Step 2: Install on the phone

1. Send **Room2Repair.apk** to the phone by email, Drive or WhatsApp, and tap it.
2. Allow **Install unknown apps** for that app when Android asks.
3. If **Play Protect** warns you, tap **More details → Install anyway**. That's normal for apps that don't come from the Play Store.

## Step 3: One-time setup (in the app's **Setup** tab)

1. **Developer Mode:** open the **Meta AI** app, go to **Settings**, scroll to the bottom, turn on **Developer Mode**, and restart Meta AI. Make sure the glasses are paired and turned on.
2. **Connect:** tap **Connect** in Room2Repair. Meta AI opens; approve **Room2Repair** there.
3. **Free AI key:**
   - Go to [console.groq.com](https://console.groq.com) and sign up (no card needed).
   - Open **API Keys → Create API Key** and copy the key (`gsk_...`).
   - Paste it into the app.
4. **Unit:** type the unit's name, for example *Unit 4B*. Tap **Save**.

## Step 4: Inspect, hands-free

On the **Inspect** tab, tap **Start hands-free inspection**. The first time, Meta AI asks you to allow the glasses' **camera** and **microphone**. You'll hear *"Room2Repair ready…"* in the glasses.

| Say | What happens |
|---|---|
| **"move in"** | Move-in mode: each photo is saved as that room's move-in record |
| **"take a photo"** (or "take a photo of the kitchen") | The glasses take a photo. In move-in mode it's saved; in inspection mode it's compared with the move-in photo |
| **"inspection"** | Switches to inspection mode |
| *describe the damage*, e.g. "the faucet is leaking" | Redrafts the last ticket with your description (within 3 minutes of a photo) |
| **"approve"** / **"skip"** | Saves the ticket or throws it away |
| **"kitchen"** before a photo | Names the room yourself instead of letting the AI guess |
| **"unit 12A"** | Switches to another unit |
| **"repeat"**, **"tickets"**, **"help"** | Repeats the last line, reads open tickets, or lists the commands |

## Demo script (about 5 minutes)

1. **Before the audience arrives:** say *"move in"*, then take a photo of each clean room. Say *"inspection"*.
2. **Live:**
   1. Put a sticky-note "hole" on a wall and a towel "leak" under the sink.
   2. Look at the hole and say *"take a photo"*. The glasses read out the ticket.
   3. Say *"approve"*.
   4. Look at the sink and say *"take a photo"*, then *"there's water pooling under the sink"*, then *"approve"*.
3. **Finish:** show the **Tickets** tab, with before and after photos, priority, cost and who pays. Tap **share** to send a ticket to "maintenance".

**Tips**

- Take the move-in and inspection photos from the same spot so the comparison is clean.
- Each AI answer takes about 3–8 seconds. Narrate while you wait.
- Keep the phone unlocked with the app open during the demo.
- **No glasses yet?** The **Phone photo** button runs the whole flow with the phone camera, and voice still works through the phone mic.

## Troubleshooting

- **Connect does nothing, or says the app isn't recognised:** Developer Mode is off, or Meta AI wasn't restarted after you turned it on.
- **The status shows Glasses ✓ but Camera ✗:** go to Meta AI → your glasses → App connections and check Room2Repair has camera access, then tap *Start hands-free* again.
- **The app doesn't react to your voice:** the status line shows what it's listening with. Turn on **Also listen with the phone mic**. The **Heard: "…"** line shows what it caught.
- **It hears its own voice:** that's rare, because the app ignores the glasses' mics while it's talking. If it happens, lower the glasses volume a little.
- **"Free AI limit reached":** wait a minute. Groq's free tier allows plenty of photos per day.
- **"The AI model name changed":** pick a current vision model at console.groq.com/docs/vision and paste its name into Setup → Advanced.
- **The glasses' capture button does nothing:** button input is an experimental Meta feature that needs Meta's approval. Voice and the on-screen buttons work without it.

## Project map

```
app/src/main/java/com/room2repair/app/
  Glasses.kt              Meta toolkit: session, glasses camera photo, on-glasses speech, capture button
  InspectionViewModel.kt  voice command → photo → AI → spoken ticket → approve
  Commands.kt             what you can say
  Ai.kt                   Groq free vision model: identify the room, compare with move-in, draft tickets
  Voice.kt                text-to-speech into the glasses + phone-mic backup
  Store.kt                tickets, move-in photos and settings saved on the phone
  Ui.kt                   Inspect / Tickets / Setup screens
.github/workflows/build-apk.yml   free GitHub build that produces Room2Repair.apk
```

Built on Meta's official samples ([CameraAccess, BirdSpotter](https://github.com/facebook/meta-wearables-dat-android)) and SDK version 1.0.1. On-glasses speech and button input are marked *experimental* by Meta. They work in Developer Mode, but Meta doesn't allow them in publicly released apps yet.
