# TabletPanel

A wall-mounted Android tablet that shows the time and weather full screen, plus a small server on the home network that controls what the tablet shows and accepts alerts over a REST API.

```
 ┌──────────────── Android tablet ────────────────┐        ┌──────── LAN server (Node.js) ────────┐
 │  Clock · current conditions · next 10 hours    │  WS    │  Web UI      http://<host>:8787/      │
 │  ───────────────────────────────────────────── │◄──────►│  REST API    /api/...                 │
 │  Focus panel: forecast / hourly / rain /       │        │  WebSocket   /ws (state push)         │
 │               wind / radar                     │        │  mDNS        _tabletpanel._tcp        │
 │  Alert banners · critical takeover · notes     │        │  State file  server/data/state.json   │
 └───────────┬────────────────────────────────────┘        └───────────────────────────────────────┘
             │ HTTPS
             ▼
   open-meteo.com (forecast) · RainViewer (radar) · Esri (map tiles)
```

The tablet fetches weather itself, using its own location or one set from the web UI. The server never talks to the internet; it only holds settings, alerts and notes, and pushes them to connected tablets the moment they change.

## Quick start

### 1. Server

Requires Node.js 20 or later.

```sh
cd server
npm install
npm start            # http://<this-machine>:8787
```

| Env var     | Default             | Purpose                                                                   |
|-------------|---------------------|---------------------------------------------------------------------------|
| `PORT`      | `8787`              | HTTP and WebSocket port                                                   |
| `API_KEY`   | none                | When set, `/api/*` and `/ws` require `X-API-Key: <key>` (or `?key=`)      |
| `DATA_FILE` | `data/state.json`   | Where settings, alerts and notes are saved                                |
| `MDNS`      | `1`                 | Set `0` to stop advertising the server on the LAN                         |
| `CONFIG_FILE` | `config.json`     | Site-specific settings (below)                                            |

**Site config:** settings for your own site live in `server/config.json`, which is not committed. Copy `server/config.example.json` to start:

```json
{
  "location": { "lat": 28.4294, "lon": -81.3089, "name": "Orlando International Airport (MCO)" },
  "nwsUserAgent": "TabletPanel (you@example.com)"
}
```

- `location`: where to look up [weather alerts](#weather-alerts-nws) when none is set in the web UI. Without it, the server uses the places the tablets report. The tablets' own forecasts still use their GPS, or the web UI's location.
- `nwsUserAgent`: contact details for the National Weather Service (the `NWS_USER_AGENT` env var takes precedence).

Locations set in the web UI are saved in `server/data/`, which is not committed either.

On Windows, allow Node through the firewall for private networks (Windows asks on first run). Otherwise the tablet can't connect.

**Start automatically:** on this setup the server is started and supervised by the sibling `../webserver` project, which runs it from its `services.json` alongside the other panels. That project restarts it if it exits and logs it to `../webserver/.run/logs/tabletpanel.log`. It is started, stopped or restarted from the TabletPanel tile on the webserver front page (http://<this PC>/), and it starts at logon through the webserver's single logon task. To run it on its own instead, use `npm start` here, after stopping it on that tile.

### 2. Android app

Open `android/` in Android Studio, or build from the command line:

```sh
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The source package is the generic `com.example.tabletpanel`. To give the installed app your own application id, add it to `android/local.properties` (not committed):

```properties
tabletpanel.applicationId=com.yourname.tabletpanel
```

Keep it the same once the app is installed: a different id installs as a separate app with its own settings.

On first launch the app asks for location permission, then finds the server automatically over mDNS. **Long-press the clock** to open tablet settings: display name, a fixed server address (needed if mDNS is blocked on your network), API key, and Exit.

For a dedicated wall display:
- Keep it plugged in. The app keeps the screen on, hides the system bars and shows over the lock screen.
- Use Android's *App pinning* (Settings → Security) so the app can't be swiped away.
- Optional: turn on *Stay awake while charging* in Developer options.

Requires Android 8.0 (API 26) or later. Google Play services are not required.

## What the display shows

**Top half (always):** clock and date, location with sunrise and sunset, current temperature and conditions, feels-like, high and low, wind, humidity, and the next 10 hours. The background tint follows the sky: blue for clear days, navy at night, slate for rain, violet for storms.

**Lower half (the "focus"):**

| Focus      | Content                                                                                         |
|------------|-------------------------------------------------------------------------------------------------|
| `auto`     | The tablet picks one of the panels below based on the forecast (see the rules that follow)      |
| `forecast` | 10-day outlook with high/low range bars, chance of rain and amounts                             |
| `hourly`   | 24-hour temperature curve, icons and precipitation chance                                       |
| `rain`     | Rain and storm summary: start time and countdown (or end time if already raining), expected total, chance, duration, heaviest rate, wind and gusts, thunder/snow/severe badges; 15-minute rain-rate nowcast for 6 hours; 24-hour chance and amount chart |
| `wind`     | Compass with current direction and speed, peak gusts, 24-hour wind and gust chart               |
| `radar`    | Animated radar loop (last ~2 hours) centred on the location                                     |

**Auto rules** (`android/.../weather/Outlook.kt`):
1. Rain or snow now, or starting within 12 hours, shows **rain**. A severe event (thunder, gusts ≥ 70 km/h or ≥ 25 mm expected) counts from 24 hours out.
2. Gusts ≥ 50 km/h or sustained wind ≥ 35 km/h within 12 hours, with no rain due, shows **wind**.
3. Otherwise it shows the long-range **forecast**.

Rain start times use Open-Meteo's 15-minute data when the event is within ~6 hours, and hourly data beyond that.

**Alerts:** `info` and `warning` alerts appear as banners across the top; `critical` takes over the whole screen with a pulsing red background. Tap to dismiss (unless sent with `"dismissible": false`); dismissing on the tablet removes the alert everywhere. New alerts can chime; critical alerts use an alarm tone.

**Notes** scroll along the bottom bar until they are removed.

**Night dimming:** when the room is dark the screen drops to the chosen brightness and the UI darkens. If the tablet has an ambient light sensor (and "use light sensor" is on), it dims after the light has stayed below the dark threshold (default 5 lux) for a minute, and brightens as soon as the light rises well above it (3× the threshold, at least +10 lux). Tablets without a sensor follow the start/end schedule instead. The web UI's device card shows the current lux reading and why the screen is dimmed.

## REST API

All bodies are JSON. If `API_KEY` is set, send `X-API-Key: <key>` with every request.

### Alerts

```sh
# info | warning | critical. durationSeconds: 0 = until dismissed.
# Defaults: info 5 min, warning 30 min, critical until dismissed.
curl -X POST http://server:8787/api/alerts -H "Content-Type: application/json" \
  -d '{"title":"Garage door open","message":"Open 15 minutes","severity":"warning","durationSeconds":600}'
```

| Field            | Type    | Notes                                                                 |
|------------------|---------|-----------------------------------------------------------------------|
| `title`          | string  | Title, message, or both                                               |
| `message`        | string  |                                                                       |
| `severity`       | string  | `info` (default), `warning`, `critical`                               |
| `durationSeconds`| number  | 0 = until dismissed                                                   |
| `sound`          | boolean | Defaults to true except for `info`                                    |
| `dismissible`    | boolean | Default true                                                          |
| `tag`            | string  | A new alert with the same tag replaces the old one (e.g. `doorbell`)  |
| `source`         | string  | Shown in the web UI                                                   |

- `GET /api/alerts`: active alerts
- `DELETE /api/alerts/:idOrTag`: remove one alert
- `DELETE /api/alerts`: remove all alerts

### Alerts over MQTT

With `MQTT_URL` set (and `MQTT_USERNAME`, `MQTT_PASSWORD`), alerts come from the shared MQTT broker that `../webserver` runs, so every display (tablets, the Pi, other devices) sees the same ones. `../webserver` passes its `tabletpanel` login in these variables (`"mqttUser"` in its `services.json`). Without `MQTT_URL` the server works as described above. The code is `server/src/mqtt.js`.

- **Receiving:** the server subscribes to `alerts/#` (retained JSON per alert: `level`, `title`, `message`, `since`, optional `expires`) and relays to the tablets over the WebSocket, under the tag `<source>-<id>`. It shows warnings and criticals from every source, and every level from the sources in `ALERT_ALL_LEVEL_SOURCES` (default `manual,tabletpanel`); `ALERT_MIN_LEVEL` changes the default `warning`. An empty retained message removes the alert.
- **Per-tablet "Show alerts":** the server keeps every alert and filters per client. A tablet registered on the webserver's IoT Devices page (`registry/devices/<name>` with `"kind": "tablet"`, its `deviceId` and `"alerts"`: `all`, `warning`, `critical` or `off`) gets only what its setting allows; without a setting it gets the default above, as do the web UI and `GET /api/state` / `/api/alerts`. A change on the hub reaches the tablets at once.
- **Chimes:** an alert chimes when it first appears, when its level rises, and every `ALERT_REPOST_HOURS` (24) while it lasts. Other updates, such as a new number in the message, change it without a chime.
- **Sending:** `POST /api/alerts` publishes `alerts/tabletpanel/<tag or random id>` and returns 202 `{id, tag, topic}`; the alert appears when it comes back from the broker. `durationSeconds` becomes the message's expiry. The web UI's send form is replaced by a link to the webserver's Send alert tile.
- **Dismissing** on a tablet, or `DELETE /api/alerts/:idOrTag`, hides an alert on the tablets only: it stays on the bus for other displays and comes back if its level rises or after `ALERT_REPOST_HOURS`. TabletPanel's own alerts (`alerts/tabletpanel/...`) are cleared on the bus instead. Hidden alerts are remembered in `data/mqtt-state.json`.
- `GET /api/health` reports `mqtt: {enabled, connected, error, dismissed}`. The server announces itself on `status/tabletpanel` (`online`, or `offline` as its last will).

### Weather alerts (NWS)

With MQTT on, the server also polls the US National Weather Service (`api.weather.gov/alerts/active`) every `WEATHER_ALERTS_INTERVAL` seconds (120) for the watches, warnings and advisories in force at the panels' location: the one set in the web UI, else `location` in `server/config.json`, else each place a tablet has reported (rounded to ~100 m, so tablets in one house make one request). The code is `server/src/weather-alerts.js`.

- Each NWS alert is published as a retained `alerts/tabletpanel/nws-<id>` message (the `tabletpanel` login may only publish under `alerts/tabletpanel/`), so the tablets, the hub and other displays all get it. The broker drops it when the hazard ends, and the server clears it with an empty message as soon as NWS no longer lists it.
- Levels line up with the webserver hub's **Severe weather** alert (`critical`: take shelter now, every display taken over). Only an extreme threat that is happening now gets it (NWS severity Extreme with urgency Immediate: Tornado Warning, Extreme Wind Warning, Flash Flood Emergency). Other warnings, watches and emergencies (Severe Thunderstorm Warning, Tornado Watch, a Hurricane Warning days out) are `warning`; advisories and statements (Heat Advisory, Special Weather Statement) are `info`. Title is the event name, message the NWS headline; the body also carries `area`, `severity`, `urgency`, `nwsId` and `sentBy: "nws"`.
- An NWS update (extended, new area) replaces the alert it references on the same topic, so the tablets change it in place and chime only if its level rises.
- On the tablets they count as the source `weather`: shown from `ALERT_MIN_LEVEL` (warnings and up), or at every level, advisories included, if `ALERT_ALL_LEVEL_SOURCES` lists `weather`. Dismissing one hides it on the tablets only; it stays on the bus.
- A failed request leaves the published alerts as they are until a poll succeeds. Published alerts are remembered in `data/weather-state.json`, so ones that end while the server is down are cleared after a restart.
- `WEATHER_ALERTS=0` turns it off. NWS asks for a User-Agent that identifies the app; set `nwsUserAgent` in `server/config.json` (or the `NWS_USER_AGENT` env var) to add contact details (e.g. `TabletPanel (you@example.com)`).
- `GET /api/health` reports `weatherAlerts: {enabled, points, lastPoll, error, active}`.
- NWS covers the US and its territories only.

### Display focus and settings

```sh
# Show radar for 15 minutes, then go back to auto
curl -X POST http://server:8787/api/focus -H "Content-Type: application/json" -d '{"focus":"radar","minutes":15}'

# Any subset of the display settings
curl -X PATCH http://server:8787/api/display -H "Content-Type: application/json" -d '{
  "focus": "auto",
  "units": "metric",
  "clock24h": true,
  "location": { "lat": 28.4294, "lon": -81.3089, "name": "Orlando (MCO)" },
  "radarZoom": 7,
  "brightness": 0.8,
  "night": { "enabled": true, "useSensor": true, "darkLux": 5, "start": "22:00", "end": "06:30", "brightness": 0.1 }
}'
```

Set `"location": null` to use the tablet's own location again, and `"brightness": null` to follow the system brightness setting.

### Notes, devices, state

- `POST /api/notes {"text": "..."}`, `DELETE /api/notes/:id`
- `GET /api/devices`: connected tablets and what each one is currently showing (temperature, focus, outlook headline, battery, location)
- `POST /api/devices/:id/command {"command": "identify" | "refresh-weather" | "reload"}`, where `:id` can be `all`
- `GET /api/state`: everything in one call
- `GET /api/health`

### WebSocket protocol (`/ws`)

The server sends `{"type":"state","state":{...}}` on connect and on every change, and `{"type":"command","command":"identify"}` for device commands. The tablet sends `hello` (device id, name, version), `status` (every 60 s and whenever its weather or focus changes) and `dismiss` (alert id). Browsers connect with `?role=ui` to also receive `{"type":"devices"}` updates.

## Project layout

```
server/
  src/index.js        Express app, REST routes, WebSocket hub, mDNS advertising
  src/store.js        Settings/alerts/notes state with validation, expiry and JSON persistence
  src/devices.js      Connected-tablet registry and commands
  src/mqtt.js         Alerts over the shared MQTT broker
  src/weather-alerts.js  NWS watches and warnings, published on the broker
  public/             Web control UI (plain HTML/CSS/JS)
  test/               node:test unit tests (npm test)
android/app/src/main/java/com/example/tabletpanel/
  MainActivity.kt     Full-screen kiosk activity, brightness, permissions
  PanelViewModel.kt   Wires server state, location, weather, alerts and status reporting
  data/               Server client (OkHttp WebSocket), mDNS discovery, location, settings
  weather/            Open-Meteo client, WMO codes, outlook analyzer, unit formatting
  ui/                 Compose screens: top section, focus panels, charts, radar, overlays
android/app/src/main/assets/radar.html   Leaflet + RainViewer radar page
```

Tests: `cd server && npm test`, and `cd android && ./gradlew testDebugUnitTest` (outlook rules and formatting).

## Data sources

- **[Open-Meteo](https://open-meteo.com/)**: forecasts. Free without a key for non-commercial use.
- **[RainViewer](https://www.rainviewer.com/api.html)**: radar tiles. The free tier stops at zoom 7, and the map upscales beyond that.
- **Esri World Dark Gray Canvas**: base map tiles for the radar view.

- **[National Weather Service](https://www.weather.gov/documentation/services-web-api)**: watches and warnings (US only).

The tablet contacts the first three, about every 10 minutes (forecast) and every 5 minutes (radar frame list, while radar is showing). The server polls NWS every 2 minutes when MQTT is on.

## Notes and limits

- The app targets SDK 36. Targeting 37 would turn on Android 17's local-network permission, which needs a runtime prompt before the app could reach the server.
- Times are shown in the tablet's timezone. If you set a remote location in another timezone, the clock and forecast times stay in local time.
- Open-Meteo does not publish official watches or warnings; those come from NWS through the server (see [Weather alerts](#weather-alerts-nws)), so they need MQTT and a US location.
