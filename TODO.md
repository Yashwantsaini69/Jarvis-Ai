# Jarvis — Future To-Do

## Done
- [x] Wake word ("Hey Jarvis") support — v3.26, on-device openWakeWord, foreground + background foreground-service, full-screen wake
- [x] Wake word diagnostics + sensitivity — v3.27, live mic score in STANDBY, vendored detector, threshold 0.30, unused models removed (~11.6 MB)
- [x] App-open handoff — v3.27, opening another app by voice ends the live session silently and resumes background wake listening
- [x] Wake word confirmed working — v3.28, foreground connect + background heads-up; peak score display + full-screen permission shortcut
- [x] Wake OFF normal mode — v3.29, app open auto-connect + live session stays in background; background re-arm 20s -> 3s
- [x] Background app control — v3.30, declared SYSTEM_ALERT_WINDOW + Settings "Display over other apps" row (fixes 2nd app after YouTube)
- [x] Voice unmute — v3.31, removed background wake service; while muted, local "Hey Jarvis" detector unmutes without touch; live session + background app control kept
- [x] Help button — v3.32, shorter SAVE bar + "?" help dialog with all features in simple English
- [x] New robot logo (`App_new_logo.png`)
- [x] Fix no-reply: server error surfacing, multi-part parsing, setup defaults, connection timeout
- [x] Uncensored mode ON/OFF in Settings
- [x] Session button shows START / END text (mic keeps its own icon)
- [x] Developer identity = Yashwant Saini (migrates old saved instruction)
- [x] Long-term memory (Phase 1): SQLite memory store + EN/HI/Hinglish fact extraction, auto-injected into system instruction

## New project idea (TBD)
- [ ] User kuch naya banana chahta hai (Jarvis ke alawa) — details pending, future me discuss karke add karenge

## Release rule
- [ ] Every new update: update.json notes must include a short "How to use" section for the new feature.

## Next up
- [ ] Test v3.31 voice unmute: mute karo -> background jao -> "Hey Jarvis" -> mic on, no touch
- [ ] Check voice unmute reliability when Jarvis is speaking / noisy room; tune threshold if needed
- [ ] Personalize: user ka naam, city, language preference ke hisaab se Jarvis ko instruct karna
- [ ] Settings me "About me" section (naam, city, tone, favourite topics)
- [ ] Chat me text input (mic ke bina bhi type karke puchh sake)
- [ ] Reply ka text bina TTS ke optional "text-only" mode
- [ ] In-app update button jo naya APK `Download/A_Ai.updates` se install kare
- [ ] Better reconnect on network change (WiFi <-> mobile data)
- [ ] Local transcript export / share
- [ ] Haptic feedback on orb interactions

## Ideas / later
- [ ] Multiple personalities (Jarvis / Friday / Ultron style)
- [ ] Battery + device status voice commands
- [ ] Hindi-first voice mode
