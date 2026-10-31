# SoundCloud official-widget feasibility test

## Intent
Provide a free, personal-use way to play **public** SoundCloud track or
playlist links inside FireTube without registering a SoundCloud API app.
The official SoundCloud HTML5 embedded player is used via Android WebView.
This is not an API client, stream downloader, media extractor or API-token
workaround. No artist subscription, billing, API credentials, private track
access or SoundCloud user authentication is introduced.

## Scope
- Library > SoundCloud (official player) lets the user paste a public https link.
- Only known soundcloud.com hostnames accepted; unusual ports, inline
  credentials, and scheme/hostname spoofing are rejected.
- The official w.soundcloud.com/player endpoint renders the stream with
  JavaScript enabled in its isolated WebView. File/content access and HTTP
  mixed content are disabled. Main-frame navigation away from the player is
  blocked. No JavaScript bridge is exposed to the app.
- SoundCloud is **not** registered as a Media3 source. This prevents false
  promises about Android Auto, lock screen, Bluetooth, caching, downloads,
  background playback, or blending SoundCloud into the Media3 queue.
- SoundCloud branding and the official player remain visible.

## Verification limits
- This is experimental code pending successful CI and a real Android device test.
- Embedded playback, permissions of individual tracks, site changes, cookie
  policies, age/location restrictions, audio focus, and screen-off behavior
  are not established by compile/unit tests.
- A SoundCloud Artist Pro subscription is required to create a new official
  public API application as documented by SoundCloud in 2026. This widget
  avoids that paid API workflow, but is subject to SoundCloud widget terms
  and continued service availability.
- Do not publish APK, Google account contents or tokens. Never turn on cloud
  billing for this feature.

Sources:
https://developers.soundcloud.com/docs/api/html5-widget
https://developers.soundcloud.com/docs/api/register-app
