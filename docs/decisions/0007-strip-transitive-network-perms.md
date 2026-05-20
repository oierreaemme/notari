# 0007. Strip transitive network permissions and Google datatransport at manifest merge

- **Status:** Accepted
- **Date:** 2026-05-10

## Context

Pillar 1 of `CLAUDE.md` is non-negotiable: the merged Android manifest must never declare
the `INTERNET` permission, and the app must never schedule background work that contacts
remote servers.

When the verification pass for v0.1 was run, the merged debug manifest at
`app/build/intermediates/merged_manifests/debug/AndroidManifest.xml` was found to contain:

```
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.INTERNET" />

<service android:name="com.google.android.datatransport.runtime.backends.TransportBackendDiscovery" />
<service android:name="com.google.android.datatransport.runtime.scheduling.jobscheduling.JobInfoSchedulerService" />
<receiver android:name="com.google.android.datatransport.runtime.scheduling.jobscheduling.AlarmManagerSchedulerBroadcastReceiver" />
```

These come from `com.google.mediapipe:tasks-genai`, which transitively depends on
`com.google.android.datatransport`. The library is benign for our use (we never call its
APIs) but its manifest entries silently re-introduce the network permissions and a
JobScheduler-based background uploader. The CI privacy gate
(`scripts/check-no-internet-permission.sh`) catches the permission, but the services
would still ship in the APK.

## Decision

Strip every transitive network permission and every `com.google.android.datatransport`
component at manifest merge time using `tools:node="remove"`.

The strip list lives in `app/src/main/AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.INTERNET" tools:node="remove" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" tools:node="remove" />
<uses-permission android:name="android.permission.CHANGE_NETWORK_STATE" tools:node="remove" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" tools:node="remove" />
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE" tools:node="remove" />

<service android:name="com.google.android.datatransport.runtime.backends.TransportBackendDiscovery" tools:node="remove" />
<service android:name="com.google.android.datatransport.runtime.scheduling.jobscheduling.JobInfoSchedulerService" tools:node="remove" />
<receiver android:name="com.google.android.datatransport.runtime.scheduling.jobscheduling.AlarmManagerSchedulerBroadcastReceiver" tools:node="remove" />
```

The CI gate is unchanged — it still enforces the post-merge permission set is empty of
network permissions.

## Consequences

- **Positive:** the privacy promise (Pillar 1, Pillar 6) holds end-to-end. The merged
  manifest is provably free of network permissions and of the datatransport uploader.
- **Positive:** the strip list is explicit, auditable, and lives next to the
  `RECORD_AUDIO` declaration so that future contributors see it immediately.
- **Negative:** if a future MediaPipe release adds new datatransport components with new
  class names, we must extend the list. The CI gate catches the permissions but not
  the service names — see "Follow-ups" below.

## Follow-ups

1. Extend `scripts/check-no-internet-permission.sh` to also fail on the presence of
   `com.google.android.datatransport.*` components. Tracked in the next ADR.
2. After v1, evaluate replacing `tasks-genai` with a leaner alternative (e.g.
   building MediaPipe with `-Pno-datatransport`) so we don't have to play whack-a-mole.
