# Observability

What the app records about its own operation, how long it keeps it, how it
shows it and how it exports it. Everything stays on the device. Nothing is
sent anywhere unless the user exports it and shares the file.

## Questions it answers

A VPN client's user mostly wants to know:

- **Is it working, and since when?** Live throughput, the current session,
  when the core last restarted, and whether the last connectivity test passed.
- **How much did it carry, and over what?** Data per day and per config, split
  into proxied, direct and blocked traffic. The split comes from each outbound's
  protocol, so a routing rule that sends everything direct shows up as such.
- **How reliable is it?** How often connecting works and how long it takes, how
  often a core crashes and restarts, how sessions end (by the user, taken over
  by another VPN, killed by the system), and the most common failure reasons.
- **What happened just before it broke?** A timeline of typed events (session,
  instance, network, permission and config events) next to the raw Xray log of
  that session. Logs are no longer wiped on every connect.

## What is recorded

| Record | Written by | When | Fields |
| --- | --- | --- | --- |
| Session | service | connect, first core ready, disconnect | id, mode (VPN or core only), primary config, start, time to connect, end, end reason |
| Run | service | each Xray process exit | session, config, start, time to API ready, end, exit code, outcome, failure reason |
| Traffic | service | each minute with traffic | minute, session, config, outbound tag, class (proxy/direct/block/other), bytes up and down |
| Event | service and app | as they happen | time, level, category, type, message, attributes, session |

The app's own process records config imports and deletions (with the link
type, never the link), permissions the user declined, and connectivity test
results with their latency. The service records the rest, including changes of
the underlying network (Wi-Fi, mobile data, lost) and whether it has internet
access.
| Log | service | each Xray output line | time, config, line |

The service samples each running core's StatsService every 10 seconds, whether
or not a screen is open, and turns the outbound counters into per-minute deltas.
A counter that goes backwards means the core restarted, and the new value is
counted from zero.

A session the service never closed (its process was killed) is closed with the
end reason `killed` the next time the service starts. The end time is the last
moment the session was seen alive.

A session counts as failed if it never connected and either ended or had its
primary core fail to start. A session still connecting is not counted yet.

Session end reasons: `user`, `revoked` (another VPN took over, or VPN access
was revoked in system settings), `failed` (the tunnel could not be set up),
`stopped` (the service went away for another reason) and `killed`.

Run outcomes: `stopped` (on request), `crashed` (exited after running and was
restarted), `failed` (exited before it was ready, or crashed too often and was
given up on) and `timeout` (its API never answered).

## Storage and expiry

Records live in `noBackupFilesDir/telemetry/<kind>/<UTC date>.<process>.jsonl`,
one JSON object per line:

- `noBackupFilesDir` keeps them out of Android's cloud backup.
- Each process writes only its own files, so the app and the service never
  write to the same file.
- Day files make expiry exact and cheap: a day older than the retention period
  is deleted whole.

| Setting | Default | Choices |
| --- | --- | --- |
| Keep history for | 30 days | 7, 30, 90, 365 days |
| Record history | on | on, off |

Traffic is kept at minute resolution for 2 days. After that, a day file is
rewritten at hour resolution, which caps the data at about 24 rows per outbound
per day. The service applies expiry and compaction when it starts and then once
an hour. "Clear history" deletes everything at once.

Xray's output goes to one log file per session (`files/logs/`), and the last
20 sessions' logs are kept. Each line starts with the time it was logged and
the config it came from, and Xray's own timestamp is removed. A session's log
is capped at 5 MB: its oldest half is dropped when it grows past that.

## Privacy

Records never contain config contents, UUIDs, passwords or keys. They do hold
config names and outbound tags. Outbounds without a tag get one when the core
starts (`outbound-<index>`), because Xray counts traffic only for tagged
outbounds. Xray's own log lines can contain server
addresses and the domains being visited, because that is what Xray logs at its
`info` and `debug` levels.

The export screen redacts by default:

- IPv4 addresses keep only their first number (`203.0.113.7` becomes
  `203.x.x.x`), and IPv6 addresses become `<ipv6>`. Loopback addresses are kept.
- Domains keep only their top-level domain (`www.example.com` becomes
  `<domain>.com`). File names in Xray's log lines, such as `handler.go`, are
  left as they are.
- UUIDs are replaced with `<uuid>`.

Turning redaction off exports the logs as recorded.

## Export

Activity → Export builds one zip for a chosen range and shares it:

- `summary.txt`: the overview as text, readable without tools.
- `sessions.csv`, `runs.csv`, `traffic_hourly.csv`: for spreadsheets.
- `events.jsonl`: every event, one JSON object per line.
- `logs/`: the session logs in the range.
- `manifest.json`: the app, Xray-core and Android versions, export time,
  range, retention setting and whether the export is redacted.

The zip is written to the app's cache (`cache/exports/`, only the latest one
is kept) and shared through the system share sheet.

## In the app

- **Dashboard:**
  - a live throughput chart of the last two minutes
  - today's proxied, direct and blocked data
  - the current session's duration and data
- **Activity** (from the Dashboard), with a range of 24 hours, 7 days or 30 days:
  - **Overview:** a traffic chart split by class, data and connected time per
    config, and reliability: success rate, median time to connect, crashes,
    end reasons and top failure reasons.
  - **Sessions:** each session with its duration, data, config and end reason.
    Opening one shows its traffic chart, runs, events and log.
  - **Events:** the timeline, filtered by level and category.
- **Log:** timestamps, a filter for warnings and errors and one by config, and
  the current session's log with the previous ones a tap away.
- **History settings** (Activity → ⋮): record on or off, how long to keep it,
  the space it uses, and "Clear history".
